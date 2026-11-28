package org.openclub.api.teams

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.guarded
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import org.jooq.impl.DSL
import org.openclub.api.notifications.Notifier
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Carpooling (OpenAPI 1.21.0, ADR-0008 addendum 25). Order of answers: session (401); for changes exact Origin and
 * CSRF (403); an active membership (403); the IDs and body (400); then 403 for a ride or event the caller may not
 * see or change (404 for unknown ones only to club-wide team holders), 409 `exists` for a second offer on one event,
 * 409 `closed` after departure or for a cancelled event, 409 `full` and 409 `conflict` for fewer seats than bookings.
 * Phones only between a driver and the people they carry. Responses are `no-store`. Ride requests (1.41.0) follow the
 * booking rules: one 403 for a person or event the caller may not ask for, 409 `closed` once the event started or is
 * cancelled.
 */
internal fun Application.installRideHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    notifier: Notifier = Notifier.DISABLED,
) {
    routing {
        get("/api/v1/events/{eventId}/rides") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.rideId("eventId") ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { Rides(it).list(actor.accountId, event) }) {
                    is Rides.Listing.Found -> call.respondJson(buildJsonObject {
                        put("items", buildJsonArray {
                            result.rides.forEach { ride -> add(buildJsonObject {
                                put("id", ride.id.toString())
                                put("driver", ride.driver)
                                put("mine", ride.mine)
                                put("direction", ride.direction.name)
                                put("seats", ride.seats)
                                put("meetingPoint", ride.meetingPoint)
                                put("departsAt", ride.departsAt.toString())
                                put("driverPhone", ride.driverPhone?.let(::JsonPrimitive) ?: JsonNull)
                                put("passengers", buildJsonArray {
                                    ride.passengers.forEach { add(buildJsonObject {
                                        put("displayName", it.displayName)
                                        put("phone", it.phone?.let(::JsonPrimitive) ?: JsonNull)
                                    }) }
                                })
                                put("booked", buildJsonArray { ride.booked.forEach { add(JsonPrimitive(it.toString())) } })
                            }) }
                        })
                        put("people", buildJsonArray {
                            result.people.forEach { add(buildJsonObject { put("id", it.id.toString()); put("firstName", it.firstName) }) }
                        })
                        put("canOffer", result.canOffer)
                        put("requests", buildJsonArray {
                            result.requests.forEach { add(buildJsonObject {
                                put("displayName", it.displayName); put("direction", it.direction.name)
                                put("memberId", it.memberId?.let { id -> JsonPrimitive(id.toString()) } ?: JsonNull)
                            }) }
                        })
                    })
                    Rides.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    Rides.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        post("/api/v1/events/{eventId}/rides") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.rideId("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val input = call.rideInput() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { Rides(it).create(actor.accountId, event, input) }) {
                    is Rides.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    Rides.Created.Invalid -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    Rides.Created.Exists -> call.refuse(HttpStatusCode.Conflict, "exists")
                    Rides.Created.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    Rides.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/rides/{rideId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val ride = call.rideId("rideId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val input = call.rideInput() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.rideChange(database.onDatabaseThread { Rides(it).replace(actor.accountId, ride, input) })
            }
        }
        delete("/api/v1/rides/{rideId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val ride = call.rideId("rideId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.rideChange(database.onDatabaseThread { Rides(it).delete(actor.accountId, ride) })
            }
        }
        put("/api/v1/events/{eventId}/ride-requests/{memberId}") { call.rideRequest(sessions, cookies, origin, database, notifier, withdraw = false) }
        delete("/api/v1/events/{eventId}/ride-requests/{memberId}") { call.rideRequest(sessions, cookies, origin, database, notifier, withdraw = true) }
        put("/api/v1/rides/{rideId}/passengers/{memberId}") { call.passenger(sessions, cookies, origin, database, cancel = false) }
        delete("/api/v1/rides/{rideId}/passengers/{memberId}") { call.passenger(sessions, cookies, origin, database, cancel = true) }
    }
}

private fun ApplicationCall.rideId(name: String): UUID? = runCatching { UUID.fromString(parameters[name]) }.getOrNull()

/** Direction THERE/BACK/BOTH, 1–8 seats, a meeting point of 1–200 characters, departure RFC 3339 with offset. */
private suspend fun ApplicationCall.rideInput(): Rides.Input? = try {
    val body = receiveJsonObject(setOf("direction", "seats", "meetingPoint", "departsAt"))
    val direction = Rides.Direction.valueOf(body.requiredString("direction"))
    val seats = body["seats"] as? JsonPrimitive
    require(seats != null && !seats.isString) { "seats" }
    val count = requireNotNull(seats.content.toIntOrNull()) { "seats" }
    require(count in 1..8) { "seats" }
    val meetingPoint = body.requiredString("meetingPoint").trim()
    require(meetingPoint.length in 1..200 && meetingPoint.none(Character::isISOControl)) { "meetingPoint" }
    Rides.Input(direction, count, meetingPoint, OffsetDateTime.parse(body.requiredString("departsAt")))
} catch (_: IllegalArgumentException) {
    null
} catch (_: DateTimeException) {
    null
}

private suspend fun ApplicationCall.rideChange(change: Rides.Change) = when (change) {
    Rides.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    Rides.Change.INVALID -> refuse(HttpStatusCode.BadRequest, "invalid")
    Rides.Change.CONFLICT -> refuse(HttpStatusCode.Conflict, "conflict")
    Rides.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    Rides.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}

/** Book (PUT) or cancel (DELETE) a seat for oneself or one's minor: one 403 for everything the caller may not do. */
private suspend fun ApplicationCall.passenger(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime, cancel: Boolean,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    val ride = rideId("rideId")
    val member = rideId("memberId")
    if (ride == null || member == null) return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    val result = database.onDatabaseThread {
        if (cancel) Rides(it).cancelBooking(actor.accountId, ride, member) else Rides(it).book(actor.accountId, ride, member)
    }
    when (result) {
        Rides.Booking.DONE -> respondText("", status = HttpStatusCode.NoContent)
        Rides.Booking.CLOSED -> refuse(HttpStatusCode.Conflict, "closed")
        Rides.Booking.FULL -> refuse(HttpStatusCode.Conflict, "full")
        Rides.Booking.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}

/**
 * Ask for a ride (PUT, body `direction`) or withdraw the request (DELETE) for oneself or one's minor (1.41.0). The
 * first request of an event tells its drivers, without a name.
 */
private suspend fun ApplicationCall.rideRequest(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime, notifier: Notifier,
    withdraw: Boolean,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    val event = rideId("eventId")
    val member = rideId("memberId")
    if (event == null || member == null) return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    val direction = if (withdraw) null else try {
        Rides.Direction.valueOf(receiveJsonObject(setOf("direction")).requiredString("direction"))
    } catch (_: IllegalArgumentException) {
        return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    }
    val result = database.onDatabaseThread { db ->
        if (direction == null) Rides(db).withdrawRequest(actor.accountId, event, member)
        else db.transactionResult { c ->
            val tx = DSL.using(c)
            val requested = Rides(tx).request(actor.accountId, event, member, direction)
            if (requested.first) notifier.rideRequested(tx, event, member)
            requested.change
        }
    }
    when (result) {
        Rides.Booking.DONE -> respondText("", status = HttpStatusCode.NoContent)
        Rides.Booking.CLOSED -> refuse(HttpStatusCode.Conflict, "closed")
        Rides.Booking.FULL, Rides.Booking.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}
