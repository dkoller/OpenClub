package org.openclub.api

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val environment = System.getenv()
    val authConfig = OidcRuntimeConfig.fromEnvironment(environment)
    require(authConfig == null || !environment["DB_URL"].isNullOrBlank()) {
        "OIDC authentication requires a PostgreSQL session database"
    }
    val database = DatabaseRuntime.openIfConfigured(environment)
    try {
        // Fail startup rather than exposing partially working auth routes.
        // Discovery runs before starting Netty, not on a request event loop.
        val login = authConfig?.let { config ->
            PostgresBrowserLogin(
                requireNotNull(database),
                config,
                OidcProviderDiscovery(config.settings).load(),
            )
        }
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            module(login, authConfig?.cookies)
        }.start(wait = true)
    } finally {
        database?.close()
    }
}

fun Application.module(
    login: BrowserLoginOperations? = null,
    cookiePolicy: AuthCookiePolicy? = null,
) {
    routing {
        // Liveness only; this endpoint does not imply database readiness.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
    if (login != null) installBrowserLogin(login, requireNotNull(cookiePolicy))
}
