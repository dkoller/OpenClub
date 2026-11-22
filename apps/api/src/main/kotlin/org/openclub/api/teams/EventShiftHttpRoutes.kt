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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.guarded
import org.openclub.api.members.readSession
import org.openclub.api.members.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.time.DateTimeException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Volunteer shifts (OpenAPI 1.16.0, ADR-0008 addendum 20; the unfilled overview since 1.23.0, addendum 27). Order of answers: session (401); for changes exact Origin
 * and CSRF (403); an active membership (403); the IDs and body (400); then the event or shift itself: 403 for one the
 * caller may not see or change (404 for an unknown one only to club-wide team holders), 409 `closed` once a shift has
 * started or its event is cancelled, 409 `full` when no place is free and 409 `conflict` when a change would leave
 * fewer places than volunteers. Responses are `no-store`; times are RFC 3339, answered in UTC.
 */
internal fun Application.installEventShiftHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        // Managers' overview (1.23.0): shifts of the next two weeks that still need people, for the teams they manage.
        get("/api/v1/shifts/unfilled") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val result = database.onDatabaseThread { EventShifts(it).unfilled(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        result.items.forEach { shift -> add(buildJsonObject {
                            put("id", shift.id.toString())
                            put("title", shift.title)
                            put("startsAt", shift.startsAt.toString())
                            put("endsAt", shift.endsAt.toString())
                            put("needed", shift.needed)
                            put("filled", shift.filled)
                            put("eventId", shift.eventId.toString())
                            put("eventTitle", shift.eventTitle)
                            put("teamId", shift.teamId.toString())
                            put("teamName", shift.teamName)
                        }) }
                    })
                    put("truncated", result.truncated)
                })
            }
        }
        get("/api/v1/events/{eventId}/shifts") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.id("eventId") ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { EventShifts(it).list(actor.accountId, event) }) {
                    is EventShifts.Listing.Found -> call.respondJson(buildJsonObject {
                        put("items", buildJsonArray {
                            result.shifts.forEach { shift -> add(buildJsonObject {
                                put("id", shift.id.toString())
                                put("title", shift.title)
                                put("startsAt", shift.startsAt.toString())
                                put("endsAt", shift.endsAt.toString())
                                put("needed", shift.needed)
                                put("volunteers", buildJsonArray { shift.volunteers.forEach { add(JsonPrimitive(it)) } })
                                put("signedUp", buildJsonArray { shift.signedUp.forEach { add(JsonPrimitive(it.toString())) } })
                            }) }
                        })
                        put("people", buildJsonArray {
                            result.people.forEach { add(buildJsonObject { put("id", it.id.toString()); put("firstName", it.firstName) }) }
                        })
                        put("canManage", result.canManage)
                    })
                    EventShifts.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventShifts.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        post("/api/v1/events/{eventId}/shifts") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.id("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val input = call.shiftInput() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { EventShifts(it).create(actor.accountId, event, input) }) {
                    is EventShifts.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    EventShifts.Created.Invalid -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    EventShifts.Created.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventShifts.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/shifts/{shiftId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val shift = call.id("shiftId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val input = call.shiftInput() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { EventShifts(it).replace(actor.accountId, shift, input) })
            }
        }
        delete("/api/v1/shifts/{shiftId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val shift = call.id("shiftId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { EventShifts(it).delete(actor.accountId, shift) })
            }
        }
        put("/api/v1/shifts/{shiftId}/volunteers/{memberId}") {
            call.volunteer(sessions, cookies, origin, database, withdraw = false)
        }
        delete("/api/v1/shifts/{shiftId}/volunteers/{memberId}") {
            call.volunteer(sessions, cookies, origin, database, withdraw = true)
        }
    }
}

private fun ApplicationCall.id(name: String): UUID? = runCatching { UUID.fromString(parameters[name]) }.getOrNull()

private val EARLIEST = OffsetDateTime.parse("2000-01-01T00:00:00Z")
private val LATEST = OffsetDateTime.parse("2100-01-01T00:00:00Z")

/** Title 1–200 characters (trimmed, no control characters), start and end RFC 3339 with offset, at most 24 hours, 1–100 places. */
private suspend fun ApplicationCall.shiftInput(): EventShifts.Input? = try {
    val body: JsonObject = receiveJsonObject(setOf("title", "startsAt", "endsAt", "needed"))
    val title = body.requiredString("title").trim()
    require(title.length in 1..200 && title.none(Character::isISOControl)) { "title" }
    val startsAt = OffsetDateTime.parse(body.requiredString("startsAt"))
    val endsAt = OffsetDateTime.parse(body.requiredString("endsAt"))
    require(!startsAt.isBefore(EARLIEST) && startsAt.isBefore(LATEST)) { "start" }
    require(endsAt.isAfter(startsAt) && Duration.between(startsAt, endsAt) <= Duration.ofHours(24)) { "end" }
    val needed = body["needed"] as? JsonPrimitive
    require(needed != null && !needed.isString) { "needed" }
    val places = requireNotNull(needed.content.toIntOrNull()) { "needed" }
    require(places in 1..100) { "needed" }
    EventShifts.Input(title, startsAt, endsAt, places)
} catch (_: IllegalArgumentException) {
    null
} catch (_: DateTimeException) {
    null
}

private suspend fun ApplicationCall.answer(change: EventShifts.Change) = when (change) {
    EventShifts.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    EventShifts.Change.INVALID -> refuse(HttpStatusCode.BadRequest, "invalid")
    EventShifts.Change.CONFLICT -> refuse(HttpStatusCode.Conflict, "conflict")
    EventShifts.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    EventShifts.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}

/** Sign up (PUT) or withdraw (DELETE) one member: one 403 for every shift or member the caller may not act for. */
private suspend fun ApplicationCall.volunteer(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime, withdraw: Boolean,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    val shift = id("shiftId")
    val member = id("memberId")
    if (shift == null || member == null) return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    val result = database.onDatabaseThread {
        if (withdraw) EventShifts(it).withdraw(actor.accountId, shift, member)
        else EventShifts(it).signUp(actor.accountId, shift, member)
    }
    when (result) {
        EventShifts.SignUp.DONE -> respondText("", status = HttpStatusCode.NoContent)
        EventShifts.SignUp.CLOSED -> refuse(HttpStatusCode.Conflict, "closed")
        EventShifts.SignUp.FULL -> refuse(HttpStatusCode.Conflict, "full")
        EventShifts.SignUp.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}
