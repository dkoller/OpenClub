package org.openclub.api.teams

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.MemberDirectory
import org.openclub.api.members.MemberPageCursor
import java.util.UUID

/** Team roster names need both live directory and team grants. Mutations need MANAGE_TEAMS. */
internal fun Application.installTeamMemberHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
    origin: String,
) {
    routing {
        get("/api/v1/teams/{teamId}/members") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = call.actor(sessions, cookies) ?: return@get
                // The directory grant is required for every roster, so check it before validating input.
                if (!database.onDatabaseThread {
                        ClubCapabilityAccess(it).has(actor.accountId,
                            ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)
                    }) {
                    call.rejected(HttpStatusCode.Forbidden, "forbidden")
                    return@get
                }
                val query = try {
                    val teamId = UUID.fromString(call.parameters["teamId"])
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: run {
                        require(call.request.queryParameters["limit"] == null)
                        20
                    }
                    require(limit in 1..50)
                    // Reuse the encrypted per-account cursor, scoped to this exact team.
                    val scope = "team:$teamId"
                    val after = call.request.queryParameters["cursor"]?.let {
                        val decoded = MemberPageCursor.decode(actor.accountId, scope, it)
                        TeamMemberDirectory.After(decoded.lastName, decoded.firstName, decoded.id)
                    }
                    RosterQuery(teamId, limit, scope, after)
                } catch (_: IllegalArgumentException) {
                    call.rejected(HttpStatusCode.BadRequest, "invalid")
                    return@get
                }
                val (teamId, limit, scope, after) = query
                val page = database.onDatabaseThread { db ->
                    val access = ClubCapabilityAccess(db)
                    if (!access.has(actor.accountId, ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY) ||
                        !access.hasForTeam(actor.accountId, teamId, ClubCapabilityAccess.Capability.VIEW_TEAMS)
                    ) null else TeamMemberDirectory(db).list(actor.accountId, teamId, limit, after)
                }
                if (page == null) {
                    call.rejected(HttpStatusCode.Forbidden, "forbidden")
                    return@get
                }
                val response = buildJsonObject {
                    put("items", buildJsonArray {
                        for (member in page.members) add(buildJsonObject {
                            put("id", member.id.toString())
                            put("firstName", member.firstName)
                            put("lastName", member.lastName)
                        })
                    })
                    page.next?.let {
                        put("nextCursor", MemberPageCursor.encode(actor.accountId, scope,
                            MemberDirectory.After(it.lastName, it.firstName, it.id)))
                    }
                }
                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.rejected(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        post("/api/v1/teams/{teamId}/members/{memberId}") {
            call.changeAssignment(sessions, cookies, database, origin, assign = true)
        }
        delete("/api/v1/teams/{teamId}/members/{memberId}") {
            call.changeAssignment(sessions, cookies, database, origin, assign = false)
        }
    }
}

private suspend fun ApplicationCall.changeAssignment(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
    origin: String,
    assign: Boolean,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = actor(sessions, cookies) ?: return
        if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
            request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
            rejected(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        // Holding MANAGE_TEAMS on at least one team (or club-wide) is required for any assignment,
        // so callers without it get 403 before their path parameters are validated.
        val mayManageSomeTeam = database.onDatabaseThread { db ->
            db.fetchOne(
                "SELECT ${ActorSql.holdsTeamCapability(null, Capability.MANAGE_TEAMS)}", actor.accountId,
            )?.get(0, Boolean::class.java) == true
        }
        if (!mayManageSomeTeam) {
            rejected(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        val (teamId, memberId) = try {
            UUID.fromString(parameters["teamId"]) to UUID.fromString(parameters["memberId"])
        } catch (_: IllegalArgumentException) {
            rejected(HttpStatusCode.BadRequest, "invalid")
            return
        }
        val changed = database.onDatabaseThread { db ->
            val access = ClubCapabilityAccess(db)
            if (!access.hasForTeam(actor.accountId, teamId, ClubCapabilityAccess.Capability.MANAGE_TEAMS)) null
            else {
                val teams = TeamAssignments(db)
                if (assign) teams.assign(actor.accountId, teamId, memberId)
                else teams.unassign(actor.accountId, teamId, memberId)
            }
        }
        when (changed) {
            null -> rejected(HttpStatusCode.Forbidden, "forbidden")
            false -> rejected(if (assign) HttpStatusCode.Conflict else HttpStatusCode.NotFound,
                if (assign) "conflict" else "not_found")
            true -> respond(HttpStatusCode.NoContent)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejected(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private data class RosterQuery(
    val teamId: UUID,
    val limit: Int,
    val scope: String,
    val after: TeamMemberDirectory.After?,
)

private suspend fun ApplicationCall.actor(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
): ActiveSession? {
    val session = sessions.read(request.cookies[cookies.sessionName])
    if (session == null) rejected(HttpStatusCode.Unauthorized, "unauthenticated")
    return session
}

private suspend fun ApplicationCall.rejected(status: HttpStatusCode, code: String) {
    respondText(buildJsonObject { put("code", code); put("message", "Request rejected") }.toString(),
        ContentType.Application.Json, status)
}
