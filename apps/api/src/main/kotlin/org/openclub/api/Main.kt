package org.openclub.api

import org.openclub.api.auth.*
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.club.installSingleClubRoutes
import org.openclub.api.members.PublicMemberNames
import org.openclub.api.members.installMemberHttpRoutes
import org.openclub.api.members.installGrantHttpRoutes
import org.openclub.api.teams.installTeamHttpRoutes
import org.openclub.api.teams.installTeamMemberHttpRoutes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
        // Anonymous names are deliberately separate from authenticated member administration.
        get("/api/v1/public/members") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val names = database?.onDatabaseThread { PublicMemberNames(it).listJson() }
                if (names == null) {
                    call.respondText("""{"code":"unavailable","message":"Directory unavailable"}""",
                        ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                } else {
                    call.respondText(names, ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable","message":"Directory unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
        // Liveness only; this endpoint does not imply database readiness.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        // The web proxy keeps /api; Docker health checks use the direct route.
        get("/api/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        // Database readiness, distinct from the process-only liveness probes.
        get("/api/ready") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val ready = try {
                database?.onDatabaseThread { it.fetchValue("SELECT 1") == 1 } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            call.respondText(
                if (ready) """{"status":"ready"}""" else """{"status":"unavailable"}""",
                ContentType.Application.Json,
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
    }
    if (login != null) installBrowserLogin(login, requireNotNull(cookiePolicy), authAdmission)
    if (sessions != null) installSessionRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin))
    if (sessions != null && database != null) {
        installSingleClubRoutes(sessions, requireNotNull(cookiePolicy), database)
        installMemberHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installTeamHttpRoutes(sessions, requireNotNull(cookiePolicy), database, requireNotNull(publicOrigin))
        installTeamMemberHttpRoutes(sessions, requireNotNull(cookiePolicy), database, requireNotNull(publicOrigin))
        installGrantHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
    }
}
