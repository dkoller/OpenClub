package org.openclub.api.club

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime

/** A narrow synthetic-M1 settings write; neither role nor actor is supplied by the caller. */
internal fun Application.installClubNameRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        patch("/api/v1/club") {
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
                require(call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull()?.let {
                    ContentType.parse(it).withoutParameters() == ContentType.Application.Json
                } == true)
                val channel = call.receiveChannel()
                val bytes = ByteArray(4097)
                var size = 0
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                require(size in 1..4096)
                val fields = Json.parseToJsonElement(bytes.copyOf(size).toString(Charsets.UTF_8)) as? JsonObject
                    ?: throw IllegalArgumentException("Invalid JSON")
                require(fields.keys == setOf("name"))
                val name = (fields["name"] as? JsonPrimitive)?.let {
                    require(it.isString)
                    it.content.trim()
                } ?: throw IllegalArgumentException("Missing name")
                require(name.length in 1..200 && name.none { it.isISOControl() })
                // The update and live authorization are one statement, so grant revocation
                // cannot be bypassed by a stale UI state or a forged actor identifier.
                val changed = database.onDatabaseThread { db ->
                    db.execute(
                        """UPDATE clubs SET name = ?
                           WHERE EXISTS (
                               SELECT 1 FROM members actor
                               JOIN club_capability_grants g ON g.member_id = actor.id
                               WHERE actor.account_id = ? AND actor.archived_at IS NULL
                                 AND actor.role = 'ADMIN' AND g.capability = 'MANAGE_PERMISSIONS'
                                 AND EXISTS (SELECT 1 FROM account_identities identity
                                             WHERE identity.account_id = actor.account_id)
                           )""",
                        name, actor.accountId,
                    ) == 1
                }
                if (changed) call.respond(HttpStatusCode.NoContent)
                else call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                    HttpStatusCode.Forbidden)
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
