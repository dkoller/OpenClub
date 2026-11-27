package org.openclub.api.club

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import org.openclub.api.refuse
import org.openclub.api.logFailure

/** A narrow synthetic-M1 settings write; neither role nor actor is supplied by the caller. */
internal fun Application.installClubNameRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        patch("/api/v1/club") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                    return@patch
                }
                if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
                    call.request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                // Checked before the body is validated, so callers without the grant always get 403.
                // The UPDATE below repeats the check atomically.
                if (database.onDatabaseThread { it.holdsClubAdminGrant(actor.accountId) }.not()) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                val name = try {
                    call.receiveJsonObject(required = setOf("name")).requiredString("name").trim().also {
                        require(it.length in 1..200 && it.none { char -> char.isISOControl() })
                    }
                } catch (_: IllegalArgumentException) {
                    call.refuse(HttpStatusCode.BadRequest, "invalid")
                    return@patch
                }
                // The update and live authorization are one statement, so grant revocation
                // cannot be bypassed by a stale UI state or a forged actor identifier.
                val changed = database.onDatabaseThread { db ->
                    db.execute(
                        """UPDATE clubs SET name = ?
                           WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_PERMISSIONS, requireAdmin = true)}""",
                        name, actor.accountId,
                    ) == 1
                }
                if (changed) call.respond(HttpStatusCode.NoContent)
                else call.refuse(HttpStatusCode.Forbidden, "forbidden")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}

/** Live, identity-bound ADMIN with MANAGE_PERMISSIONS: the grant every club settings write needs. */
internal fun org.jooq.DSLContext.holdsClubAdminGrant(accountId: java.util.UUID): Boolean =
    fetchOne(
        "SELECT 1 WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_PERMISSIONS, requireAdmin = true)}",
        accountId,
    ) != null
