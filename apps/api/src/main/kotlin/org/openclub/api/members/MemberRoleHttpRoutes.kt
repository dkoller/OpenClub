package org.openclub.api.members

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID
import org.openclub.api.refuse
import org.openclub.api.readSession

private sealed interface RoleRead {
    data object Denied : RoleRead
    data object Invalid : RoleRead
    data class Found(val role: String) : RoleRead
}

/** Minimal synthetic-M1 role boundary: privileged inspection and serialized changes only. */
internal fun Application.installMemberRoleHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        // Everyone with a role above member (OpenAPI 1.38.0, ADR-0008 addendum 43): names and role only, for
        // MANAGE_PERMISSIONS holders, so Club roles opens with the current administrators.
        get("/api/v1/roles/holders") {
            call.readSession(sessions, cookies) { actor ->
                val holders = database.onDatabaseThread { db ->
                    if (!ClubCapabilityAccess(db).has(actor.accountId,
                            ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)) null
                    else db.fetch(
                        """SELECT id, first_name, last_name, role FROM members
                           WHERE archived_at IS NULL AND role <> 'MEMBER' ORDER BY last_name, first_name, id""",
                    ).map { row -> buildJsonObject {
                        put("memberId", row.get(0, UUID::class.java).toString())
                        put("firstName", row.get(1, String::class.java))
                        put("lastName", row.get(2, String::class.java))
                        put("role", row.get(3, String::class.java))
                    } }
                }
                if (holders == null) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@readSession
                }
                call.respondText(buildJsonObject { put("items", JsonArray(holders)) }.toString(), ContentType.Application.Json)
            }
        }
        get("/api/v1/members/{memberId}/role") {
            call.readSession(sessions, cookies) { actor ->
                // Authorization is decided before the path is validated, so callers without
                // MANAGE_PERMISSIONS learn nothing about expected input.
                val id = runCatching { UUID.fromString(call.parameters["memberId"]) }.getOrNull()
                val read = database.onDatabaseThread { db ->
                    if (!ClubCapabilityAccess(db).has(actor.accountId,
                            ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)) RoleRead.Denied
                    else if (id == null) RoleRead.Invalid
                    else db.fetchOne("SELECT role FROM members WHERE id = ? AND archived_at IS NULL", id)
                        ?.get("role", String::class.java)?.let { RoleRead.Found(it) } ?: RoleRead.Denied
                }
                when (read) {
                    RoleRead.Denied -> call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    RoleRead.Invalid -> call.refuse(HttpStatusCode.BadRequest, "invalid")
                    is RoleRead.Found -> call.respondText(buildJsonObject { put("role", read.role) }.toString(),
                        ContentType.Application.Json)
                }
            }
        }
        patch("/api/v1/members/{memberId}/role") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.refuse(HttpStatusCode.Unauthorized, "unauthenticated")
                    return@patch
                }
                if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
                    call.request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                // Advisory pre-check so unauthorized callers get 403 for malformed input too; the
                // service below repeats the check atomically under its lock and stays authoritative.
                val permitted = database.onDatabaseThread { db ->
                    ClubCapabilityAccess(db).has(actor.accountId,
                        ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)
                }
                if (!permitted) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                    return@patch
                }
                val (id, role) = try {
                    UUID.fromString(call.parameters["memberId"]) to MemberRoles.Role.valueOf(
                        call.receiveJsonObject(required = setOf("role")).requiredString("role"))
                } catch (_: IllegalArgumentException) {
                    call.refuse(HttpStatusCode.BadRequest, "invalid")
                    return@patch
                }
                val outcome = database.onDatabaseThread { db ->
                    MemberRoles(db).change(actor.accountId, id, role)
                }
                if (outcome == MemberRoles.Change.DENIED) {
                    call.refuse(HttpStatusCode.Forbidden, "forbidden")
                } else call.respond(HttpStatusCode.NoContent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
            }
        }
    }
}
