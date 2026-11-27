package org.openclub.api.join

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.guarded
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredBoolean
import org.openclub.api.requiredString
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

/**
 * Join requests (OpenAPI 1.22.0, ADR-0008 addendum 26).
 *
 * Public, without a session: `GET /api/v1/public/join-requests` (is the form open, which teams) and
 * `POST /api/v1/public/join-requests`. The POST needs the exact Origin (403), passes an in-memory limit of 5 per
 * client address and hour (429; the address is the last `X-Forwarded-For` entry set by the bundled Caddy, else the
 * connection, and is never stored), then the body (400). A filled honeypot field is answered 202 and dropped; so is
 * a person who already has an open request. A closed form or a full queue is 409.
 *
 * For `EDIT_MEMBERS` holders, with the usual session, Origin and CSRF checks: the switch
 * (`GET`/`PUT /api/v1/club/join-requests/settings`), the list (`GET /api/v1/join-requests`), accept
 * (`POST /api/v1/join-requests/{id}/accept`, 201 with the new member's ID) and decline (`DELETE`).
 */
internal fun Application.installJoinRequestHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    limiter: JoinRequestLimiter = JoinRequestLimiter(),
) {
    val strict = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    routing {
        get("/api/v1/public/join-requests") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val (open, teams) = database.onDatabaseThread { JoinRequests(it).publicForm() }
                call.respondText(buildJsonObject {
                    put("open", open)
                    put("teams", buildJsonArray { teams.forEach { (id, name) -> add(buildJsonObject { put("id", id.toString()); put("name", name) }) } })
                }.toString(), ContentType.Application.Json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        post("/api/v1/public/join-requests") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (call.request.headers[HttpHeaders.Origin] != origin) return@post call.refuse(HttpStatusCode.Forbidden, "forbidden")
            if (!limiter.allow(call.clientAddress())) return@post call.refuse(HttpStatusCode.TooManyRequests, "too_many_requests")
            val body: JsonObject = try {
                call.receiveJsonObject(
                    setOf("kind", "firstName", "lastName", "dateOfBirth", "email", "phone", "acceptedPrivacyNotice", "website"),
                    setOf("parentFirstName", "parentLastName", "teamId", "message"))
            } catch (_: IllegalArgumentException) {
                return@post call.refuse(HttpStatusCode.BadRequest, "invalid")
            }
            val application = try {
                require(body.requiredBoolean("acceptedPrivacyNotice"))
                val website = body.requiredString("website")
                if (website.isNotEmpty()) return@post call.respondText("", status = HttpStatusCode.Accepted)
                JoinRequests.validate(body.requiredString("kind"), body.requiredString("firstName"), body.requiredString("lastName"),
                    LocalDate.parse(body.requiredString("dateOfBirth"), strict), body.requiredString("email"), body.requiredString("phone"),
                    body.optionalString("parentFirstName"), body.optionalString("parentLastName"),
                    body.optionalString("teamId")?.let(UUID::fromString), body.optionalString("message"))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: DateTimeException) {
                null
            } ?: return@post call.refuse(HttpStatusCode.BadRequest, "invalid")
            try {
                when (database.onDatabaseThread { JoinRequests(it).submit(application) }) {
                    JoinRequests.Submitted.ACCEPTED -> call.respondText("", status = HttpStatusCode.Accepted)
                    JoinRequests.Submitted.CLOSED -> call.refuse(HttpStatusCode.Conflict, "closed")
                    JoinRequests.Submitted.FULL -> call.refuse(HttpStatusCode.Conflict, "full")
                    JoinRequests.Submitted.INVALID -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        get("/api/v1/club/join-requests/settings") {
            call.readSession(sessions, cookies) { actor ->
                val open = database.onDatabaseThread { JoinRequests(it).settings(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject { put("open", open) })
            }
        }
        put("/api/v1/club/join-requests/settings") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { JoinRequests(it).settings(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val open = try {
                    call.receiveJsonObject(setOf("open")).requiredBoolean("open")
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                if (!database.onDatabaseThread { JoinRequests(it).setOpen(actor.accountId, open) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
        get("/api/v1/join-requests") {
            call.readSession(sessions, cookies) { actor ->
                val requests = database.onDatabaseThread { JoinRequests(it).list(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        requests.forEach { request -> add(buildJsonObject {
                            val a = request.application
                            put("id", request.id.toString())
                            put("createdAt", request.createdAt.toString())
                            put("kind", a.kind.name)
                            put("firstName", a.firstName); put("lastName", a.lastName); put("dateOfBirth", a.dateOfBirth.toString())
                            put("email", a.email); put("phone", a.phone)
                            put("parentFirstName", a.parentFirstName?.let(::JsonPrimitive) ?: JsonNull)
                            put("parentLastName", a.parentLastName?.let(::JsonPrimitive) ?: JsonNull)
                            put("teamName", request.teamName?.let(::JsonPrimitive) ?: JsonNull)
                            put("message", a.message?.let(::JsonPrimitive) ?: JsonNull)
                        }) }
                    })
                })
            }
        }
        post("/api/v1/join-requests/{requestId}/accept") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { JoinRequests(it).settings(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val id = call.requestId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { JoinRequests(it).accept(actor.accountId, id) }) {
                    is JoinRequests.Accepted.Done -> call.respondText(buildJsonObject { put("memberId", result.memberId.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    JoinRequests.Accepted.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    JoinRequests.Accepted.Rejected -> call.refuse(HttpStatusCode.Conflict, "rejected")
                    JoinRequests.Accepted.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        delete("/api/v1/join-requests/{requestId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { JoinRequests(it).settings(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val id = call.requestId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (database.onDatabaseThread { JoinRequests(it).decline(actor.accountId, id) }) {
                    true -> call.respondText("", status = HttpStatusCode.NoContent)
                    false -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    null -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
    }
}

private fun ApplicationCall.requestId(): UUID? = runCatching { UUID.fromString(parameters["requestId"]) }.getOrNull()

/** The client address for the rate limit: the last X-Forwarded-For entry (set by the bundled Caddy), else the peer. */
internal fun ApplicationCall.clientAddress(): String =
    request.headers[HttpHeaders.XForwardedFor]?.split(',')?.lastOrNull()?.trim()?.takeIf(String::isNotEmpty)
        ?: request.origin.remoteHost

/** A field that is a JSON string or null (a blank string counts as null). */
private fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected a string")
    require(primitive.isString) { "Expected a string" }
    return primitive.content.trim().ifEmpty { null }
}
