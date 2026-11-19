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

/**
 * `GET /api/v1/me/profile` and `PUT /api/v1/me/profile/contacts` (OpenAPI 1.9.0). Order of answers: session (401);
 * for the change, exact Origin and CSRF (403); then the caller must be an active member with a verified identity
 * (403, the same answer as for every other refusal); then the body (400), `If-Match` (428, 412) and the club's
 * rules for adults' contacts (400). Responses are `no-store`.
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
    }
}
