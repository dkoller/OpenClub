package org.openclub.api

import org.openclub.api.auth.*
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.club.installSingleClubRoutes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    var cleanup: Job? = null
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
        if (login != null) {
            cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                AuthRecordCleanup.run(requireNotNull(database))
            }
        }
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            module(login, authConfig?.cookies,
                authConfig?.let { PostgresBrowserSessions(requireNotNull(database)) },
                authConfig?.browserOrigin,
                database = database)
        }.start(wait = true)
    } finally {
        runBlocking { cleanup?.cancelAndJoin() }
        database?.close()
    }
}

internal fun Application.module(
    login: BrowserLoginOperations? = null,
    cookiePolicy: AuthCookiePolicy? = null,
    sessions: BrowserSessionOperations? = null,
    publicOrigin: String? = null,
    authAdmission: AuthAdmissionLimiter = AuthAdmissionLimiter(),
    database: DatabaseRuntime? = null,
) {
    routing {
        // Liveness only; this endpoint does not imply database readiness.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
    if (login != null) installBrowserLogin(login, requireNotNull(cookiePolicy), authAdmission)
    if (sessions != null) installSessionRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin))
    if (sessions != null && database != null) installSingleClubRoutes(sessions, requireNotNull(cookiePolicy), database)
}
