package org.openclub.api.announcements

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
import org.openclub.api.members.guarded
import org.openclub.api.members.optionalText
import org.openclub.api.members.readSession
import org.openclub.api.members.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

/**
 * Announcements (OpenAPI 1.14.0, ADR-0008 addendum 18). Reading: session (401), an active membership (403). Changes:
 * session (401), exact Origin and CSRF (403), `POST_ANNOUNCEMENTS` or `MANAGE_TEAMS` somewhere (403), then the input
 * (400), then the audience or announcement itself (403 without the right for it, 404 when it does not exist).
 */
internal fun Application.installAnnouncementHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/me/announcements") {
            call.readSession(sessions, cookies) { actor ->
                val page = database.onDatabaseThread { Announcements(it).mine(actor.accountId, LocalDate.now()) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        page.items.forEach { item -> add(buildJsonObject {
                            put("id", item.id.toString())
                            put("teamId", item.teamId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            put("teamName", item.teamName?.let(::JsonPrimitive) ?: JsonNull)
                            put("title", item.title); put("body", item.body)
                            put("showUntil", item.showUntil.toString()); put("createdAt", item.createdAt.toString())
                            put("canManage", item.canManage)
                        }) }
                    })
                    put("audiences", buildJsonArray {
                        page.audiences.forEach { audience -> add(buildJsonObject {
                            put("teamId", audience.teamId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            put("teamName", audience.teamName?.let(::JsonPrimitive) ?: JsonNull)
                        }) }
                    })
                })
            }
        }
        post("/api/v1/announcements") {
            call.changing(sessions, cookies, origin, database) { actor ->
                val (team, content) = try {
                    val body = call.receiveJsonObject(setOf("teamId", "title", "body"), setOf("showUntil"))
                    val team = when (val value = body["teamId"]) {
                        JsonNull -> null
                        else -> UUID.fromString(body.requiredString("teamId")).also { require(value is JsonPrimitive) }
                    }
                    team to body.content()
                } catch (_: IllegalArgumentException) {
                    return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (val result = database.onDatabaseThread {
                    Announcements(it).create(actor, team, content.title, content.body, content.showUntil) }) {
                    is Announcements.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    Announcements.Created.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    Announcements.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/announcements/{announcementId}") {
            call.changing(sessions, cookies, origin, database) { actor ->
                val id = call.uuid() ?: return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                val content = try {
                    call.receiveJsonObject(setOf("title", "body"), setOf("showUntil")).content()
                } catch (_: IllegalArgumentException) {
                    return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                call.answer(database.onDatabaseThread { Announcements(it).replace(actor, id, content.title, content.body, content.showUntil) })
            }
        }
        delete("/api/v1/announcements/{announcementId}") {
            call.changing(sessions, cookies, origin, database) { actor ->
                val id = call.uuid() ?: return@changing call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { Announcements(it).delete(actor, id) })
            }
        }
    }
}

private class Content(val title: String, val body: String, val showUntil: LocalDate)

/** Title 1–200 and text 1–5,000 characters after trimming (line breaks and tabs allowed in the text); show until today to a year ahead, default 60 days. */
private fun JsonObject.content(): Content {
    val title = requiredString("title").trim()
    require(title.length in 1..200 && title.none(Character::isISOControl)) { "title" }
    val body = requiredString("body").trim().replace("\r\n", "\n")
    require(body.length in 1..5000 && body.none { Character.isISOControl(it) && it != '\n' && it != '\t' }) { "body" }
    val today = LocalDate.now()
    val until = optionalText("showUntil")?.let(LocalDate::parse) ?: today.plusDays(60)
    require(!until.isBefore(today) && !until.isAfter(today.plusYears(1))) { "showUntil" }
    return Content(title, body, until)
}

private fun ApplicationCall.uuid(): UUID? = runCatching { UUID.fromString(parameters["announcementId"]) }.getOrNull()

/** The shared order for changes, plus: no right to post anywhere is 403 before the input is read. */
private suspend fun ApplicationCall.changing(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime,
    block: suspend (UUID) -> Unit,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (!database.onDatabaseThread { Announcements(it).mayPostSomewhere(actor.accountId) }) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    block(actor.accountId)
}

private suspend fun ApplicationCall.answer(change: Announcements.Change) = when (change) {
    Announcements.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    Announcements.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    Announcements.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}
