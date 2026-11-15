package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

internal interface BrowserSessionOperations {
    suspend fun read(handle: String?): ActiveSession?
    suspend fun logout(handle: String?, csrfToken: String?): LogoutResult
}

internal class PostgresBrowserSessions(private val database: DatabaseRuntime) : BrowserSessionOperations {
    override suspend fun read(handle: String?): ActiveSession? =
        database.onDatabaseThread { AuthSessions(it).find(handle) }

    override suspend fun logout(handle: String?, csrfToken: String?): LogoutResult =
        database.onDatabaseThread { AuthSessions(it).logout(handle, csrfToken) }
}

internal fun Application.installSessionRoutes(
    operations: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
) {
    routing {
        get("/api/v1/auth/me") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val session = operations.read(call.request.cookies[cookies.sessionName])
                if (session == null) {
                    call.respondText("""{"code":"unauthenticated","message":"Authentication required"}""",
                        ContentType.Application.Json, HttpStatusCode.Unauthorized)
                } else {
                    // Both fields are server-owned UUID/base64url values, never arbitrary user text.
                    call.respondText("""{"accountId":"${session.accountId}","csrfToken":"${session.csrfToken}"}""",
                        ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable","message":"Session storage unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
        post("/api/v1/auth/logout") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val origins = call.request.headers.getAll(HttpHeaders.Origin)
            if (origins != listOf(origin)) {
                call.respondText("""{"code":"forbidden","message":"Origin rejected"}""",
                    ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }
            val types = call.request.headers.getAll(HttpHeaders.ContentType)
            val json = types?.singleOrNull()?.let {
                runCatching { ContentType.parse(it).withoutParameters() == ContentType.Application.Json }.getOrDefault(false)
            } ?: false
            // The contract requires an empty JSON object; cap bytes before allocating request text.
            if (!json || call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > 256 } == true) {
                call.respondText("""{"code":"invalid","message":"Expected an empty JSON object"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            try {
                val channel = call.receiveChannel()
                val bytes = ByteArray(257)
                var size = 0
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                val body = bytes.copyOf(size).toString(Charsets.UTF_8)
                if (body.length > 256 || !Regex("""[ \t\r\n]*\{[ \t\r\n]*}[ \t\r\n]*""").matches(body)) {
                    call.respondText("""{"code":"invalid","message":"Expected an empty JSON object"}""",
                        ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                when (operations.logout(call.request.cookies[cookies.sessionName],
                    call.request.headers.getAll("X-CSRF-Token")?.singleOrNull())) {
                    LogoutResult.REVOKED -> {
                        call.response.cookies.append(cookies.clearSession())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    LogoutResult.UNAUTHENTICATED -> call.respondText(
                        """{"code":"unauthenticated","message":"Authentication required"}""",
                        ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    LogoutResult.FORBIDDEN -> call.respondText(
                        """{"code":"forbidden","message":"CSRF token rejected"}""",
                        ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable","message":"Session storage unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
