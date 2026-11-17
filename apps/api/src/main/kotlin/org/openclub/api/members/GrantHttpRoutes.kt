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
import java.util.UUID
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString

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
                val memberId = UUID.fromString(call.request.queryParameters["memberId"])
                val grants = database.onDatabaseThread { db ->
                    if (!ClubCapabilityAccess(db).has(actor.accountId,
                            ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)) null
                    else if (db.fetchOne("SELECT 1 FROM members WHERE id = ?", memberId) == null) {
                        emptyList<Pair<String, String?>>()
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
                        club + teams
                    }
                }
                if (grants == null) {
                    call.grantError(HttpStatusCode.Forbidden, "forbidden")
                    return@get
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
            } catch (_: IllegalArgumentException) {
                call.grantError(HttpStatusCode.BadRequest, "invalid")
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
        val fields = readGrantBody()
        val memberId = UUID.fromString(fields.requiredString("memberId"))
        val capability = ClubCapabilityAccess.Capability.valueOf(fields.requiredString("capability"))
        val scope = CapabilityGrants.Scope.valueOf(fields.requiredString("scope"))
        val teamId = fields["teamId"]?.let { UUID.fromString(fields.requiredString("teamId")) }
        require((scope == CapabilityGrants.Scope.TEAM) == (teamId != null))
        require(scope != CapabilityGrants.Scope.TEAM ||
            capability == ClubCapabilityAccess.Capability.VIEW_TEAMS ||
            capability == ClubCapabilityAccess.Capability.MANAGE_TEAMS)
        val outcome = database.onDatabaseThread { db ->
            val grants = CapabilityGrants(db)
            if (granting) grants.grant(actor.accountId, memberId, capability, scope, teamId)
            else grants.revoke(actor.accountId, memberId, capability, scope, teamId)
        }
        if (outcome == CapabilityGrants.Change.DENIED) grantError(HttpStatusCode.Forbidden, "forbidden")
        else respond(HttpStatusCode.NoContent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        grantError(HttpStatusCode.BadRequest, "invalid")
    } catch (_: Exception) {
        grantError(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private suspend fun ApplicationCall.readGrantBody(): JsonObject = receiveJsonObject(
    required = setOf("memberId", "capability", "scope"), optional = setOf("teamId"))


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
