package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.refuse

/**
 * `GET /api/v1/members/{memberId}/details` (OpenAPI 1.3.0; owner-approved 2026-11-18). Authorization is decided
 * before the path is validated: a caller who may read no member's details gets 403 for every ID, even a
 * malformed one. A malformed ID from a caller who holds EDIT_MEMBERS or MANAGE_TEAMS is 400. A read needs no
 * CSRF token (it changes nothing but the audit trail) and is never cacheable.
 *
 * `GET /api/v1/members/{memberId}/export` (OpenAPI 1.7.0; owner-approved 2026-11-19, ADR-0008 addendum 10): the
 * complete [MemberExport] for an EDIT_MEMBERS holder, as a JSON download for an access or portability request.
 * Same order of answers (403 for any input without the grant, then 400); unknown members are 404; each export is
 * audited as MEMBER_EXPORTED with the editor as actor.
 */
internal fun Application.installMemberDetailsHttpRoute(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/members/{memberId}/details") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                    ?: return@get call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                if (call.isCrossSiteRequest()) return@get call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val mayRead = database.onDatabaseThread { db ->
                    val access = ClubCapabilityAccess(db)
                    access.has(actor.accountId, ClubCapabilityAccess.Capability.EDIT_MEMBERS) ||
                        access.holdsAnyTeamManagement(actor.accountId)
                }
                if (!mayRead) return@get call.refuse(HttpStatusCode.Forbidden, "forbidden")
                val id = call.memberId() ?: return@get call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MemberDetailsRead(it).read(actor.accountId, id) }) {
                    is MemberDetailsRead.Result.Found -> {
                        result.version?.let { call.response.headers.append(HttpHeaders.ETag, memberETag(it)) }
                        call.respondText(result.body.toString(), ContentType.Application.Json)
                    }
                    MemberDetailsRead.Result.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    MemberDetailsRead.Result.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
        get("/api/v1/members/{memberId}/export") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                    ?: return@get call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                if (call.isCrossSiteRequest()) return@get call.refuse(HttpStatusCode.Forbidden, "forbidden")
                if (!database.onDatabaseThread {
                        ClubCapabilityAccess(it).has(actor.accountId, ClubCapabilityAccess.Capability.EDIT_MEMBERS) }) {
                    return@get call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val id = call.memberId() ?: return@get call.refuse(HttpStatusCode.BadRequest, "invalid")
                when (val result = database.onDatabaseThread { MemberExport(it).exportAsEditor(actor.accountId, id) }) {
                    is MemberExport.EditorExport.Exported -> {
                        // A download, never rendered inline: the file name carries only the member ID.
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"member-$id.json\"")
                        call.response.headers.append("X-Content-Type-Options", "nosniff")
                        call.respondText(result.json.toString(), ContentType.Application.Json)
                    }
                    MemberExport.EditorExport.NotFound -> call.refuse(HttpStatusCode.NotFound, "not_found")
                    MemberExport.EditorExport.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}
