package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.openclub.api.logFailure

/** HTTP boundary: all identity and session operations are server-owned. */
internal interface BrowserLoginOperations {
    suspend fun begin(): PendingOidcLogin
    suspend fun finish(state: String, browserBinding: String?, code: String, previousSession: String?): IssuedSession?
}

internal fun Application.installBrowserLogin(
    operations: BrowserLoginOperations,
    cookies: AuthCookiePolicy,
    admission: AuthAdmissionLimiter = AuthAdmissionLimiter(),
) {
    routing {
        get("/api/v1/auth/login") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (!admission.allowLogin()) {
                call.response.headers.append(HttpHeaders.RetryAfter, "60")
                call.respondText("""{"code":"rate_limited","message":"Authentication rate limited"}""",
                    ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return@get
            }
            try {
                val pending = operations.begin()
                call.response.cookies.append(cookies.loginBinding(pending.browserBinding))
                call.respondRedirect(pending.authorization.url.toASCIIString(), permanent = false)
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.respondText("""{"code":"unavailable","message":"Authentication unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
        get("/api/v1/auth/callback") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            // Always discard the one-time browser binding, including on provider errors.
            call.response.cookies.append(cookies.clearLogin())
            if (!admission.allowCallback()) {
                call.response.headers.append(HttpHeaders.RetryAfter, "60")
                call.respondText("""{"code":"rate_limited","message":"Authentication rate limited"}""",
                    ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return@get
            }
            val query = call.request.queryParameters
            val states = query.getAll("state")
            val codes = query.getAll("code")
            val errors = query.getAll("error")
            val state = states?.singleOrNull()
            val code = codes?.singleOrNull()
            if (!errors.isNullOrEmpty() || state.isNullOrBlank() || code.isNullOrBlank() ||
                code.length > 4096 || code.any(Char::isISOControl)
            ) {
                call.respondText("""{"code":"invalid","message":"Invalid authentication response"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            try {
                val issued = operations.finish(
                    state, call.request.cookies[cookies.loginName],
                    code, call.request.cookies[cookies.sessionName],
                )
                if (issued == null) {
                    call.respondText("""{"code":"unauthenticated","message":"Authentication rejected"}""",
                        ContentType.Application.Json, HttpStatusCode.Unauthorized)
                } else {
                    call.response.cookies.append(cookies.session(issued.handle))
                    call.response.headers.append(HttpHeaders.Location, "/")
                    call.respond(HttpStatusCode.SeeOther)
                }
            } catch (failure: Exception) {
                // No token, nonce, code or provider error is ever reflected to the client, nor logged:
                // the log gets only the exception classes and stack frames.
                call.logFailure(failure)
                call.respondText("""{"code":"unavailable","message":"Authentication unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
