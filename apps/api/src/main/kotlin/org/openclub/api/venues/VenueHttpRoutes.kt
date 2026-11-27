package org.openclub.api.venues

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.guarded
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Fields and halls (OpenAPI 1.30.0, ADR-0008 addendum 34). Reads need an active membership (403); changes need
 * exact Origin and CSRF, then `MANAGE_VENUES` (403) before the body is read (400). Never cacheable.
 */
internal fun Application.installVenueHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/venues") {
            call.readSession(sessions, cookies) { actor ->
                val result = database.onDatabaseThread { db ->
                    val venues = Venues(db)
                    if (!venues.isMember(actor.accountId)) null else venues.list() to venues.canManage(actor.accountId)
                } ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("canManage", result.second)
                    put("items", buildJsonArray {
                        result.first.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", it.name) }) }
                    })
                })
            }
        }
        get("/api/v1/venues/{venueId}/bookings") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { Venues(it).isMember(actor.accountId) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val venue = call.venueId()
                val range = call.range()
                if (venue == null || range == null) return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                val bookings = database.onDatabaseThread { db ->
                    val venues = Venues(db)
                    if (!venues.exists(venue)) null else venues.bookings(venue, range.first, range.second)
                } ?: return@readSession call.refuse(HttpStatusCode.NotFound, "not_found")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        bookings.forEach { add(buildJsonObject {
                            put("eventId", it.eventId.toString()); put("teamName", it.teamName); put("kind", it.kind)
                            put("title", it.title); put("startsAt", it.startsAt.toString()); put("endsAt", it.endsAt.toString())
                        }) }
                    })
                })
            }
        }
        post("/api/v1/venues") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_VENUES) { actor ->
                val name = call.venueName() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { Venues(it).create(actor.accountId, name) }) {
                    is Venues.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    Venues.Created.Duplicate -> call.refuse(HttpStatusCode.Conflict, "duplicate")
                    Venues.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/venues/{venueId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_VENUES) { actor ->
                val venue = call.venueId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val name = call.venueName() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { Venues(it).rename(actor.accountId, venue, name) })
            }
        }
        delete("/api/v1/venues/{venueId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_VENUES) { actor ->
                val venue = call.venueId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { Venues(it).delete(actor.accountId, venue) })
            }
        }
    }
}

private fun ApplicationCall.venueId(): UUID? = runCatching { UUID.fromString(parameters["venueId"]) }.getOrNull()

/** A trimmed name of 1 to 100 characters without control characters. */
private suspend fun ApplicationCall.venueName(): String? = try {
    receiveJsonObject(setOf("name")).requiredString("name").trim()
        .takeIf { it.length in 1..100 && it.none(Character::isISOControl) }
} catch (_: IllegalArgumentException) {
    null
}

/** `from` (default now) and `to` (default 14 days later), RFC 3339; `to` after `from`, at most 62 days. */
private fun ApplicationCall.range(): Pair<OffsetDateTime, OffsetDateTime>? = try {
    val query = request.queryParameters
    require(query.names().all { it == "from" || it == "to" } && query.names().all { query.getAll(it)?.size == 1 })
    val from = query["from"]?.let(OffsetDateTime::parse) ?: OffsetDateTime.now()
    val to = query["to"]?.let(OffsetDateTime::parse) ?: from.plusDays(14)
    require(to.isAfter(from) && Duration.between(from, to) <= Duration.ofDays(62))
    from to to
} catch (_: IllegalArgumentException) {
    null
} catch (_: DateTimeParseException) {
    null
}

private suspend fun ApplicationCall.answer(change: Venues.Change) = when (change) {
    Venues.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    Venues.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    Venues.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    Venues.Change.DUPLICATE -> refuse(HttpStatusCode.Conflict, "duplicate")
}
