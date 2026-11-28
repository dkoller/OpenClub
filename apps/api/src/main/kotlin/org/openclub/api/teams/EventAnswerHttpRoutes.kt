package org.openclub.api.teams

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.jooq.impl.DSL
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.notifications.Notifier
import org.openclub.api.guarded
import org.openclub.api.members.isCrossSiteRequest
import org.openclub.api.members.optionalText
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredBoolean
import org.openclub.api.requiredString
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeParseException
import java.util.UUID

/** 500 member IDs as JSON. */
private const val PRESENCE_BODY = 24 * 1024
private val EARLIEST = OffsetDateTime.parse("2000-01-01T00:00:00Z")
private val LATEST = OffsetDateTime.parse("2100-01-01T00:00:00Z")

/**
 * What happens around one event (split from TeamEventHttpRoutes.kt on 2026-11-28): answers and their reminders
 * (1.11.0, 1.25.0, notes and deadline 1.39.0), statistics, presence and the J+S list (1.34.0), call-ups (1.35.0) and
 * coach messages. The same order of answers as TeamEventHttpRoutes.kt; responses are `no-store`.
 */
internal fun Application.installEventAnswerHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    notifier: Notifier = Notifier.DISABLED,
) {
    routing {
        get("/api/v1/events/{eventId}/attendance") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread {
                        TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { EventAttendance(it).list(actor.accountId, event) }) {
                    is EventAttendance.Listing.Found -> call.respondJson(buildJsonObject {
                        put("items", buildJsonArray {
                            result.answers.forEach { add(buildJsonObject {
                                put("displayName", it.displayName)
                                put("response", it.response?.let { response -> JsonPrimitive(response.name) } ?: JsonNull)
                                put("note", it.note?.let(::JsonPrimitive) ?: JsonNull)
                            }) }
                        })
                        put("counts", result.counts.json())
                    })
                    EventAttendance.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventAttendance.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        get("/api/v1/teams/{teamId}/attendance-statistics") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread {
                        TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val team = call.uuid("teamId")
                val dates = call.dateRange()
                if (team == null || dates == null) return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (from, to) = dates
                when (val result = database.onDatabaseThread { EventAttendance(it).statistics(actor.accountId, team, from, to) }) {
                    is EventAttendance.Statistics.Found -> call.respondJson(buildJsonObject {
                        put("from", from.toString())
                        put("to", to.toString())
                        put("held", result.held)
                        put("items", buildJsonArray {
                            result.rows.forEach { add(buildJsonObject {
                                put("displayName", it.displayName)
                                put("yes", it.yes); put("no", it.no); put("maybe", it.maybe); put("noAnswer", it.noAnswer)
                            }) }
                        })
                        put("totals", buildJsonObject {
                            put("yes", result.rows.sumOf { it.yes }); put("no", result.rows.sumOf { it.no })
                            put("maybe", result.rows.sumOf { it.maybe }); put("noAnswer", result.rows.sumOf { it.noAnswer })
                        })
                    })
                    EventAttendance.Statistics.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventAttendance.Statistics.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        // A coach reminds everyone who has not answered (1.25.0): 200 with the count, 409 `closed` once the event
        // started or was cancelled, `email_off` without a mail configuration, `recent` within 12 hours of the last one.
        post("/api/v1/events/{eventId}/attendance-reminders") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { AnswerReminders(it, notifier).remind(actor.accountId, event) }) {
                    is AnswerReminders.Outcome.Done -> call.respondJson(buildJsonObject { put("reminded", result.reminded) })
                    AnswerReminders.Outcome.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    AnswerReminders.Outcome.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    AnswerReminders.Outcome.Closed -> call.refuse(HttpStatusCode.Conflict, "closed")
                    AnswerReminders.Outcome.EmailOff -> call.refuse(HttpStatusCode.Conflict, "email_off")
                    AnswerReminders.Outcome.Recent -> call.refuse(HttpStatusCode.Conflict, "recent")
                }
            }
        }
        // Actual attendance (1.27.0): who came, recorded by a coach once the event has started.
        get("/api/v1/events/{eventId}/presence") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { EventPresence(it).read(actor.accountId, event) }) {
                    is EventPresence.Listing.Found -> call.respondJson(buildJsonObject {
                        put("open", result.open)
                        put("items", buildJsonArray {
                            result.people.forEach { add(buildJsonObject {
                                put("memberId", it.memberId.toString())
                                put("displayName", it.displayName)
                                put("role", it.role.name)
                                put("response", it.response?.let(::JsonPrimitive) ?: JsonNull)
                                put("present", it.present)
                            }) }
                        })
                    })
                    EventPresence.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventPresence.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/events/{eventId}/presence") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val present = try {
                    call.receiveJsonObject(setOf("present"), maxBytes = PRESENCE_BODY).uuidSet("present", 500)
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (database.onDatabaseThread { EventPresence(it).replace(actor.accountId, event, present) }) {
                    EventPresence.Change.DONE -> call.respondText("", status = HttpStatusCode.NoContent)
                    EventPresence.Change.NOT_FOUND -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventPresence.Change.DENIED -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    EventPresence.Change.CLOSED -> call.refuse(HttpStatusCode.Conflict, "not_started")
                    EventPresence.Change.INVALID -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
            }
        }
        // Match squads (1.28.0): the team's managers call up members for a match until it starts.
        get("/api/v1/events/{eventId}/squad") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { EventSquads(it).read(actor.accountId, event) }) {
                    is EventSquads.Listing.Found -> call.respondJson(buildJsonObject {
                        put("open", result.open)
                        put("meetAt", result.meetAt?.let { JsonPrimitive(it.withOffsetSameInstant(ZoneOffset.UTC).toString()) } ?: JsonNull)
                        put("meetingPoint", result.meetingPoint?.let(::JsonPrimitive) ?: JsonNull)
                        put("items", buildJsonArray {
                            result.people.forEach { add(buildJsonObject {
                                put("memberId", it.memberId.toString())
                                put("displayName", it.displayName)
                                put("response", it.response?.let(::JsonPrimitive) ?: JsonNull)
                                put("called", it.called)
                            }) }
                        })
                    })
                    EventSquads.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventSquads.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    EventSquads.Listing.NotMatch -> call.refuse(HttpStatusCode.Conflict, "not_match")
                }
            }
        }
        put("/api/v1/events/{eventId}/squad") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val input = try {
                    val body = call.receiveJsonObject(setOf("memberIds", "meetAt", "meetingPoint"), maxBytes = PRESENCE_BODY)
                    val meetAt = when (val value = body["meetAt"]) {
                        is JsonNull -> null
                        is JsonPrimitive -> if (value.isString) OffsetDateTime.parse(value.content).also {
                            require(!it.isBefore(EARLIEST) && it.isBefore(LATEST))
                        } else throw IllegalArgumentException("meetAt")
                        else -> throw IllegalArgumentException("meetAt")
                    }
                    val point = when (val value = body["meetingPoint"]) {
                        is JsonNull -> null
                        is JsonPrimitive -> if (value.isString) text(value.content, 200) else throw IllegalArgumentException("point")
                        else -> throw IllegalArgumentException("point")
                    }
                    EventSquads.Input(body.uuidSet("memberIds", 500), meetAt, point)
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (database.onDatabaseThread { EventSquads(it, notifier).replace(actor.accountId, event, input) }) {
                    EventSquads.Change.DONE -> call.respondText("", status = HttpStatusCode.NoContent)
                    EventSquads.Change.NOT_FOUND -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    EventSquads.Change.DENIED -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    EventSquads.Change.NOT_MATCH -> call.refuse(HttpStatusCode.Conflict, "not_match")
                    EventSquads.Change.CLOSED -> call.refuse(HttpStatusCode.Conflict, "closed")
                    EventSquads.Change.INVALID -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
            }
        }
        // Coach messages (1.31.0): an email to part of the event's people, sent by the server, 10 per team per day.
        post("/api/v1/events/{eventId}/messages") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (audience, subject, body) = try {
                    val json = call.receiveJsonObject(setOf("audience", "subject", "body"), maxBytes = 32 * 1024)
                    val audience = CoachMessages.Audience.entries.firstOrNull { it.name == json.requiredString("audience") }
                        ?: throw IllegalArgumentException("audience")
                    Triple(audience, requireNotNull(text(json.requiredString("subject"), 150)) { "subject" },
                        requireNotNull(text(json.requiredString("body"), 5000, multiline = true)) { "body" })
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (val result = database.onDatabaseThread { CoachMessages(it, notifier).send(actor.accountId, event, audience, subject, body) }) {
                    is CoachMessages.Outcome.Sent -> call.respondJson(buildJsonObject { put("members", result.members) })
                    CoachMessages.Outcome.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    CoachMessages.Outcome.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    CoachMessages.Outcome.EmailOff -> call.refuse(HttpStatusCode.Conflict, "email_off")
                    CoachMessages.Outcome.Limit -> call.refuse(HttpStatusCode.Conflict, "limit")
                    CoachMessages.Outcome.NotMatch -> call.refuse(HttpStatusCode.Conflict, "not_match")
                    CoachMessages.Outcome.Closed -> call.refuse(HttpStatusCode.Conflict, "closed")
                }
            }
        }
        get("/api/v1/teams/{teamId}/js-attendance") {
            call.jsAttendance(sessions, cookies, database, file = false)
        }
        get("/api/v1/teams/{teamId}/js-attendance.csv") {
            call.jsAttendance(sessions, cookies, database, file = true)
        }
        put("/api/v1/events/{eventId}/attendance/{memberId}") {
            call.attendance(sessions, cookies, origin, database, clear = false)
        }
        delete("/api/v1/events/{eventId}/attendance/{memberId}") {
            call.attendance(sessions, cookies, origin, database, clear = true)
        }
    }
}

/**
 * Answer (PUT) or clear (DELETE) one member's attendance. Session (401), Origin and CSRF (403), an active membership
 * (403), the IDs and body (400); then one 403 for every event or member the actor may not answer for, and 409 once
 * the event has started or is cancelled.
 */
private suspend fun ApplicationCall.attendance(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime, clear: Boolean,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    val event = uuid("eventId")
    val member = uuid("memberId")
    if (event == null || member == null) return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    // Since 1.39.0 an optional `note` (at most 100 characters): left out keeps the stored note, null or blank removes it.
    var setNote = false
    var note: String? = null
    val response = if (clear) null else try {
        val body = receiveJsonObject(setOf("response"), setOf("note"))
        if ("note" in body) { setNote = true; note = text(body.optionalText("note"), NOTE_LENGTH) }
        val value = body.requiredString("response")
        EventAttendance.Response.entries.firstOrNull { it.name == value } ?: throw IllegalArgumentException("response")
    } catch (_: IllegalArgumentException) {
        return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    }
    val change = database.onDatabaseThread {
        val attendance = EventAttendance(it)
        if (response == null) attendance.clear(actor.accountId, event, member)
        else attendance.answer(actor.accountId, event, member, response, setNote, note)
    }
    when (change) {
        EventAttendance.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
        EventAttendance.Change.CLOSED -> refuse(HttpStatusCode.Conflict, "conflict")
        EventAttendance.Change.DEADLINE -> refuse(HttpStatusCode.Conflict, "answers_closed")
        EventAttendance.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}

/** A JSON array of distinct member IDs, at most [max]. */
internal fun JsonObject.uuidSet(name: String, max: Int): Set<UUID> {
    val array = this[name] as? kotlinx.serialization.json.JsonArray ?: throw IllegalArgumentException(name)
    require(array.size <= max)
    val ids = array.map {
        val value = it as? JsonPrimitive
        require(value != null && value.isString)
        UUID.fromString(value.content)
    }
    require(ids.toSet().size == ids.size)
    return ids.toSet()
}

/**
 * The J+S check (JSON) or the NDS import file (CSV, audited) for a team's dates `from`..`to` (ISO dates, Swiss time,
 * defaults as for the statistics). Session (401), cross-site request for the file (403), `MANAGE_TEAMS` somewhere
 * (403), input (400), then the team (403/404).
 */
private suspend fun ApplicationCall.jsAttendance(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, database: DatabaseRuntime, file: Boolean,
) = readSession(sessions, cookies) { actor ->
    if (file && isCrossSiteRequest()) return@readSession refuse(HttpStatusCode.Forbidden, "forbidden")
    if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
        return@readSession refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    val team = uuid("teamId")
    val dates = dateRange()
    if (team == null || dates == null) return@readSession refuse(HttpStatusCode.BadRequest, "invalid")
    val (from, to) = dates
    when (val result = database.onDatabaseThread { EventPresence(it).export(actor.accountId, team, from, to, audit = file) }) {
        is EventPresence.Export.Found -> if (file) {
            response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, "js-attendance-$from-$to.csv").toString())
            response.header("X-Content-Type-Options", "nosniff")
            respondText(EventPresence.csv(result.check.rows), ContentType.Text.CSV.withParameter("charset", "utf-8"))
        } else respondJson(buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("rows", result.check.rows.size)
            put("withoutNumber", buildJsonArray { result.check.withoutNumber.forEach { add(JsonPrimitive(it)) } })
            put("trainingsWithoutPlace", buildJsonArray { result.check.trainingsWithoutPlace.forEach { add(JsonPrimitive(it)) } })
        })
        EventPresence.Export.NotFound -> refuse(HttpStatusCode.NotFound, "not_found")
        EventPresence.Export.Denied -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}
