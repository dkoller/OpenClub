package org.openclub.api.calendar

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.guarded
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.respondJson
import org.openclub.api.logFailure

/**
 * Calendar feeds (OpenAPI 1.17.0, ADR-0008 addendum 21). The member's own feed is managed with a session
 * (`/api/v1/me/calendar-feed`: read 401/403; create and revoke also exact Origin and CSRF). The feed itself
 * (`/api/v1/calendar/{secret}`) needs no session: the secret in the path is the credential. An unknown, malformed or
 * revoked secret, or one whose member may no longer sign in, is the same 404. Nothing here logs the path or secret.
 */
internal fun Application.installCalendarHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/me/calendar-feed") {
            call.readSession(sessions, cookies) { actor ->
                val status = database.onDatabaseThread { CalendarFeeds(it).status(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("active", status.active)
                    put("createdAt", status.createdAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                })
            }
        }
        post("/api/v1/me/calendar-feed") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                val secret = database.onDatabaseThread { CalendarFeeds(it).create(actor.accountId) }
                    ?: return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondText(buildJsonObject { put("url", "$origin/api/v1/calendar/$secret") }.toString(),
                    ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        delete("/api/v1/me/calendar-feed") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { CalendarFeeds(it).revoke(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
        get("/api/v1/calendar/{secret}") {
            call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Robots-Tag", "noindex")
            try {
                val secret = call.parameters["secret"].orEmpty()
                val text = database.onDatabaseThread { CalendarFeeds(it).feed(secret) }
                    ?: return@get call.refuse(HttpStatusCode.NotFound, "not_found")
                call.respondText(text, ContentType("text", "calendar").withCharset(Charsets.UTF_8))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}
