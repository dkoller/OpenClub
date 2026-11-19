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
import io.ktor.server.routing.put
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
                call.memberPage(sessions, cookies, database, archived = false)
            }
            // Archived members for editors (OpenAPI 1.5.0, owner-approved 2026-11-19, ADR-0008 addendum 7).
            // A literal segment, so Ktor prefers it to the /{memberId} parameter route below.
            get("/archived") {
                call.memberPage(sessions, cookies, database, archived = true)
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
            // Full replacement (OpenAPI 1.4.0, owner-approved 2026-11-19). Every editable field is sent: an omitted
            // contact or gender clears it, so the browser must first read the current values (GET .../details).
            put("/{memberId}") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                try {
                    val actor = call.requireActor(sessions, cookies) ?: return@put
                    if (!call.requireWrite(actor, origin)) return@put
                    if (!call.requireCapability(database, actor, ClubCapabilityAccess.Capability.EDIT_MEMBERS)) {
                        return@put
                    }
                    val (id, input) = try {
                        UUID.fromString(call.parameters["memberId"]) to call.readNewMember()
                    } catch (_: IllegalArgumentException) {
                        call.error(HttpStatusCode.BadRequest, "invalid")
                        return@put
                    }
                    // OpenAPI 1.8.0: the version from the editor's detail read. Missing is 428; anything that is not
                    // exactly the current version, including a malformed value or `*`, is 412 and changes nothing.
                    val ifMatch = call.request.headers[HttpHeaders.IfMatch]
                        ?: return@put call.error(HttpStatusCode.PreconditionRequired, "precondition_required")
                    val expected = parseMemberETag(ifMatch)
                        ?: return@put call.error(HttpStatusCode.PreconditionFailed, "changed")
                    val result = database.onDatabaseThread { db ->
                        if (!ClubCapabilityAccess(db).has(actor.accountId,
                                ClubCapabilityAccess.Capability.EDIT_MEMBERS)) null
                        else MemberDetailsUpdate(db).update(actor.accountId, id, MemberDetailsUpdate.Input(
                            input.firstName, input.lastName, input.dateOfBirth,
                            input.email, input.phone, input.gender, input.isPlayer), expected)
                    }
                    when (result) {
                        null, MemberDetailsUpdate.Replaced.Denied -> call.error(HttpStatusCode.Forbidden, "forbidden")
                        MemberDetailsUpdate.Replaced.NotFound -> call.error(HttpStatusCode.NotFound, "not_found")
                        MemberDetailsUpdate.Replaced.Changed -> call.error(HttpStatusCode.PreconditionFailed, "changed")
                        is MemberDetailsUpdate.Replaced.Updated -> {
                            call.response.headers.append(HttpHeaders.ETag, memberETag(result.version))
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DataAccessException) {
                    // Same mapping as creation: the database rules (adult contacts, player gender) are authoritative.
                    if (failure.rejectedTheData()) call.error(HttpStatusCode.BadRequest, "invalid")
                    else call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                } catch (_: Exception) {
                    call.error(HttpStatusCode.ServiceUnavailable, "unavailable")
                }
            }
            // Reactivation (OpenAPI 1.4.0, owner-approved 2026-11-19): restores only active status, never grants,
            // teams or a login link. 404 for an unknown or active member; 409 when the archived record no longer
            // satisfies the club's current policy (for example an adult without email or phone).
            post("/{memberId}/reactivate") {
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
                    val result = try {
                        database.onDatabaseThread { db ->
                            val archived = db.fetchOne("SELECT 1 FROM members WHERE id = ? AND archived_at IS NOT NULL", id) != null
                            if (!archived) "not_found"
                            else if (MemberReactivation(db).reactivate(actor.accountId, id)) "ok"
                            else "forbidden"
                        }
                    } catch (failure: DataAccessException) {
                        if (!failure.rejectedTheData()) throw failure
                        "conflict"
                    }
                    when (result) {
                        "ok" -> call.respond(HttpStatusCode.NoContent)
                        "not_found" -> call.error(HttpStatusCode.NotFound, "not_found")
                        "conflict" -> call.error(HttpStatusCode.Conflict, "conflict")
                        else -> call.error(HttpStatusCode.Forbidden, "forbidden")
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

/**
 * One page of member names: active members for VIEW_MEMBER_DIRECTORY, or archived members (with their
 * archive time) for EDIT_MEMBERS. Authorization comes before validation, so a caller without the grant
 * always gets 403. Cursors are scoped to the actor, the query and the list, so a token from one list is
 * rejected by the other.
 */
private suspend fun ApplicationCall.memberPage(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
    archived: Boolean,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val capability = if (archived) ClubCapabilityAccess.Capability.EDIT_MEMBERS
        else ClubCapabilityAccess.Capability.VIEW_MEMBER_DIRECTORY
    try {
        val actor = requireActor(sessions, cookies) ?: return
        if (!requireCapability(database, actor, capability)) return
        val (q, limit, after) = try {
            val q = request.queryParameters["q"] ?: ""
            require(q.trim().length <= 100 && (q.isEmpty() || q.isNotBlank()))
            val limit = request.queryParameters["limit"]?.toIntOrNull() ?: run {
                require(request.queryParameters["limit"] == null)
                20
            }
            require(limit in 1..50)
            val after = request.queryParameters["cursor"]?.let {
                MemberPageCursor.decode(actor.accountId, cursorScope(q, archived), it)
            }
            MemberQuery(q, limit, after)
        } catch (_: IllegalArgumentException) {
            error(HttpStatusCode.BadRequest, "invalid")
            return
        }
        val page = database.onDatabaseThread { db ->
            if (!ClubCapabilityAccess(db).has(actor.accountId, capability)) null
            else if (archived) MemberDirectory(db).searchArchived(actor.accountId, q, limit, after)
            else MemberDirectory(db).search(actor.accountId, q, limit, after)
        }
        if (page == null) {
            error(HttpStatusCode.Forbidden, "forbidden")
            return
        }
        val body = buildJsonObject {
            put("items", buildJsonArray {
                page.members.forEach { member ->
                    val names = memberNameResponse(member.id, member.firstName, member.lastName)
                    add(if (!archived) names else JsonObject(names + ("archivedAt" to
                        JsonPrimitive(requireNotNull(member.archivedAt).toInstant().toString()))))
                }
            })
            page.next?.let { put("nextCursor", MemberPageCursor.encode(actor.accountId, cursorScope(q, archived), it)) }
        }
        respondText(body.toString(), ContentType.Application.Json)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        error(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private fun cursorScope(q: String, archived: Boolean) = if (archived) "archived\u0000" + q.trim() else q.trim()

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
