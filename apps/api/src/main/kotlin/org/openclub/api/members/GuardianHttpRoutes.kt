package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.rejectedTheData
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID

/**
 * Guardian routes (OpenAPI 1.6.0, ADR-0008 addendum 8). Editors manage a minor's guardians under
 * `/api/v1/members/{memberId}/guardians`; a guardian reaches only their own minors under `/api/v1/me/dependents`.
 * Every response is `no-store`. Editor routes decide the capability before validating input; guardian routes
 * answer one generic 403 for every refusal (including a malformed ID), so they reveal nothing about other members.
 */
internal fun Application.installGuardianHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/members/{memberId}/guardians") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread {
                        ClubCapabilityAccess(it).has(actor.accountId, ClubCapabilityAccess.Capability.EDIT_MEMBERS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val minor = call.memberId() ?: return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MemberGuardianships(it).list(actor.accountId, minor) }) {
                    is MemberGuardianships.Listing.Found -> call.respondJson(names(result.guardians))
                    MemberGuardianships.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberGuardianships.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        post("/api/v1/members/{memberId}/guardians") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val minor = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val guardian = try {
                    UUID.fromString(call.receiveJsonObject(required = setOf("guardianMemberId")).requiredString("guardianMemberId"))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                call.answer(database.onDatabaseThread { MemberGuardianships(it).add(actor.accountId, minor, guardian) })
            }
        }
        delete("/api/v1/members/{memberId}/guardians/{guardianMemberId}") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val minor = call.memberId()
                val guardian = runCatching { UUID.fromString(call.parameters["guardianMemberId"]) }.getOrNull()
                if (minor == null || guardian == null) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { MemberGuardianships(it).remove(actor.accountId, minor, guardian) })
            }
        }
        get("/api/v1/me/dependents") {
            call.readSession(sessions, cookies) { actor ->
                call.respondJson(names(database.onDatabaseThread { Dependents(it).list(actor.accountId) }))
            }
        }
        get("/api/v1/me/dependents/{memberId}") {
            call.readSession(sessions, cookies) { actor ->
                if (call.isCrossSiteRequest()) return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val minor = call.memberId() ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val details = database.onDatabaseThread { Dependents(it).read(actor.accountId, minor) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("id", details.id.toString())
                    put("firstName", details.firstName)
                    put("lastName", details.lastName)
                    put("dateOfBirth", details.dateOfBirth.toString())
                    put("email", details.email?.let(::JsonPrimitive) ?: JsonNull)
                    put("phone", details.phone?.let(::JsonPrimitive) ?: JsonNull)
                    put("isPlayer", details.isPlayer)
                    put("teams", buildJsonArray { details.teams.forEach { add(JsonPrimitive(it)) } })
                })
            }
        }
        put("/api/v1/me/dependents/{memberId}/contacts") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                val minor = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                // The relationship decides first, so only a current guardian ever learns what input is expected.
                if (!database.onDatabaseThread { Dependents(it).isGuardian(actor.accountId, minor) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val (email, phone) = try {
                    val body = call.receiveJsonObject(required = emptySet(), optional = setOf("email", "phone"))
                    MemberFieldValidation.email(body.optionalText("email")) to MemberFieldValidation.phone(body.optionalText("phone"))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val changed = try {
                    database.onDatabaseThread { Dependents(it).replaceContacts(actor.accountId, minor, email, phone) }
                } catch (failure: DataAccessException) {
                    if (failure.rejectedTheData()) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                    throw failure
                }
                if (changed) call.respondText("", status = HttpStatusCode.NoContent)
                else call.refuse(HttpStatusCode.Forbidden, "forbidden")
            }
        }
    }
}

/** A read: session (401), then the route's own checks; no Origin or CSRF; never cacheable; faults are 503. */
private suspend fun ApplicationCall.readSession(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    block: suspend (ActiveSession) -> Unit,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = sessions.read(request.cookies[cookies.sessionName])
            ?: return refuse(HttpStatusCode.Unauthorized, "unauthenticated")
        block(actor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private suspend fun ApplicationCall.answer(result: MemberGuardianships.Change) = when (result) {
    MemberGuardianships.Change.DONE, MemberGuardianships.Change.ABSENT -> respondText("", status = HttpStatusCode.NoContent)
    MemberGuardianships.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    MemberGuardianships.Change.CONFLICT -> refuse(HttpStatusCode.Conflict, "conflict")
    MemberGuardianships.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}

private suspend fun ApplicationCall.respondJson(body: JsonObject) = respondText(body.toString(), ContentType.Application.Json)

private fun names(members: List<MemberDirectory.Summary>) = buildJsonObject {
    put("items", buildJsonArray {
        members.forEach { add(buildJsonObject { put("id", it.id.toString()); put("firstName", it.firstName); put("lastName", it.lastName) }) }
    })
}

/** An optional string field; JSON null and an absent key both mean "clear". */
private fun JsonObject.optionalText(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (value.isString) value.content else throw IllegalArgumentException("Expected a string")
    else -> throw IllegalArgumentException("Expected a string")
}
