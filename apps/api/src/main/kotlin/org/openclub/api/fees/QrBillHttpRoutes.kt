package org.openclub.api.fees

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.guarded
import org.openclub.api.members.optionalText
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID

/**
 * Swiss QR bills for fees (OpenAPI 1.26.0, ADR-0008 addendum 30). `GET`/`PUT /api/v1/club/payment-details` for holders
 * of `MANAGE_FEES`: session (401), for the change exact Origin and CSRF (403), the capability (403), then the input
 * (400). `GET /api/v1/me/fees/{periodId}/{memberId}/qr-bill` returns the A4 PDF of an open fee: session (401), an
 * active verified member whose own fee or current minor's it is (403), the input (400), no fee in that period (404),
 * paid or nothing to pay (409 `not_open`), no payment details (409 `no_payment_details`). Never cacheable.
 */
internal fun Application.installQrBillHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/club/payment-details") {
            call.readSession(sessions, cookies) { actor ->
                when (val result = database.onDatabaseThread { QrBills(it).details(actor.accountId) }) {
                    is QrBills.Reading.Found -> call.respondJson(buildJsonObject {
                        val details = result.details
                        put("iban", details.iban); put("qrIban", SwissPayments.isQrIban(details.iban))
                        put("holderName", details.holderName)
                        put("street", details.street?.let(::JsonPrimitive) ?: JsonNull)
                        put("buildingNumber", details.buildingNumber?.let(::JsonPrimitive) ?: JsonNull)
                        put("postalCode", details.postalCode); put("town", details.town); put("country", details.country)
                    })
                    QrBills.Reading.None -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    QrBills.Reading.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        put("/api/v1/club/payment-details") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val details = (try {
                    val body = call.receiveJsonObject(
                        setOf("iban", "holderName", "postalCode", "town", "country"), setOf("street", "buildingNumber"))
                    QrBills.validate(body.requiredString("iban"), body.requiredString("holderName"),
                        body.optionalText("street"), body.optionalText("buildingNumber"), body.requiredString("postalCode"),
                        body.requiredString("town"), body.requiredString("country"))
                } catch (_: IllegalArgumentException) {
                    null
                }) ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                if (database.onDatabaseThread { QrBills(it).replace(actor.accountId, details) }) {
                    call.respondText("", status = HttpStatusCode.NoContent)
                } else {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
        get("/api/v1/me/fees/{periodId}/{memberId}/qr-bill") {
            call.readSession(sessions, cookies) { actor ->
                val member = database.onDatabaseThread { MembershipFees(it).isActiveMember(actor.accountId) }
                if (!member) return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val period = runCatching { UUID.fromString(call.parameters["periodId"]) }.getOrNull()
                val target = runCatching { UUID.fromString(call.parameters["memberId"]) }.getOrNull()
                val query = call.request.queryParameters
                val language = query["lang"] ?: "en"
                if (period == null || target == null || language !in QrBillPdf.languages ||
                    query.names().any { it != "lang" } || (query.getAll("lang")?.size ?: 0) > 1) {
                    return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (val outcome = database.onDatabaseThread { QrBills(it).bill(actor.accountId, period, target) }) {
                    is QrBills.Outcome.Ready -> {
                        val pdf = QrBillPdf.render(outcome.bill, language)
                        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "qr-bill-${outcome.bill.dueDate}.pdf").toString())
                        call.response.header("X-Content-Type-Options", "nosniff")
                        call.respondBytes(pdf, ContentType.Application.Pdf)
                    }
                    QrBills.Outcome.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    QrBills.Outcome.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    QrBills.Outcome.NotOpen -> call.refuse(HttpStatusCode.Conflict, "not_open")
                    QrBills.Outcome.NoPaymentDetails -> call.refuse(HttpStatusCode.Conflict, "no_payment_details")
                }
            }
        }
    }
}
