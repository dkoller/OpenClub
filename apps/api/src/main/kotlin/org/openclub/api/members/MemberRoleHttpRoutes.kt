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
import java.util.UUID
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredString

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
                val id = UUID.fromString(call.parameters["memberId"])
                val role = database.onDatabaseThread { db ->
                    if (!ClubCapabilityAccess(db).has(actor.accountId,
                            ClubCapabilityAccess.Capability.MANAGE_PERMISSIONS)) null
                    else db.fetchOne("SELECT role FROM members WHERE id = ? AND archived_at IS NULL", id)
                        ?.get("role", String::class.java)
                }
                if (role == null) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                } else {
                    call.respondText(buildJsonObject { put("role", role) }.toString(), ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                    HttpStatusCode.BadRequest)
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
                val id = UUID.fromString(call.parameters["memberId"])
                val role = MemberRoles.Role.valueOf(
                    call.receiveJsonObject(required = setOf("role")).requiredString("role"))
                val outcome = database.onDatabaseThread { db ->
                    MemberRoles(db).change(actor.accountId, id, role)
                }
                if (outcome == MemberRoles.Change.DENIED) {
                    call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                        HttpStatusCode.Forbidden)
                } else call.respond(HttpStatusCode.NoContent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                    HttpStatusCode.BadRequest)
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
