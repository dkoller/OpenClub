package org.openclub.api.teams

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime

/** Team names/counts are private, and are never returned without a verified team grant. */
internal fun Application.installTeamHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/teams") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                        HttpStatusCode.Unauthorized)
                    return@get
                }
                val teams = database.onDatabaseThread { db ->
                    // The service filters individual team visibility; no blanket club-wide grant
                    // is required for readers with a grant on only one team.
                    val allowed = db.fetchOne(
                        """SELECT EXISTS (
                            SELECT 1 FROM members m WHERE m.account_id = ?
                              AND m.archived_at IS NULL AND (
                                EXISTS (SELECT 1 FROM club_capability_grants g
                                        WHERE g.member_id = m.id
                                        AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                                OR EXISTS (SELECT 1 FROM team_capability_grants g
                                           WHERE g.member_id = m.id
                                           AND g.capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS'))
                              )
                        )""", actor.accountId,
                    )?.get(0, Boolean::class.java) == true
                    if (allowed) TeamAssignments(db).list(actor.accountId) else null
                }
                if (teams == null) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                } else {
                    val payload = buildJsonObject {
                        put("items", buildJsonArray {
                            for (team in teams) {
                                add(buildJsonObject {
                                    put("id", team.id.toString())
                                    put("name", team.name)
                                    put("memberCount", team.memberCount)
                                })
                            }
                        })
                    }
                    call.respondText(payload.toString(), ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
