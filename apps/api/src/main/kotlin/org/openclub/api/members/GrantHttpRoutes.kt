package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID

/** Explicit ADMIN + MANAGE_PERMISSIONS only; no role or account identity accepted from callers. */
internal fun Application.installGrantHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/grants") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = call.grantActor(sessions, cookies) ?: return@get
                // Authorization is decided before the query is validated, so a caller without
                // MANAGE_PERMISSIONS learns nothing about expected input.
                val memberId = call.request.queryParameters["memberId"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val read = database.onDatabaseThread { db ->
                    if (!ClubCapabilityAccess(db).has(actor.accountId,
                            ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)) GrantRead.Denied
                    else if (memberId == null) GrantRead.Invalid
                    else if (db.fetchOne("SELECT 1 FROM members WHERE id = ?", memberId) == null) {
                        GrantRead.Items(emptyList())
                    } else {
                        val club = db.fetch(
                            "SELECT capability FROM club_capability_grants WHERE member_id = ? ORDER BY capability",
                            memberId,
                        ).map { requireNotNull(it.get(0, String::class.java)) to null }
                        val teams = db.fetch(
                            """SELECT capability, team_id FROM team_capability_grants
                               WHERE member_id = ? ORDER BY capability, team_id""", memberId,
                        ).map { requireNotNull(it.get(0, String::class.java)) to
                            requireNotNull(it.get(1, UUID::class.java)).toString() }
                        GrantRead.Items(club + teams)
                    }
                }
                val grants = when (read) {
                    GrantRead.Denied -> {
                        call.grantError(HttpStatusCode.Forbidden, "forbidden")
                        return@get
                    }
                    GrantRead.Invalid -> {
                        call.grantError(HttpStatusCode.BadRequest, "invalid")
                        return@get
                    }
                    is GrantRead.Items -> read.grants
                }
                val response = buildJsonObject {
                    put("items", buildJsonArray {
                        for ((capability, teamId) in grants) add(buildJsonObject {
                            put("capability", capability)
                            put("scope", if (teamId == null) "CLUB" else "TEAM")
                            if (teamId != null) put("teamId", teamId)
                        })
                    })
                }
                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.grantError(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        post("/api/v1/grants") {
            call.mutateGrant(sessions, cookies, origin, database, granting = true)
        }
        delete("/api/v1/grants") {
            call.mutateGrant(sessions, cookies, origin, database, granting = false)
        }
    }
}

private sealed interface GrantRead {
    data object Denied : GrantRead
    data object Invalid : GrantRead
    data class Items(val grants: List<Pair<String, String?>>) : GrantRead
}

private suspend fun ApplicationCall.mutateGrant(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    granting: Boolean,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = grantActor(sessions, cookies) ?: return
        if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
            request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
            grantError(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        // Advisory pre-check so unauthorized callers get 403 for malformed bodies too; the
        // service below repeats the check atomically under its lock and stays authoritative.
        val permitted = database.onDatabaseThread { db ->
            ClubCapabilityAccess(db).has(actor.accountId, ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)
        }
        if (!permitted) {
            grantError(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        val change = try {
            receiveGrantChange()
        } catch (_: IllegalArgumentException) {
            grantError(HttpStatusCode.BadRequest, "invalid")
            return
        }
        val outcome = database.onDatabaseThread { db ->
            val grants = CapabilityGrants(db)
            if (granting) grants.grant(actor.accountId, change.memberId, change.capability, change.scope, change.teamId)
            else grants.revoke(actor.accountId, change.memberId, change.capability, change.scope, change.teamId)
        }
        if (outcome == CapabilityGrants.Change.DENIED) grantError(HttpStatusCode.Forbidden, "forbidden")
        else respond(HttpStatusCode.NoContent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        grantError(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private class GrantChange(
    val memberId: UUID,
    val capability: ClubCapabilityAccess.Capability,
    val scope: CapabilityGrants.Scope,
    val teamId: UUID?,
)

/** Reads and validates the body. Every violation throws [IllegalArgumentException] (mapped to 400). */
private suspend fun ApplicationCall.receiveGrantChange(): GrantChange {
    val fields = receiveJsonObject(
        required = setOf("memberId", "capability", "scope"), optional = setOf("teamId"))
    val capability = ClubCapabilityAccess.Capability.valueOf(fields.requiredString("capability"))
    val scope = CapabilityGrants.Scope.valueOf(fields.requiredString("scope"))
    val teamId = fields["teamId"]?.let { UUID.fromString(fields.requiredString("teamId")) }
    require((scope == CapabilityGrants.Scope.TEAM) == (teamId != null))
    require(scope != CapabilityGrants.Scope.TEAM ||
        capability == ClubCapabilityAccess.Capability.VIEW_TEAMS ||
        capability == ClubCapabilityAccess.Capability.MANAGE_TEAMS)
    return GrantChange(UUID.fromString(fields.requiredString("memberId")), capability, scope, teamId)
}

private suspend fun ApplicationCall.grantActor(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
): ActiveSession? {
    val actor = sessions.read(request.cookies[cookies.sessionName])
    if (actor == null) grantError(HttpStatusCode.Unauthorized, "unauthenticated")
    return actor
}

private suspend fun ApplicationCall.grantError(status: HttpStatusCode, code: String) {
    respondText(buildJsonObject { put("code", code); put("message", "Request rejected") }.toString(),
        ContentType.Application.Json, status)
}
