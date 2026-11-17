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
import kotlinx.serialization.json.booleanOrNull
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability

/** Configures only player-gender requirement; database triggers enforce existing member compatibility. */
internal fun Application.installClubPlayerPolicyRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        patch("/api/v1/club/player-gender-policy") {
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
                    ?: throw IllegalArgumentException("Invalid policy object")
                require(fields.keys == setOf("genderRequiredForPlayers"))
                val policy = fields["genderRequiredForPlayers"] as? JsonPrimitive
                    ?: throw IllegalArgumentException("Missing policy")
                require(!policy.isString)
                val required = policy.booleanOrNull ?: throw IllegalArgumentException("Invalid boolean")
                val updated = database.onDatabaseThread { db ->
                    // Enforce a live, identity-bound admin grant atomically with the change.
                    // V7's club policy trigger rejects stricter settings when existing
                    // players lack gender and serializes changes with member writes.
                    db.execute(
                        """UPDATE clubs SET gender_required_for_players = ?
                           WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_PERMISSIONS, requireAdmin = true)}""",
                        required, actor.accountId,
                    ) == 1
                }
                if (updated) call.respond(HttpStatusCode.NoContent)
                else call.respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                    HttpStatusCode.Forbidden)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                call.respondText("""{"code":"invalid"}""", ContentType.Application.Json,
                    HttpStatusCode.BadRequest)
            } catch (_: DataAccessException) {
                // V7 rejects incompatible stricter policies without disclosing member fields.
                call.respondText("""{"code":"conflict"}""", ContentType.Application.Json,
                    HttpStatusCode.Conflict)
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
