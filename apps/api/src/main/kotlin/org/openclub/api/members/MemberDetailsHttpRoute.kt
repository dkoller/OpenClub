package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime

/**
 * `GET /api/v1/members/{memberId}/details` (OpenAPI 1.3.0; owner-approved 2026-11-18). Authorization is decided
 * before the path is validated: a caller who may read no member's details gets 403 for every ID, even a
 * malformed one. A malformed ID from a caller who holds EDIT_MEMBERS or MANAGE_TEAMS is 400. A read needs no
 * CSRF token (it changes nothing but the audit trail) and is never cacheable.
 */
internal fun Application.installMemberDetailsHttpRoute(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/members/{memberId}/details") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                    ?: return@get call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                val mayRead = database.onDatabaseThread { db ->
                    val access = ClubCapabilityAccess(db)
                    access.has(actor.accountId, ClubCapabilityAccess.Capability.EDIT_MEMBERS) ||
                        access.holdsAnyTeamManagement(actor.accountId)
                }
                if (!mayRead) return@get call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val id = call.memberId() ?: return@get call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MemberDetailsRead(it).read(actor.accountId, id) }) {
                    is MemberDetailsRead.Result.Found ->
                        call.respondText(result.body.toString(), ContentType.Application.Json)
                    MemberDetailsRead.Result.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    MemberDetailsRead.Result.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}
