package org.openclub.api.members

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import org.openclub.api.refuse
import org.openclub.api.guarded

/**
 * `POST /api/v1/members/{memberId}/erase` (OpenAPI 1.3.0; owner-approved 2026-11-18): permanent erasure of an
 * archived member by a holder of EDIT_MEMBERS. Authorization comes first, so a caller without the grant
 * never learns what input is expected. After that, a malformed ID or body is 400; every other refusal (active,
 * linked or administrator member, unknown ID, wrong typed name) is the same 403, so nothing is revealed about
 * members the caller may not erase. The service repeats the authorization atomically.
 */
internal fun Application.installMemberErasureHttpRoute(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        post("/api/v1/members/{memberId}/erase") {
            call.guarded(sessions, cookies, origin, database, ClubCapabilityAccess.Capability.EDIT_MEMBERS) { actor ->
                val id = call.memberId() ?: return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                val confirmation = try {
                    call.receiveJsonObject(required = setOf("confirmation")).requiredString("confirmation")
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                if (confirmation.isBlank() || confirmation.length > 402) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                when (database.onDatabaseThread { MemberErasure(it).erase(actor.accountId, id, confirmation) }) {
                    MemberErasure.Result.ERASED -> call.respondText("", status = HttpStatusCode.NoContent)
                    MemberErasure.Result.DENIED -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            }
        }
    }
}
