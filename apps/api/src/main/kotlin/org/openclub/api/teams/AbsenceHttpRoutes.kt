package org.openclub.api.teams

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
import org.openclub.api.members.optionalText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

/**
 * Absences (OpenAPI 1.20.0, ADR-0008 addendum 24): `GET`/`POST /api/v1/me/absences`, `DELETE /api/v1/absences/{id}`.
 * Order of answers: session (401); for changes exact Origin and CSRF (403); an active verified member (403); the IDs
 * and body (400); then 403 for a member or absence the caller may not act for, 400 for dates outside the rules.
 */
private class Input(val memberId: UUID, val from: LocalDate, val to: LocalDate, val note: String?)

internal fun Application.installAbsenceHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/me/absences") {
            call.readSession(sessions, cookies) { actor ->
                val listing = database.onDatabaseThread { Absences(it).list(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        listing.items.forEach { add(buildJsonObject {
                            put("id", it.id.toString()); put("memberId", it.memberId.toString()); put("firstName", it.firstName)
                            put("from", it.from.toString()); put("to", it.to.toString())
                            put("note", it.note?.let(::JsonPrimitive) ?: JsonNull)
                        }) }
                    })
                    put("people", buildJsonArray {
                        listing.people.forEach { add(buildJsonObject { put("id", it.id.toString()); put("firstName", it.firstName) }) }
                    })
                })
            }
        }
        post("/api/v1/me/absences") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val strict = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
                val input = try {
                    // Since 1.39.0 an optional note of at most 100 characters, seen by the coaches next to the implied No.
                    val body = call.receiveJsonObject(setOf("memberId", "from", "to"), setOf("note"))
                    Input(UUID.fromString(body.requiredString("memberId")),
                        LocalDate.parse(body.requiredString("from"), strict), LocalDate.parse(body.requiredString("to"), strict),
                        text(body.optionalText("note"), NOTE_LENGTH))
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: DateTimeException) {
                    null
                } ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { Absences(it).create(actor.accountId, input.memberId, input.from, input.to, input.note) }) {
                    is Absences.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    Absences.Created.Invalid -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    Absences.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        delete("/api/v1/absences/{absenceId}") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (!database.onDatabaseThread { TeamEvents(it).isMember(actor.accountId) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val id = runCatching { UUID.fromString(call.parameters["absenceId"]) }.getOrNull()
                    ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                if (!database.onDatabaseThread { Absences(it).delete(actor.accountId, id) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }
}
