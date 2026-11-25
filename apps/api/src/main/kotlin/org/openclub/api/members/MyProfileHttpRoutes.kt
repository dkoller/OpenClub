package org.openclub.api.members

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.rejectedTheData
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredBoolean
import java.util.UUID

/**
 * `GET /api/v1/me/profile` and `PUT /api/v1/me/profile/contacts` (OpenAPI 1.9.0). Order of answers: session (401);
 * for the change, exact Origin and CSRF (403); then the caller must be an active member with a verified identity
 * (403, the same answer as for every other refusal); then the body (400), `If-Match` (428, 412) and the club's
 * rules for adults' contacts (400). Responses are `no-store`.
 *
 * Also the public-list switch (OpenAPI 1.15.0, controller decision C5): `PUT /api/v1/me/profile/public-listing` for
 * the member's own name and `PUT /api/v1/members/{memberId}/public-listing` for an editor (`EDIT_MEMBERS`, for example
 * a minor's objection made through a guardian), both with `If-Match` on the member's version.
 */
internal fun Application.installMyProfileHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/me/profile") {
            call.readSession(sessions, cookies) { actor ->
                val profile = database.onDatabaseThread { MyProfile(it).read(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.response.headers.append(HttpHeaders.ETag, memberETag(profile.version))
                call.respondJson(buildJsonObject {
                    put("id", profile.id.toString())
                    put("firstName", profile.firstName)
                    put("lastName", profile.lastName)
                    put("dateOfBirth", profile.dateOfBirth.toString())
                    put("email", profile.email?.let(::JsonPrimitive) ?: JsonNull)
                    put("phone", profile.phone?.let(::JsonPrimitive) ?: JsonNull)
                    put("gender", profile.gender?.let(::JsonPrimitive) ?: JsonNull)
                    put("isPlayer", profile.isPlayer)
                    put("role", profile.role)
                    put("listedPublicly", profile.listedPublicly)
                    put("jsPersonNumber", profile.jsPersonNumber?.let(::JsonPrimitive) ?: JsonNull)
                    put("teams", buildJsonArray {
                        profile.teams.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) }
                    })
                })
            }
        }
        put("/api/v1/me/profile/contacts") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { MyProfile(it).read(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val (email, phone) = try {
                    val body = call.receiveJsonObject(required = emptySet(), optional = setOf("email", "phone"))
                    MemberFieldValidation.email(body.optionalText("email")) to MemberFieldValidation.phone(body.optionalText("phone"))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]
                    ?: return@guarded call.refuse(HttpStatusCode.PreconditionRequired, "precondition_required")
                val expected = parseMemberETag(ifMatch)
                    ?: return@guarded call.refuse(HttpStatusCode.PreconditionFailed, "changed")
                val result = try {
                    database.onDatabaseThread { MyProfile(it).replaceContacts(actor.accountId, email, phone, expected) }
                } catch (failure: DataAccessException) {
                    if (failure.rejectedTheData()) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                    throw failure
                }
                when (result) {
                    is MyProfile.Replaced.Updated -> {
                        call.response.headers.append(HttpHeaders.ETag, memberETag(result.version))
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                    MyProfile.Replaced.Changed -> call.refuse(HttpStatusCode.PreconditionFailed, "changed")
                    MyProfile.Replaced.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/me/profile/public-listing") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { MyProfile(it).read(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val listed = call.readListing() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val expected = call.expectedVersion() ?: return@guarded
                when (val result = database.onDatabaseThread { MyProfile(it).setListing(actor.accountId, listed, expected) }) {
                    is MyProfile.Replaced.Updated -> {
                        call.response.headers.append(HttpHeaders.ETag, memberETag(result.version))
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                    MyProfile.Replaced.Changed -> call.refuse(HttpStatusCode.PreconditionFailed, "changed")
                    MyProfile.Replaced.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/members/{memberId}/js-person-number") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val id = runCatching { UUID.fromString(call.parameters["memberId"]) }.getOrNull()
                    ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val number = try {
                    val body = call.receiveJsonObject(required = setOf("jsPersonNumber"))
                    when (val value = body["jsPersonNumber"]) {
                        is JsonNull -> null
                        is JsonPrimitive -> value.content.trim().takeIf { value.isString }
                            ?.also { require(Regex("[0-9]{1,12}").matches(it)) } ?: throw IllegalArgumentException("number")
                        else -> throw IllegalArgumentException("number")
                    }
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val expected = call.expectedVersion() ?: return@guarded
                when (val result = database.onDatabaseThread { MemberDetailsUpdate(it).setJsNumber(actor.accountId, id, number, expected) }) {
                    is MemberDetailsUpdate.Replaced.Updated -> {
                        call.response.headers.append(HttpHeaders.ETag, memberETag(result.version))
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                    MemberDetailsUpdate.Replaced.Changed -> call.refuse(HttpStatusCode.PreconditionFailed, "changed")
                    MemberDetailsUpdate.Replaced.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberDetailsUpdate.Replaced.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/members/{memberId}/public-listing") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val id = try {
                    UUID.fromString(call.parameters["memberId"])
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val listed = call.readListing() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val expected = call.expectedVersion() ?: return@guarded
                when (val result = database.onDatabaseThread {
                    MemberDetailsUpdate(it).setListing(actor.accountId, id, listed, expected)
                }) {
                    is MemberDetailsUpdate.Replaced.Updated -> {
                        call.response.headers.append(HttpHeaders.ETag, memberETag(result.version))
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                    MemberDetailsUpdate.Replaced.Changed -> call.refuse(HttpStatusCode.PreconditionFailed, "changed")
                    MemberDetailsUpdate.Replaced.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberDetailsUpdate.Replaced.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.readListing(): Boolean? = try {
    receiveJsonObject(required = setOf("listedPublicly")).requiredBoolean("listedPublicly")
} catch (_: IllegalArgumentException) {
    null
}

/** `If-Match` with the member's version: missing is 428, anything but a well-formed version is 412. */
private suspend fun io.ktor.server.application.ApplicationCall.expectedVersion(): Long? {
    val ifMatch = request.headers[HttpHeaders.IfMatch]
        ?: return null.also { refuse(HttpStatusCode.PreconditionRequired, "precondition_required") }
    return parseMemberETag(ifMatch) ?: null.also { refuse(HttpStatusCode.PreconditionFailed, "changed") }
}
