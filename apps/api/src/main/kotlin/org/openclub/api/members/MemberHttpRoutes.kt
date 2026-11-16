package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import java.time.LocalDate
import java.util.UUID

/** Member administration is always session-linked and capability-checked server-side. */
internal fun Application.installMemberHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        route("/api/v1/members") {
            get {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@get
                    val q = call.request.queryParameters["q"] ?: ""
                    require(q.trim().length <= 100 && (q.isEmpty() || q.isNotBlank()))
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: run {
                        require(call.request.queryParameters["limit"] == null)
                        20
                    }
                    require(limit in 1..50)
                    val after = call.request.queryParameters["cursor"]?.let {
                        MemberPageCursor.decode(actor.accountId, q.trim(), it)
                    }
                    val page = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)) null
                        else MemberDirectory(db).search(actor.accountId, q, limit, after)
                    }
                    if (page == null) {
                        call.error(HttpStatusCode.Forbidden, "forbidden")
                        return@get
                    }
                    val response = buildJsonObject {
                        put("items", buildJsonArray {
                            page.members.forEach { member ->
                                add(buildJsonObject {
                                    put("id", member.id.toString())
                                    put("firstName", member.firstName)
                                    put("lastName", member.lastName)
                                })
                            }
                        })
                        page.next?.let { put("nextCursor", MemberPageCursor.encode(actor.accountId, q.trim(), it)) }
                    }
                    call.respondText(response.toString(), ContentType.Application.Json)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            post {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@post
                    if (!call.requireWrite(actor, origin)) return@post
                    val fields = call.readObject(setOf("firstName", "lastName", "dateOfBirth"),
                        setOf("email", "phone", "gender", "isPlayer"))
                    val input = MemberCreation.Input(
                        firstName = fields.string("firstName"),
                        lastName = fields.string("lastName"),
                        dateOfBirth = LocalDate.parse(fields.string("dateOfBirth")),
                        email = fields.optionalString("email"),
                        phone = fields.optionalString("phone"),
                        gender = fields.optionalString("gender"),
                        isPlayer = fields["isPlayer"]?.jsonPrimitive?.boolean ?: false,
                    )
                    val id = database.onDatabaseThread { MemberCreation(it).create(actor.accountId, input) }
                    if (id == null) call.error(HttpStatusCode.Forbidden, "forbidden")
                    else {
                        call.response.headers.append(HttpHeaders.Location, "/api/v1/members/$id")
                        call.respondText(buildJsonObject { put("id", id.toString()) }.toString(),
                            ContentType.Application.Json, HttpStatusCode.Created)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DataAccessException) {
                    // Database field constraints remain authoritative; never echo stored values.
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            get("/{memberId}") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@get
                    val id = UUID.fromString(call.parameters["memberId"])
                    val detail = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)) {
                            null
                        } else {
                            db.fetchOne(
                                """SELECT m.first_name, m.last_name FROM members m
                                   JOIN members actor ON actor.account_id = ?
                                   JOIN club_capability_grants g ON g.member_id = actor.id
                                      AND g.capability = 'VIEW_MEMBER_DIRECTORY'
                                   WHERE m.id = ? AND m.archived_at IS NULL
                                      AND actor.archived_at IS NULL""",
                                actor.accountId, id,
                            )?.let { it.get(0, String::class.java) to it.get(1, String::class.java) }
                        }
                    }
                    if (detail == null) {
                        // Distinguish lack of capability from missing target without revealing target details.
                        val allowed = database.onDatabaseThread {
                            ClubCapabilityAccess(it).has(actor.accountId,
                                ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)
                        }
                        call.error(if (allowed) HttpStatusCode.NotFound else HttpStatusCode.Forbidden,
                            if (allowed) "not_found" else "forbidden")
                    } else call.respondText(buildJsonObject {
                        put("id", id.toString())
                        put("firstName", detail.first)
                        put("lastName", detail.second)
                    }.toString(), ContentType.Application.Json)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            patch("/{memberId}") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@patch
                    if (!call.requireWrite(actor, origin)) return@patch
                    val id = UUID.fromString(call.parameters["memberId"])
                    val fields = call.readObject(setOf("firstName", "lastName"), emptySet())
                    val result = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.EDIT_MEMBERS)) null
                        else MemberDetailsUpdate(db).updateNames(actor.accountId, id,
                            fields.string("firstName"), fields.string("lastName"))
                    }
                    when (result) {
                        null -> call.error(HttpStatusCode.Forbidden, "forbidden")
                        false -> call.error(HttpStatusCode.NotFound, "not_found")
                        true -> call.respond(HttpStatusCode.NoContent)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            post("/{memberId}/archive") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@post
                    if (!call.requireWrite(actor, origin)) return@post
                    val id = UUID.fromString(call.parameters["memberId"])
                    val result = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.EDIT_MEMBERS)) null
                        else {
                            val exists = db.fetchOne("SELECT 1 FROM members WHERE id = ? AND archived_at IS NULL", id) != null
                            if (!exists) false to false else true to MemberArchive(db).archive(actor.accountId, id)
                        }
                    }
                    when {
                        result == null -> call.error(HttpStatusCode.Forbidden, "forbidden")
                        !result.first -> call.error(HttpStatusCode.NotFound, "not_found")
                        !result.second -> call.error(HttpStatusCode.Conflict, "conflict")
                        else -> call.respond(HttpStatusCode.NoContent)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    call.error(HttpStatusCode.BadRequest, "invalid")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireActor(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
): ActiveSession? {
    val actor = sessions.read(request.cookies[cookies.sessionName])
    if (actor == null) error(HttpStatusCode.Unauthorized, "unauthenticated")
    return actor
}

private suspend fun ApplicationCall.requireWrite(actor: ActiveSession, origin: String): Boolean {
    if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
        request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
        error(HttpStatusCode.Forbidden, "forbidden")
        return false
    }
    return true
}

private suspend fun ApplicationCall.readObject(required: Set<String>, optional: Set<String>): JsonObject {
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
        ?: throw IllegalArgumentException("Invalid JSON object")
    require(fields.keys.containsAll(required) && fields.keys.all { it in required || it in optional })
    return fields
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("Missing field")

private fun JsonObject.optionalString(key: String): String? = this[key]?.let {
    if (it is JsonNull) null else it.jsonPrimitive.content
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) {
    respondText(buildJsonObject { put("code", code); put("message", "Request rejected") }.toString(),
        ContentType.Application.Json, status)
}
