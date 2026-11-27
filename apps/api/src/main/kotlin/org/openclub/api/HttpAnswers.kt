package org.openclub.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations

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
    } catch (_: Exception) {
        refuse(HttpStatusCode.ServiceUnavailable, "unavailable")
    }
}
