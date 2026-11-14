package org.openclub.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.cookies
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** HTTP boundary: all identity and session operations are server-owned. */
internal interface BrowserLoginOperations {
    suspend fun begin(): PendingOidcLogin
    suspend fun finish(state: String, browserBinding: String?, code: String, previousSession: String?): IssuedSession?
}

internal fun Application.installBrowserLogin(
    operations: BrowserLoginOperations,
    cookies: AuthCookiePolicy,
) {
    routing {
        get("/api/v1/auth/login") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val pending = operations.begin()
                call.response.cookies.append(cookies.loginBinding(pending.browserBinding))
                call.respondRedirect(pending.authorization.url.toASCIIString(), permanent = false)
            } catch (_: Exception) {
                call.respondText("Authentication unavailable", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        get("/api/v1/auth/callback") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            // Always discard the one-time browser binding, including on provider errors.
            call.response.cookies.append(cookies.clearLogin())
            val query = call.request.queryParameters
            val states = query.getAll("state")
            val codes = query.getAll("code")
            val errors = query.getAll("error")
            if (!errors.isNullOrEmpty() || states?.size != 1 || codes?.size != 1 ||
                states.single().isBlank() || codes.single().isBlank() ||
                codes.single().length > 4096 || codes.single().any(Char::isISOControl)
            ) {
                call.respondText("Invalid authentication response", status = HttpStatusCode.BadRequest)
                return@get
            }
            try {
                val issued = operations.finish(
                    states.single(), call.request.cookies[cookies.loginName],
                    codes.single(), call.request.cookies[cookies.sessionName],
                )
                if (issued == null) {
                    call.respondText("Authentication rejected", status = HttpStatusCode.Unauthorized)
                } else {
                    call.response.cookies.append(cookies.session(issued.handle))
                    call.response.headers.append(HttpHeaders.Location, "/")
                    call.respond(HttpStatusCode.SeeOther)
                }
            } catch (_: Exception) {
                // No token, nonce, code or provider error is ever reflected to the client.
                call.respondText("Authentication unavailable", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
