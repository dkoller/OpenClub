package org.openclub.api.teams

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jooq.exception.DataAccessException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/** Team names/counts are private, and are never returned without a verified team grant. */
internal fun Application.installTeamHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
    origin: String,
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
        post("/api/v1/teams") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                        HttpStatusCode.Unauthorized)
                    return@post
                }
                if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
                    call.request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    return@post
                }
                val name = call.readTeamName()
                val id = database.onDatabaseThread { TeamAssignments(it).create(actor.accountId, name) }
                if (id == null) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                } else {
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/teams/$id")
                    call.respondText(buildJsonObject { put("id", id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                    HttpStatusCode.BadRequest)
            } catch (_: DataAccessException) {
                call.respondText("""{"code":"conflict"}""", ContentType.Application.Json,
                    HttpStatusCode.Conflict)
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
        patch("/api/v1/teams/{teamId}") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                        HttpStatusCode.Unauthorized)
                    return@patch
                }
                if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
                    call.request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    return@patch
                }
                val teamId = UUID.fromString(call.parameters["teamId"])
                val name = call.readTeamName()
                val outcome = database.onDatabaseThread { db ->
                    val authorized = db.fetchOne(
                        """SELECT 1 FROM members m
                           JOIN club_capability_grants g ON g.member_id = m.id
                           WHERE m.account_id = ? AND m.archived_at IS NULL
                             AND g.capability = 'MANAGE_TEAMS'""",
                        actor.accountId,
                    ) != null
                    if (!authorized) null else TeamAssignments(db).rename(actor.accountId, teamId, name)
                }
                when (outcome) {
                    null -> call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    false -> call.respondText("""{"code":"not_found"}""", ContentType.Application.Json,
                        HttpStatusCode.NotFound)
                    true -> call.respond(HttpStatusCode.NoContent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                    HttpStatusCode.BadRequest)
            } catch (_: DataAccessException) {
                call.respondText("""{"code":"conflict"}""", ContentType.Application.Json,
                    HttpStatusCode.Conflict)
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

/** Single strict name-only body shared by team create and rename. */
private suspend fun ApplicationCall.readTeamName(): String {
    require(request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull()?.let {
        ContentType.parse(it).withoutParameters() == ContentType.Application.Json
    } == true)
    val channel = receiveChannel()
    val bytes = ByteArray(4097)
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count == -1) break
        size += count
    }
    require(size <= 4096)
    val fields = Json.parseToJsonElement(bytes.copyOf(size).toString(Charsets.UTF_8)) as? JsonObject
        ?: throw IllegalArgumentException("Invalid team JSON")
    require(fields.keys == setOf("name"))
    return (fields["name"] as? JsonPrimitive)?.let { require(it.isString); it.content }
        ?: throw IllegalArgumentException("Missing team name")
}
