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

/**
 * Trainings and events (OpenAPI 1.10.0, ADR-0008 addendum 13; answer reminders 1.25.0, addendum 29). Order of answers: session (401); for changes exact
 * Origin and CSRF (403); a team grant on some team, or for `/me/events` an active membership (403); then the input
 * (400); then the team or event itself (403 without the grant for it, 404 when it does not exist). Responses are
 * `no-store`; times are RFC 3339 and answered in UTC.
 */
internal fun Application.installTeamEventHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    notifier: Notifier = Notifier.DISABLED,
) {
    routing {
        get("/api/v1/me/events") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val (from, to) = call.range() ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                val page = database.onDatabaseThread { TeamEvents(it).mine(actor.accountId, from, to) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray { page.events.forEach { add(it.json(withFor = true)) } })
                    put("truncated", page.truncated)
                })
            }
        }
        get("/api/v1/teams/{teamId}/events") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread {
                        TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.VIEW_TEAMS, Capability.MANAGE_TEAMS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val team = call.uuid("teamId")
                val range = call.range()
                if (team == null || range == null) return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { TeamEvents(it).listForTeam(actor.accountId, team, range.first, range.second) }) {
                    is TeamEvents.Listing.Found -> call.respondJson(buildJsonObject {
                        put("items", buildJsonArray { result.page.events.forEach { add(it.json(withFor = false)) } })
                        put("truncated", result.page.truncated)
                        put("canManage", result.canManage)
                    })
                    TeamEvents.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    TeamEvents.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        post("/api/v1/teams/{teamId}/events") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val team = call.uuid("teamId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (input, starts) = try {
                    val body = call.receiveJsonObject(EVENT_FIELDS, OPTIONAL_FIELDS + setOf("repeatWeeklyUntil", "timeZone", "repeatEveryWeeks"), EVENT_BODY)
                    val input = body.eventInput()
                    val until = body.optionalText("repeatWeeklyUntil")?.let(LocalDate::parse)
                    val zone = body.optionalText("timeZone")?.let(ZoneId::of)
                    require((until == null) == (zone == null)) { "A weekly repeat needs both an end date and a time zone" }
                    // Every 1–4 weeks (1.40.0), only with a repeat; default every week.
                    val every = when (val value = body["repeatEveryWeeks"]) {
                        null -> 1
                        is JsonPrimitive -> value.takeUnless { it.isString }?.content?.toIntOrNull()?.takeIf { it in 1..4 && until != null }
                            ?: throw IllegalArgumentException("repeatEveryWeeks")
                        else -> throw IllegalArgumentException("repeatEveryWeeks")
                    }
                    input to requireNotNull(TeamEvents.weeklyStarts(input.startsAt, until, zone, every.toLong())) { "Too many copies" }
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val created = venueRace { database.onDatabaseThread { db -> db.transactionResult { c -> TeamEvents(DSL.using(c), notifier).create(actor.accountId, team, input, starts) } } }
                    ?: return@guarded call.refuse(HttpStatusCode.Conflict, "venue_taken")
                when (val result = created) {
                    is TeamEvents.Created.Done -> call.respondText(buildJsonObject {
                        put("ids", buildJsonArray { result.ids.forEach { add(JsonPrimitive(it.toString())) } })
                    }.toString(), ContentType.Application.Json, HttpStatusCode.Created)
                    TeamEvents.Created.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    TeamEvents.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    TeamEvents.Created.UnknownVenue -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    is TeamEvents.Created.VenueTaken -> call.venueTaken(result.clash)
                }
            }
        }
        // Several events at once (1.29.0): cancel, reinstate or move to another place, with a preview.
        post("/api/v1/events/bulk") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val input = try {
                    val body = call.receiveJsonObject(setOf("teamIds", "from", "to", "kinds", "action", "location", "preview"), setOf("venueId", "startTime", "endTime"))
                    val teams = body.uuidSet("teamIds", 50)
                    require(teams.isNotEmpty())
                    val from = strictDate(body.requiredString("from"))
                    val to = strictDate(body.requiredString("to"))
                    require(!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 366 && from.year in 2000..2099 && to.year in 2000..2099)
                    val kindsJson = body["kinds"] as? kotlinx.serialization.json.JsonArray ?: throw IllegalArgumentException("kinds")
                    val kinds = kindsJson.map { value ->
                        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        TeamEvents.Kind.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("kind")
                    }
                    require(kinds.isNotEmpty() && kinds.toSet().size == kinds.size)
                    val action = BulkEvents.Action.entries.firstOrNull { it.name == body.requiredString("action") }
                        ?: throw IllegalArgumentException("action")
                    val location = when (val value = body["location"]) {
                        is JsonNull -> null
                        is JsonPrimitive -> if (value.isString) text(value.content, 200) else throw IllegalArgumentException("location")
                        else -> throw IllegalArgumentException("location")
                    }
                    require(action == BulkEvents.Action.SET_LOCATION || location == null)
                    // SET_VENUE (1.30.0) names a venue, or null to clear it; other actions take no venue.
                    val venue = when (val value = body["venueId"]) {
                        null, is JsonNull -> null
                        is JsonPrimitive -> if (value.isString) UUID.fromString(value.content) else throw IllegalArgumentException("venueId")
                        else -> throw IllegalArgumentException("venueId")
                    }
                    require(action == BulkEvents.Action.SET_VENUE || venue == null)
                    // SET_TIMES (1.40.0) needs both times ("18:30"), the end after the start; other actions take none.
                    val startTime = body.optionalText("startTime")?.let(::localTime)
                    val endTime = body.optionalText("endTime")?.let(::localTime)
                    if (action == BulkEvents.Action.SET_TIMES) require(startTime != null && endTime != null && endTime.isAfter(startTime))
                    else require(startTime == null && endTime == null)
                    val preview = body.requiredBoolean("preview")
                    BulkEvents.Input(teams, from, to, kinds.toSet(), action, location, preview, venue, startTime, endTime)
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val result = try {
                    database.onDatabaseThread { BulkEvents(it, notifier).apply(actor.accountId, input) }
                } catch (error: org.jooq.exception.DataAccessException) {
                    // Two of the moved events, or a moved one and another, would hold the venue at the same time.
                    if (error.sqlState() == "23P01") return@guarded call.refuse(HttpStatusCode.Conflict, "venue_taken")
                    throw error
                }
                when (result) {
                    is BulkEvents.Result.Done -> call.respondJson(buildJsonObject {
                        put("matched", result.matched); put("changed", result.changed)
                    })
                    BulkEvents.Result.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    BulkEvents.Result.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    BulkEvents.Result.TooMany -> call.refuse(HttpStatusCode.Conflict, "too_many")
                    BulkEvents.Result.UnknownVenue -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
            }
        }
        put("/api/v1/events/{eventId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (input, cancelled) = try {
                    val body = call.receiveJsonObject(EVENT_FIELDS + "cancelled", OPTIONAL_FIELDS, EVENT_BODY)
                    val cancelled = body["cancelled"] as? JsonPrimitive
                    require(cancelled != null && !cancelled.isString && cancelled.content in setOf("true", "false")) { "Expected a boolean" }
                    body.eventInput() to (cancelled.content == "true")
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val (change, clash) = venueRace { database.onDatabaseThread { db -> db.transactionResult { c ->
                    val events = TeamEvents(DSL.using(c), notifier)
                    events.replace(actor.accountId, event, input, cancelled) to events.clash
                } } } ?: return@guarded call.refuse(HttpStatusCode.Conflict, "venue_taken")
                if (change == TeamEvents.Change.VENUE_TAKEN && clash != null) return@guarded call.venueTaken(clash)
                call.answer(change)
            }
        }
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
        delete("/api/v1/events/{eventId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).holdsSomeTeamCapability(actor.accountId, Capability.MANAGE_TEAMS) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val event = call.uuid("eventId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { db -> db.transactionResult { c -> TeamEvents(DSL.using(c), notifier).delete(actor.accountId, event) } })
            }
        }
    }
}

/** 500 member IDs as JSON. */
private const val PRESENCE_BODY = 24 * 1024

private val EVENT_FIELDS = setOf("kind", "title", "startsAt", "endsAt")
/** Notes of 2,000 characters and a 200-character title and place can exceed 4 KiB as JSON. */
private const val EVENT_BODY = 16 * 1024
private val OPTIONAL_FIELDS = setOf("location", "notes", "venueId", "answersUntil")

/** A note with an answer or an absence (1.39.0). */
internal const val NOTE_LENGTH = 100
private val EARLIEST = OffsetDateTime.parse("2000-01-01T00:00:00Z")
private val LATEST = OffsetDateTime.parse("2100-01-01T00:00:00Z")
private val DEFAULT_SPAN: Duration = Duration.ofDays(56)
private val MAX_SPAN: Duration = Duration.ofDays(366)

/** A local time of day, "18:30". */
private fun localTime(value: String): java.time.LocalTime =
    java.time.LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT))

private fun ApplicationCall.uuid(name: String): UUID? = runCatching { UUID.fromString(parameters[name]) }.getOrNull()

/** `from` (default now) and `to` (default 8 weeks later), RFC 3339 with offset; `to` after `from`, at most 366 days. */
private fun ApplicationCall.range(): Pair<OffsetDateTime, OffsetDateTime>? = try {
    val query = request.queryParameters
    require(query.names().all { it == "from" || it == "to" } && query.names().all { query.getAll(it)?.size == 1 })
    val from = query["from"]?.let(OffsetDateTime::parse) ?: OffsetDateTime.now()
    val to = query["to"]?.let(OffsetDateTime::parse) ?: from.plus(DEFAULT_SPAN)
    require(to.isAfter(from) && Duration.between(from, to) <= MAX_SPAN)
    from to to
} catch (_: IllegalArgumentException) {
    null
} catch (_: DateTimeParseException) {
    null
}

/**
 * `from` and `to` as ISO dates (UTC), both optional: `to` defaults to today, `from` to 12 months before `to`.
 * `from` is not after `to`, the range spans at most 366 days, and both lie between 2000 and 2099.
 */
private fun ApplicationCall.dateRange(): Pair<LocalDate, LocalDate>? = try {
    val query = request.queryParameters
    require(query.names().all { it == "from" || it == "to" } && query.names().all { query.getAll(it)?.size == 1 })
    val to = query["to"]?.let(::strictDate) ?: LocalDate.now(ZoneOffset.UTC)
    val from = query["from"]?.let(::strictDate) ?: to.minusMonths(12)
    require(!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 366)
    require(from.year in 2000..2099 && to.year in 2000..2099)
    from to to
} catch (_: IllegalArgumentException) {
    null
} catch (_: DateTimeException) {
    null
}

private fun strictDate(value: String): LocalDate =
    LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT))

/** Trimmed text: blank optional text is absent; titles 1–200, places 1–200, notes 1–2000 characters, no control characters. */
internal fun text(value: String?, max: Int, multiline: Boolean = false): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
    require(it.length <= max && it.none { char -> Character.isISOControl(char) && !(multiline && (char == '\n' || char == '\t')) })
}

private fun JsonObject.eventInput(): TeamEvents.Input {
    val kind = TeamEvents.Kind.entries.firstOrNull { it.name == requiredString("kind") } ?: throw IllegalArgumentException("kind")
    val title = requireNotNull(text(requiredString("title"), 200)) { "title" }
    val startsAt = OffsetDateTime.parse(requiredString("startsAt"))
    val endsAt = OffsetDateTime.parse(requiredString("endsAt"))
    require(!startsAt.isBefore(EARLIEST) && startsAt.isBefore(LATEST)) { "start" }
    require(endsAt.isAfter(startsAt) && Duration.between(startsAt, endsAt) <= Duration.ofDays(7)) { "end" }
    val venue = when (val value = this["venueId"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) UUID.fromString(value.content) else throw IllegalArgumentException("venueId")
        else -> throw IllegalArgumentException("venueId")
    }
    // The answer deadline (1.39.0) lies before the start and at most 60 days before it.
    val answersUntil = optionalText("answersUntil")?.let(OffsetDateTime::parse)
    require(answersUntil == null || (answersUntil.isBefore(startsAt) && Duration.between(answersUntil, startsAt) <= Duration.ofDays(60))) { "answersUntil" }
    return TeamEvents.Input(kind, title, startsAt, endsAt, text(optionalText("location"), 200), text(optionalText("notes"), 2000, multiline = true), venue,
        answersUntil)
}

private fun TeamEvents.Event.json(withFor: Boolean) = buildJsonObject {
    put("id", id.toString())
    put("teamId", teamId.toString())
    put("teamName", teamName)
    put("kind", kind.name)
    put("title", title)
    put("startsAt", startsAt.toString())
    put("endsAt", endsAt.toString())
    put("location", location?.let(::JsonPrimitive) ?: JsonNull)
    put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
    put("cancelled", cancelled)
    put("counts", counts.json())
    put("venueId", venueId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    put("venueName", venueName?.let(::JsonPrimitive) ?: JsonNull)
    put("answersUntil", answersUntil?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    if (withFor) put("for", buildJsonArray {
        forMembers.forEach { add(buildJsonObject {
            put("id", it.id.toString()); put("firstName", it.firstName)
            put("response", it.response?.let { response -> JsonPrimitive(response.name) } ?: JsonNull)
            put("note", it.note?.let(::JsonPrimitive) ?: JsonNull)
            put("squad", it.squad?.let { squad -> buildJsonObject {
                put("called", squad.called)
                put("meetAt", squad.meetAt?.let(::JsonPrimitive) ?: JsonNull)
                put("meetingPoint", squad.meetingPoint?.let(::JsonPrimitive) ?: JsonNull)
            } } ?: JsonNull)
        }) }
    })
}

private suspend fun ApplicationCall.answer(change: TeamEvents.Change) = when (change) {
    TeamEvents.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    TeamEvents.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    TeamEvents.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    TeamEvents.Change.UNKNOWN_VENUE -> refuse(HttpStatusCode.BadRequest, "invalid")
    TeamEvents.Change.VENUE_TAKEN -> refuse(HttpStatusCode.Conflict, "venue_taken")
}

/**
 * Runs [block]; null when the database's double-booking rule refused it (SQLSTATE 23P01). That happens only when two
 * coaches book the same venue at the same moment, after both passed the clash check (security review 2026-11-26).
 */
private suspend fun <T> venueRace(block: suspend () -> T): T? = try {
    block()
} catch (error: org.jooq.exception.DataAccessException) {
    if (error.sqlState() == "23P01") null else throw error
}

/** 409 `venue_taken` with the event already holding the venue (team, title, start), so the coach can choose. */
private suspend fun ApplicationCall.venueTaken(clash: TeamEvents.Clash) = respondText(buildJsonObject {
    put("code", "venue_taken")
    put("clash", buildJsonObject {
        put("teamName", clash.teamName); put("title", clash.title); put("startsAt", clash.startsAt.toString())
    })
}.toString(), ContentType.Application.Json, HttpStatusCode.Conflict)

private fun EventAttendance.Counts.json() = buildJsonObject { put("yes", yes); put("no", no); put("maybe", maybe) }

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
private fun JsonObject.uuidSet(name: String, max: Int): Set<UUID> {
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
