package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthAdmissionLimiter
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID

/**
 * Invitations and account unlinking (OpenAPI 1.2.0; owner-approved 2026-11-18). Every route is `no-store`, decides
 * authorization before it validates anything, and ends in one generic outcome per class of refusal. The services
 * repeat their checks atomically and stay authoritative; the up-front check only fixes the order of answers.
 */
internal fun Application.installMemberInvitationHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    admission: AuthAdmissionLimiter,
) {
    routing {
        post("/api/v1/members/{memberId}/invitation") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val id = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MemberInvitations(it).create(actor.accountId, id) }) {
                    is MemberInvitations.CreateResult.Created -> call.respondText(
                        buildJsonObject { put("secret", result.secret); put("expiresAt", result.expiresAt.toString()) }.toString(),
                        ContentType.Application.Json, HttpStatusCode.Created)
                    MemberInvitations.CreateResult.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    MemberInvitations.CreateResult.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberInvitations.CreateResult.Conflict -> call.refuse(HttpStatusCode.Conflict, "conflict")
                }
            }
        }
        delete("/api/v1/members/{memberId}/invitation") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val id = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (database.onDatabaseThread { MemberInvitations(it).revoke(actor.accountId, id) }) {
                    MemberInvitations.RevokeResult.REVOKED, MemberInvitations.RevokeResult.NOTHING_OPEN ->
                        call.respondText("", status = HttpStatusCode.NoContent)
                    MemberInvitations.RevokeResult.DENIED -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    MemberInvitations.RevokeResult.NOT_FOUND -> call.refuse(HttpStatusCode.NotFound, "not_found")
                }
            }
        }
        delete("/api/v1/members/{memberId}/account-link") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS) { actor ->
                val id = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (database.onDatabaseThread { MemberAccountLink(it).unlink(actor.accountId, id) }) {
                    MemberAccountLink.Result.UNLINKED -> call.respondText("", status = HttpStatusCode.NoContent)
                    MemberAccountLink.Result.DENIED -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    MemberAccountLink.Result.NOT_FOUND -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberAccountLink.Result.CONFLICT -> call.refuse(HttpStatusCode.Conflict, "conflict")
                }
            }
        }
        // No capability: the proofs are the secret and a verified session. The same admission budget as the
        // authentication routes bounds guessing before any database work.
        post("/api/v1/invitations/accept") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (!admission.allowInvitation()) {
                call.response.headers.append(HttpHeaders.RetryAfter, "60")
                return@post call.refuse(HttpStatusCode.TooManyRequests, "rate_limited")
            }
            call.guarded(sessions, cookies, origin, database, capability = null) { actor ->
                val secret = try {
                    call.receiveJsonObject(required = setOf("secret")).requiredString("secret")
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (database.onDatabaseThread { MemberInvitations(it).accept(actor.accountId, secret) }) {
                    MemberInvitations.AcceptResult.ACCEPTED -> call.respondText("", status = HttpStatusCode.NoContent)
                    // One answer for every reason, so nothing about members or invitations is revealed.
                    MemberInvitations.AcceptResult.REFUSED -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
            }
        }
    }
}

internal fun ApplicationCall.memberId(): UUID? = runCatching { UUID.fromString(parameters["memberId"]) }.getOrNull()

/**
 * True for a request another site started, such as a link or form on a third-party page. Reads that write an audit
 * event (details, export, a dependent's record) refuse these, so no other site can make a member's browser
 * produce a false audit entry or a download. Browsers without Fetch Metadata send no header and are allowed:
 * the session cookie is `SameSite=Lax` and these responses are never readable cross-origin.
 */
internal fun ApplicationCall.isCrossSiteRequest(): Boolean =
    request.headers["Sec-Fetch-Site"]?.let { it != "same-origin" && it != "none" } ?: false

internal suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String) =
    respondText("""{"code":"$code"}""", ContentType.Application.Json, status)

/**
 * The shared order of answers: session (401), exact Origin and session CSRF (403), the capability if the route
 * needs one (403), and only then the route's own validation and work. Any unexpected failure is a 503.
 */
internal suspend fun ApplicationCall.guarded(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    capability: ClubCapabilityAccess.Capability?,
    block: suspend (ActiveSession) -> Unit,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = sessions.read(request.cookies[cookies.sessionName])
            ?: return refuse(HttpStatusCode.Unauthorized, "unauthenticated")
        if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
            request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
            return refuse(HttpStatusCode.Forbidden, "forbidden")
        }
        if (capability != null &&
            !database.onDatabaseThread { ClubCapabilityAccess(it).has(actor.accountId, capability) }) {
            return refuse(HttpStatusCode.Forbidden, "forbidden")
        }
        block(actor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}
