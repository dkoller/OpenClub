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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.guarded
import org.openclub.api.members.optionalText
import org.openclub.api.members.readSession
import org.openclub.api.members.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
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
 * Trainings and events (OpenAPI 1.10.0, ADR-0008 addendum 13). Order of answers: session (401); for changes exact
 * Origin and CSRF (403); a team grant on some team, or for `/me/events` an active membership (403); then the input
 * (400); then the team or event itself (403 without the grant for it, 404 when it does not exist). Responses are
 * `no-store`; times are RFC 3339 and answered in UTC.
 */
internal fun Application.installTeamEventHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
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
                    val body = call.receiveJsonObject(EVENT_FIELDS, OPTIONAL_FIELDS + setOf("repeatWeeklyUntil", "timeZone"), EVENT_BODY)
                    val input = body.eventInput()
                    val until = body.optionalText("repeatWeeklyUntil")?.let(LocalDate::parse)
                    val zone = body.optionalText("timeZone")?.let(ZoneId::of)
                    require((until == null) == (zone == null)) { "A weekly repeat needs both an end date and a time zone" }
                    input to requireNotNull(TeamEvents.weeklyStarts(input.startsAt, until, zone)) { "Too many copies" }
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (val result = database.onDatabaseThread { TeamEvents(it).create(actor.accountId, team, input, starts) }) {
                    is TeamEvents.Created.Done -> call.respondText(buildJsonObject {
                        put("ids", buildJsonArray { result.ids.forEach { add(JsonPrimitive(it.toString())) } })
                    }.toString(), ContentType.Application.Json, HttpStatusCode.Created)
                    TeamEvents.Created.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    TeamEvents.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
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
                call.answer(database.onDatabaseThread { TeamEvents(it).replace(actor.accountId, event, input, cancelled) })
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
                call.answer(database.onDatabaseThread { TeamEvents(it).delete(actor.accountId, event) })
            }
        }
    }
}

private val EVENT_FIELDS = setOf("kind", "title", "startsAt", "endsAt")
/** Notes of 2,000 characters and a 200-character title and place can exceed 4 KiB as JSON. */
private const val EVENT_BODY = 16 * 1024
private val OPTIONAL_FIELDS = setOf("location", "notes")
private val EARLIEST = OffsetDateTime.parse("2000-01-01T00:00:00Z")
private val LATEST = OffsetDateTime.parse("2100-01-01T00:00:00Z")
private val DEFAULT_SPAN: Duration = Duration.ofDays(56)
private val MAX_SPAN: Duration = Duration.ofDays(366)

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
private fun text(value: String?, max: Int, multiline: Boolean = false): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
    require(it.length <= max && it.none { char -> Character.isISOControl(char) && !(multiline && (char == '\n' || char == '\t')) })
}

private fun JsonObject.eventInput(): TeamEvents.Input {
    val kind = TeamEvents.Kind.entries.firstOrNull { it.name == requiredString("kind") } ?: throw IllegalArgumentException("kind")
    val title = requireNotNull(text(requiredString("title"), 200)) { "title" }
    val startsAt = OffsetDateTime.parse(requiredString("startsAt"))
    val endsAt = OffsetDateTime.parse(requiredString("endsAt"))
    require(!startsAt.isBefore(EARLIEST) && startsAt.isBefore(LATEST)) { "start" }
    require(endsAt.isAfter(startsAt) && Duration.between(startsAt, endsAt) <= Duration.ofDays(7)) { "end" }
    return TeamEvents.Input(kind, title, startsAt, endsAt, text(optionalText("location"), 200), text(optionalText("notes"), 2000, multiline = true))
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
    if (withFor) put("for", buildJsonArray {
        forMembers.forEach { add(buildJsonObject {
            put("id", it.id.toString()); put("firstName", it.firstName)
            put("response", it.response?.let { response -> JsonPrimitive(response.name) } ?: JsonNull)
        }) }
    })
}

private suspend fun ApplicationCall.answer(change: TeamEvents.Change) = when (change) {
    TeamEvents.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    TeamEvents.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    TeamEvents.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}

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
    val response = if (clear) null else try {
        val value = receiveJsonObject(setOf("response")).requiredString("response")
        EventAttendance.Response.entries.firstOrNull { it.name == value } ?: throw IllegalArgumentException("response")
    } catch (_: IllegalArgumentException) {
        return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    }
    val change = database.onDatabaseThread {
        val attendance = EventAttendance(it)
        if (response == null) attendance.clear(actor.accountId, event, member)
        else attendance.answer(actor.accountId, event, member, response)
    }
    when (change) {
        EventAttendance.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
        EventAttendance.Change.CLOSED -> refuse(HttpStatusCode.Conflict, "conflict")
        EventAttendance.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
    }
}
