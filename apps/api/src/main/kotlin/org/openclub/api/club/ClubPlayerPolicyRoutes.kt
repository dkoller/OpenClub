package org.openclub.api.club

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.receiveJsonObject

/** Club member-policy settings (player gender, adult age). Database triggers enforce existing member compatibility. */
internal fun Application.installClubPlayerPolicyRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        patch("/api/v1/club/player-gender-policy") {
            call.updateClubPolicy(sessions, cookies, origin, database,
                field = "genderRequiredForPlayers", column = "gender_required_for_players") { value ->
                require(!value.isString)
                value.booleanOrNull ?: throw IllegalArgumentException("Invalid boolean")
            }
        }
        patch("/api/v1/club/adult-age-policy") {
            // ADR-0007: the adult age is configurable per club within the schema bounds.
            call.updateClubPolicy(sessions, cookies, origin, database,
                field = "adultAgeYears", column = "adult_age_years") { value ->
                require(!value.isString)
                val years = value.content.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer")
                require(value.content == years.toString() && years in ADULT_AGE_RANGE)
                years
            }
        }
    }
}

internal val ADULT_AGE_RANGE = 13..25

/**
 * Applies one club policy field from a JSON body with exactly that key. The
 * session, exact Origin and session CSRF are checked first; the UPDATE itself
 * requires a live, identity-bound ADMIN with MANAGE_PERMISSIONS. V7's club policy
 * trigger rejects a stricter setting that existing members do not satisfy (409),
 * serialized with member writes, without disclosing which members are affected.
 */
private suspend fun ApplicationCall.updateClubPolicy(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    field: String,
    column: String,
    parse: (JsonPrimitive) -> Any,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = sessions.read(request.cookies[cookies.sessionName])
        if (actor == null) {
            respondText("""{"code":"unauthenticated"}""", ContentType.Application.Json,
                HttpStatusCode.Unauthorized)
            return
        }
        if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin) ||
            request.headers.getAll("X-CSRF-Token") != listOf(actor.csrfToken)) {
            respondText("""{"code":"forbidden"}""", ContentType.Application.Json,
                HttpStatusCode.Forbidden)
            return
        }
        // Checked before the body is validated, so callers without the grant always get 403.
        // The UPDATE below repeats the check atomically.
        if (!database.onDatabaseThread { it.holdsClubAdminGrant(actor.accountId) }) {
            respondText("""{"code":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return
        }
        val value = try {
            val fields = receiveJsonObject(required = setOf(field))
            parse(fields[field] as? JsonPrimitive ?: throw IllegalArgumentException("Missing policy"))
        } catch (_: IllegalArgumentException) {
            respondText("""{"code":"invalid"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val updated = database.onDatabaseThread { db ->
            db.execute(
                """UPDATE clubs SET $column = ?
                   WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_PERMISSIONS, requireAdmin = true)}""",
                value, actor.accountId,
            ) == 1
        }
        if (updated) respond(HttpStatusCode.NoContent)
        else respondText("""{"code":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: DataAccessException) {
        respondText("""{"code":"conflict"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
    } catch (_: Exception) {
        respondText("""{"code":"unavailable"}""", ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable)
    }
}
