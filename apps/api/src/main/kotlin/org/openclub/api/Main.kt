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
    // Fail closed on invalid explicit OIDC settings. This does not enable auth routes.
    val authConfigured = OidcRuntimeConfig.fromEnvironment(environment) != null
    require(!authConfigured || !environment["DB_URL"].isNullOrBlank()) {
        "OIDC authentication requires a PostgreSQL session database"
    }
    val database = DatabaseRuntime.openIfConfigured(environment)
    try {
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            module()
        }.start(wait = true)
    } finally {
        database?.close()
    }
}

fun Application.module() {
    routing {
        // Liveness only; this endpoint does not imply database readiness.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}
