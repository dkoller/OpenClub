package org.openclub.api.teams

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.rejectedTheData
import java.util.UUID
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import org.openclub.api.refuse
import org.openclub.api.readSession
import org.openclub.api.logFailure
import org.openclub.api.isSameOriginWrite

/** Team names/counts are private, and are never returned without a verified team grant. */
internal fun Application.installTeamHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
    origin: String,
) {
    routing {
        get("/api/v1/teams") {
            call.readSession(sessions, cookies) { actor ->
                val teams = database.onDatabaseThread { db ->
                    // The service filters individual team visibility; no blanket club-wide grant
                    // is required for readers with a grant on only one team.
                    val allowed = db.fetchOne(
                        "SELECT ${ActorSql.holdsTeamCapability(null, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS)}",
                        actor.accountId,
                    )?.get(0, Boolean::class.java) == true
                    if (allowed) TeamAssignments(db).list(actor.accountId) else null
                }
                if (teams == null) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
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
            }
        }
        post("/api/v1/teams") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                    return@post
                }
                if (!call.isSameOriginWrite(actor, origin)) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@post
                }
                if (!database.onDatabaseThread { it.canManageTeams(actor.accountId) }) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@post
                }
                val name = try {
                    TeamAssignments.validName(call.readTeamName())
                } catch (_: IllegalArgumentException) {
                    call.refuse(HttpStatusCode.BadRequest, "invalid")
                    return@post
                }
                val id = database.onDatabaseThread { TeamAssignments(it).create(actor.accountId, name) }
                if (id == null) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                } else {
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/teams/$id")
                    call.respondText(buildJsonObject { put("id", id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DataAccessException) {
                if (failure.rejectedTheData()) {
                    call.refuse(HttpStatusCode.Conflict, "conflict")
                } else {
                    call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        patch("/api/v1/teams/{teamId}") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                    return@patch
                }
                if (!call.isSameOriginWrite(actor, origin)) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                if (!database.onDatabaseThread { it.canManageTeams(actor.accountId) }) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                val (teamId, name) = try {
                    UUID.fromString(call.parameters["teamId"]) to TeamAssignments.validName(call.readTeamName())
                } catch (_: IllegalArgumentException) {
                    call.refuse(HttpStatusCode.BadRequest, "invalid")
                    return@patch
                }
                if (database.onDatabaseThread { TeamAssignments(it).rename(actor.accountId, teamId, name) }) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.refuse(HttpStatusCode.NotFound, "not_found")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DataAccessException) {
                if (failure.rejectedTheData()) {
                    call.refuse(HttpStatusCode.Conflict, "conflict")
                } else {
                    call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        delete("/api/v1/teams/{teamId}") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                    return@delete
                }
                if (!call.isSameOriginWrite(actor, origin)) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@delete
                }
                if (!database.onDatabaseThread { it.canManageTeams(actor.accountId) }) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@delete
                }
                val (teamId, expectedName) = try {
                    UUID.fromString(call.parameters["teamId"]) to call.readTeamName()
                } catch (_: IllegalArgumentException) {
                    call.refuse(HttpStatusCode.BadRequest, "invalid")
                    return@delete
                }
                val outcome = database.onDatabaseThread { db ->
                    db.transactionResult { configuration ->
                        val tx = DSL.using(configuration)
                        if (!tx.canManageTeams(actor.accountId)) TeamDelete.FORBIDDEN else {
                            // Hold the target row until deletion so a concurrent rename
                            // cannot invalidate the human's exact-name confirmation.
                            val name = tx.fetchOne(
                                "SELECT name FROM teams WHERE id = ? FOR UPDATE", teamId,
                            )?.get(0, String::class.java)
                            when {
                                name == null -> TeamDelete.NOT_FOUND
                                name != expectedName -> TeamDelete.NAME_CHANGED
                                TeamAssignments(tx).remove(actor.accountId, teamId) -> TeamDelete.DELETED
                                else -> TeamDelete.NOT_FOUND
                            }
                        }
                    }
                }
                when (outcome) {
                    TeamDelete.FORBIDDEN -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    TeamDelete.NOT_FOUND -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    TeamDelete.NAME_CHANGED -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    TeamDelete.DELETED -> call.respond(HttpStatusCode.NoContent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}

private enum class TeamDelete { FORBIDDEN, NOT_FOUND, NAME_CHANGED, DELETED }

/** Club-wide MANAGE_TEAMS; a cheap check that runs before request input is validated. */
private fun DSLContext.canManageTeams(accountId: UUID): Boolean =
    fetchOne("SELECT 1 WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_TEAMS)}", accountId) != null

/** Single strict name-only body shared by team create, rename and delete confirmation. */
private suspend fun ApplicationCall.readTeamName(): String =
    receiveJsonObject(required = setOf("name")).requiredString("name")
