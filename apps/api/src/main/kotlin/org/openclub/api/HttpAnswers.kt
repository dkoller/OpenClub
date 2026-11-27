package org.openclub.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject
import org.openclub.api.members.ClubCapabilityAccess
import org.openclub.api.database.DatabaseRuntime

/** Every refusal of the administration API: one JSON object with a stable `code` and the given status. */
internal suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String) =
    respondText("""{"code":"$code"}""", ContentType.Application.Json, status)

/** A read: session (401), then the route's own checks; no Origin or CSRF; never cacheable; faults are 503. */
internal suspend fun ApplicationCall.readSession(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    block: suspend (ActiveSession) -> Unit,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val actor = sessions.read(request.cookies[cookies.sessionName])
            ?: return refuse(HttpStatusCode.Unauthorized, "unauthenticated")
        block(actor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure(failure)
        refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

private val failures = LoggerFactory.getLogger("org.openclub.api.failures")

/**
 * Logs why a request ended in 503: method, path (no query string, so no searched names), the exception classes,
 * the SQL state when there is one and the stack frames. Exception messages are left out on purpose: database
 * messages can quote the values that failed (an email address, a name), and nothing personal may reach the log.
 */
internal fun ApplicationCall.logFailure(failure: Throwable) =
    logFailure("${request.httpMethod.value} ${routeShape(request.path())}", failure)

private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * The path with every ID shown as `{id}` and every other non-word segment as `{secret}`: no member, event or team
 * IDs, and never the calendar feed secret (`/api/v1/calendar/{secret}`), which the API promises not to log.
 */
internal fun routeShape(path: String): String = path.split('/').joinToString("/") { segment ->
    when {
        uuid.matches(segment) -> "{id}"
        segment.isEmpty() || Regex("^[a-z][a-z-]*$").matches(segment) || Regex("^v[0-9]+$").matches(segment) -> segment
        else -> "{secret}"
    }
}

/** The same for work outside a request (mail and push workers, cleanup), with a short name for the job. */
internal fun logFailure(where: String, failure: Throwable) {
    failures.error("{} failed: {}", where, describeFailure(failure))
}

internal fun describeFailure(failure: Throwable): String = buildString {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < 5) {
        if (depth > 0) append(" <- caused by ")
        append(current::class.qualifiedName ?: current::class.java.name)
        val state = (current as? SQLException)?.sqlState ?: (current as? DataAccessException)?.sqlState()
        if (state != null) append(" [SQL state ").append(state).append(']')
        current.stackTrace.filter { it.className.startsWith("org.openclub") }.take(6).forEach {
            append("\n    at ").append(it.className).append('.').append(it.methodName).append(':').append(it.lineNumber)
        }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
}

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
        if (!isSameOriginWrite(actor, origin)) {
            return refuse(HttpStatusCode.Forbidden, "forbidden")
        }
        if (capability != null &&
            !database.onDatabaseThread { ClubCapabilityAccess(it).has(actor.accountId, capability) }) {
            return refuse(HttpStatusCode.Forbidden, "forbidden")
        }
        block(actor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure(failure)
        refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}

/**
 * A write must carry exactly our Origin and exactly the session's CSRF token. The token is compared in constant
 * time so its value cannot be guessed from response timing.
 */
internal fun ApplicationCall.isSameOriginWrite(actor: ActiveSession, origin: String): Boolean {
    if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin)) return false
    val token = request.headers.getAll("X-CSRF-Token")?.singleOrNull() ?: return false
    return MessageDigest.isEqual(token.toByteArray(StandardCharsets.UTF_8), actor.csrfToken.toByteArray(StandardCharsets.UTF_8))
}

internal suspend fun ApplicationCall.respondJson(body: JsonObject) = respondText(body.toString(), ContentType.Application.Json)
