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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString
import java.util.UUID

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
        get("/api/v1/members/{memberId}/role") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                        HttpStatusCode.Unauthorized)
                    return@get
                }
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
                    RoleRead.Denied -> call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    RoleRead.Invalid -> call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                        HttpStatusCode.BadRequest)
                    is RoleRead.Found -> call.respondText(buildJsonObject { put("role", read.role) }.toString(),
                        ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
        patch("/api/v1/members/{memberId}/role") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val actor = sessions.read(call.request.cookies[cookies.sessionName])
                if (actor == null) {
                    call.respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                        HttpStatusCode.Unauthorized)
                    return@patch
                }
                if (call.request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
                    call.request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    return@patch
                }
                // Advisory pre-check so unauthorized callers get 403 for malformed input too; the
                // service below repeats the check atomically under its lock and stays authoritative.
                val permitted = database.onDatabaseThread { db ->
                    ClubCapabilityAccess(db).has(actor.accountId,
                        ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)
                }
                if (!permitted) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                    return@patch
                }
                val (id, role) = try {
                    UUID.fromString(call.parameters["memberId"]) to MemberRoles.Role.valueOf(
                        call.receiveJsonObject(required = setOf("role")).requiredString("role"))
                } catch (_: IllegalArgumentException) {
                    call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                        HttpStatusCode.BadRequest)
                    return@patch
                }
                val outcome = database.onDatabaseThread { db ->
                    MemberRoles(db).change(actor.accountId, id, role)
                }
                if (outcome == MemberRoles.Change.DENIED) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                } else call.respond(HttpStatusCode.NoContent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
