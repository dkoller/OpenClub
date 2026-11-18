package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.rejectedTheData
import java.time.LocalDate
import java.util.UUID
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString

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
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)) {
                        return@get
                    }
                    val (q, limit, after) = try {
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
                        MemberQuery(q, limit, after)
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@get
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
                                add(memberNameResponse(member.id, member.firstName, member.lastName))
                            }
                        })
                        page.next?.let { put("nextCursor", MemberPageCursor.encode(actor.accountId, q.trim(), it)) }
                    }
                    call.respondText(response.toString(), ContentType.Application.Json)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            post {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@post
                    if (!call.requireWrite(actor, origin)) return@post
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.EDIT_MEMBERS)) {
                        return@post
                    }
                    val input = try {
                        call.readNewMember()
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@post
                    }
                    val id = database.onDatabaseThread { MemberCreation(it).create(actor.accountId, input) }
                    if (id == null) call.error(HttpStatusCode.Forbidden, "forbidden")
                    else {
                        call.response.headers.append(HttpHeaders.Location, "/api/v1/members/$id")
                        call.respondText(buildJsonObject { put("id", id.toString()) }.toString(),
                            ContentType.Application.Json, HttpStatusCode.Created)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DataAccessException) {
                    // Database field constraints and rules remain authoritative; never echo stored values.
                    // A connection loss or timeout is a server fault, not a rejected request.
                    if (failure.rejectedTheData()) call.error(HttpStatusCode.BadRequest, "invalid")
                    else call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            get("/{memberId}") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@get
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY)) {
                        return@get
                    }
                    val id = try {
                        UUID.fromString(call.parameters["memberId"])
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@get
                    }
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
                                      AND ${ActorSql.activeVerified("actor")}""",
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
                    } else call.respondText(
                        memberNameResponse(id, detail.first, detail.second).toString(),
                        ContentType.Application.Json,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            patch("/{memberId}") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@patch
                    if (!call.requireWrite(actor, origin)) return@patch
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.EDIT_MEMBERS)) {
                        return@patch
                    }
                    val (id, firstName, lastName) = try {
                        val id = UUID.fromString(call.parameters["memberId"])
                        val fields = call.readObject(setOf("firstName", "lastName"), emptySet())
                        Triple(id, MemberFieldValidation.name(fields.string("firstName")),
                            MemberFieldValidation.name(fields.string("lastName")))
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@patch
                    }
                    val result = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.EDIT_MEMBERS)) null
                        else MemberDetailsUpdate(db).updateNames(actor.accountId, id, firstName, lastName)
                    }
                    when (result) {
                        null -> call.error(HttpStatusCode.Forbidden, "forbidden")
                        false -> call.error(HttpStatusCode.NotFound, "not_found")
                        true -> call.respond(HttpStatusCode.NoContent)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            post("/{memberId}/archive") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@post
                    if (!call.requireWrite(actor, origin)) return@post
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.EDIT_MEMBERS)) {
                        return@post
                    }
                    val id = try {
                        UUID.fromString(call.parameters["memberId"])
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@post
                    }
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

/** Checked before any request input is validated, so callers without the grant always get 403. */
private suspend fun ApplicationCall.requireCapability(
    database: DatabaseRuntime,
    actor: ActiveSession,
    capability: ClubCapabilityAccess.Capability,
): Boolean {
    val allowed = database.onDatabaseThread { ClubCapabilityAccess(it).has(actor.accountId, capability) }
    if (!allowed) error(HttpStatusCode.Forbidden, "forbidden")
    return allowed
}

private data class MemberQuery(val q: String, val limit: Int, val after: MemberDirectory.After?)

/** Parses and validates a new member; every violation throws [IllegalArgumentException] (400). */
private suspend fun ApplicationCall.readNewMember(): MemberCreation.Input {
    val fields = readObject(setOf("firstName", "lastName", "dateOfBirth"),
        setOf("email", "phone", "gender", "isPlayer"))
    return MemberCreation.Input(
        firstName = MemberFieldValidation.name(fields.string("firstName")),
        lastName = MemberFieldValidation.name(fields.string("lastName")),
        dateOfBirth = MemberFieldValidation.birthDate(
            runCatching { LocalDate.parse(fields.string("dateOfBirth")) }
                .getOrElse { throw IllegalArgumentException("Invalid date") }),
        email = MemberFieldValidation.email(fields.optionalString("email")),
        phone = MemberFieldValidation.phone(fields.optionalString("phone")),
        gender = MemberFieldValidation.gender(fields.optionalString("gender")),
        isPlayer = fields.optionalBoolean("isPlayer") ?: false,
    )
}

private suspend fun ApplicationCall.readObject(required: Set<String>, optional: Set<String>): JsonObject =
    receiveJsonObject(required, optional)

private fun JsonObject.string(key: String): String = requiredString(key)

private fun JsonObject.optionalString(key: String): String? = this[key]?.let {
    if (it is JsonNull) null else {
        require(it is JsonPrimitive && it.isString)
        it.content
    }
}

/** A JSON boolean only; strings, numbers and null are rejected instead of failing later. */
private fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.let {
    require(it is JsonPrimitive && !it.isString)
    it.booleanOrNull ?: throw IllegalArgumentException("Expected a boolean")
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) {
    respondText(buildJsonObject { put("code", code); put("message", "Request rejected") }.toString(),
        ContentType.Application.Json, status)
}

/** Shared public shape of authorized member name reads; never add private member fields here. */
private fun memberNameResponse(id: UUID, firstName: String, lastName: String): JsonObject =
    buildJsonObject {
        put("id", id.toString())
        put("firstName", firstName)
        put("lastName", lastName)
    }
