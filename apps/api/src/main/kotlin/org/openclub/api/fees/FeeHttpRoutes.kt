package org.openclub.api.fees

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
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
import org.openclub.api.members.ClubCapabilityAccess
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.guarded
import org.openclub.api.members.readSession
import org.openclub.api.members.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

/**
 * Membership fees (OpenAPI 1.12.0, ADR-0008 addendum 15). Treasurer routes need the club-wide `MANAGE_FEES`
 * capability: session (401), for changes exact Origin and CSRF (403), the capability (403), then the input (400),
 * then the period or member (404) and conflicts (409). `GET /api/v1/me/fees` shows an active member their own fees and
 * their current minors'. Every response is `no-store`.
 */
internal fun Application.installFeeHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/fee-periods") {
            call.readSession(sessions, cookies) { actor ->
                val periods = database.onDatabaseThread { MembershipFees(it).periods(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        periods.forEach { period -> add(buildJsonObject {
                            put("id", period.id.toString()); put("name", period.name)
                            put("dueDate", period.dueDate.toString()); put("amountCents", period.amountCents)
                            put("members", period.members); put("paid", period.paid)
                            put("totalCents", period.totalCents); put("paidCents", period.paidCents)
                        }) }
                    })
                })
            }
        }
        post("/api/v1/fee-periods") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val (name, due, amount) = try {
                    val body = call.receiveJsonObject(setOf("name", "dueDate", "amountCents"))
                    Triple(body.name(), body.dueDate(), body.amount("amountCents"))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (val result = database.onDatabaseThread { MembershipFees(it).create(actor.accountId, name, due, amount) }) {
                    is MembershipFees.Created.Done -> call.respondText(buildJsonObject { put("id", result.id.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    MembershipFees.Created.Conflict -> call.refuse(HttpStatusCode.Conflict, "conflict")
                    MembershipFees.Created.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        patch("/api/v1/fee-periods/{periodId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val period = call.uuid("periodId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (name, due) = try {
                    val body = call.receiveJsonObject(setOf("name", "dueDate"))
                    body.name() to body.dueDate()
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                call.answer(database.onDatabaseThread { MembershipFees(it).rename(actor.accountId, period, name, due) })
            }
        }
        delete("/api/v1/fee-periods/{periodId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val period = call.uuid("periodId") ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val confirmation = try {
                    call.receiveJsonObject(setOf("name")).requiredString("name")
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                call.answer(database.onDatabaseThread { MembershipFees(it).delete(actor.accountId, period, confirmation) })
            }
        }
        get("/api/v1/fee-periods/{periodId}/fees") {
            call.readSession(sessions, cookies) { actor ->
                if (!database.onDatabaseThread { ClubCapabilityAccess(it).has(actor.accountId, Capability.MANAGE_FEES) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val period = call.uuid("periodId")
                val filter = call.request.queryParameters.let { query ->
                    val value = query["status"] ?: "all"
                    MembershipFees.Filter.entries.firstOrNull { it.name.lowercase() == value }
                        ?.takeIf { query.names().all { name -> name == "status" } && (query.getAll("status")?.size ?: 0) <= 1 }
                }
                if (period == null || filter == null) return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MembershipFees(it).fees(actor.accountId, period, filter) }) {
                    is MembershipFees.Listing.Found -> call.respondJson(buildJsonObject {
                        put("items", buildJsonArray {
                            result.fees.forEach { fee -> add(buildJsonObject {
                                put("memberId", fee.memberId.toString()); put("firstName", fee.firstName)
                                put("lastName", fee.lastName); put("archived", fee.archived)
                                put("amountCents", fee.amountCents?.let(::JsonPrimitive) ?: JsonNull)
                                put("paidOn", fee.paidOn?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            }) }
                        })
                    })
                    MembershipFees.Listing.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MembershipFees.Listing.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/fee-periods/{periodId}/fees/{memberId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val period = call.uuid("periodId")
                val member = call.uuid("memberId")
                if (period == null || member == null) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val (amount, paidOn) = try {
                    val body = call.receiveJsonObject(setOf("amountCents", "paidOn"))
                    val paid = body["paidOn"]
                    body.amount("amountCents") to (if (paid == JsonNull) null else body.date("paidOn"))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                call.answer(database.onDatabaseThread { MembershipFees(it).set(actor.accountId, period, member, amount, paidOn) })
            }
        }
        delete("/api/v1/fee-periods/{periodId}/fees/{memberId}") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val period = call.uuid("periodId")
                val member = call.uuid("memberId")
                if (period == null || member == null) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                call.answer(database.onDatabaseThread { MembershipFees(it).remove(actor.accountId, period, member) })
            }
        }
        get("/api/v1/me/fees") {
            call.readSession(sessions, cookies) { actor ->
                val fees = database.onDatabaseThread { MembershipFees(it).mine(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        fees.forEach { fee -> add(buildJsonObject {
                            put("periodId", fee.periodId.toString()); put("periodName", fee.periodName)
                            put("dueDate", fee.dueDate.toString()); put("amountCents", fee.amountCents)
                            put("paidOn", fee.paidOn?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            put("for", buildJsonObject { put("id", fee.forId.toString()); put("firstName", fee.forFirstName) })
                        }) }
                    })
                })
            }
        }
    }
}

private fun ApplicationCall.uuid(name: String): UUID? = runCatching { UUID.fromString(parameters[name]) }.getOrNull()

private fun JsonObject.name(): String = requiredString("name").trim().also {
    require(it.length in 1..200 && it.none(Character::isISOControl)) { "name" }
}

private fun JsonObject.date(key: String): LocalDate = LocalDate.parse(requiredString(key)).also {
    require(it.year in 2000..2099) { key }
}

private fun JsonObject.dueDate(): LocalDate = date("dueDate")

/** Whole Swiss cents from 0 to 100,000 francs; a JSON number without fraction, never a string. */
private fun JsonObject.amount(key: String): Int {
    val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException(key)
    require(!value.isString && value != JsonNull && Regex("0|[1-9][0-9]{0,7}").matches(value.content)) { key }
    return value.content.toInt().also { require(it in 0..10_000_000) { key } }
}

private suspend fun ApplicationCall.answer(change: MembershipFees.Change) = when (change) {
    MembershipFees.Change.DONE -> respondText("", status = HttpStatusCode.NoContent)
    MembershipFees.Change.NOT_FOUND -> refuse(HttpStatusCode.NotFound, "not_found")
    MembershipFees.Change.CONFLICT -> refuse(HttpStatusCode.Conflict, "conflict")
    MembershipFees.Change.DENIED -> refuse(HttpStatusCode.Forbidden, "forbidden")
}
