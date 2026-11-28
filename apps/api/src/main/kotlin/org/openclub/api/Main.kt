package org.openclub.api

import org.openclub.api.auth.*
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.RetentionCleanup
import org.openclub.api.club.installSingleClubRoutes
import org.openclub.api.club.installClubNameRoutes
import org.openclub.api.club.installClubPlayerPolicyRoutes
import org.openclub.api.members.PublicMemberNames
import org.openclub.api.members.installMemberHttpRoutes
import org.openclub.api.members.installGrantHttpRoutes
import org.openclub.api.members.installGuardianHttpRoutes
import org.openclub.api.members.installMyProfileHttpRoutes
import org.openclub.api.calendar.installCalendarHttpRoutes
import org.openclub.api.notifications.MailConfig
import org.openclub.api.notifications.MailWorker
import org.openclub.api.notifications.Notifier
import org.openclub.api.notifications.SmtpMailer
import org.openclub.api.notifications.PushConfig
import org.openclub.api.notifications.HttpPushSender
import org.openclub.api.notifications.installNotificationHttpRoutes
import org.openclub.api.notifications.installPushHttpRoutes
import org.openclub.api.venues.installVenueHttpRoutes
import org.openclub.api.fees.installHelperDutyHttpRoutes
import org.openclub.api.join.installJoinRequestHttpRoutes
import org.openclub.api.teams.installAbsenceHttpRoutes
import org.openclub.api.teams.installRideHttpRoutes
import org.openclub.api.teams.installEventShiftHttpRoutes
import org.openclub.api.teams.installEventAnswerHttpRoutes
import org.openclub.api.teams.installTeamEventHttpRoutes
import org.openclub.api.fees.installFeeHttpRoutes
import org.openclub.api.fees.installQrBillHttpRoutes
import org.openclub.api.members.installMemberListHttpRoutes
import org.openclub.api.announcements.installAnnouncementHttpRoutes
import org.openclub.api.members.installMemberDetailsHttpRoute
import org.openclub.api.members.installMemberErasureHttpRoute
import org.openclub.api.members.installMemberInvitationHttpRoutes
import org.openclub.api.members.installMemberRoleHttpRoutes
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
    // Email (OpenAPI 1.19.0): only with an SMTP server, a database and a browser origin for the links in messages.
    val mail = MailConfig.fromEnvironment(environment)
    require(mail == null || (database != null && authConfig != null)) { "Email notifications need the database and OIDC settings" }
    // Push (OpenAPI 1.33.0): optional, alone or with email.
    val push = PushConfig.fromEnvironment(environment)
    require(push == null || (database != null && authConfig != null)) { "Push notifications need the database and OIDC settings" }
    val notifier = if ((mail != null || push != null) && authConfig != null) Notifier(mail, authConfig.browserOrigin, push) else Notifier.DISABLED
    var cleanup: Job? = null
    var retention: Job? = null
    var mailer: Job? = null
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
        if (database != null) {
            retention = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                RetentionCleanup.run(database)
            }
        }
        if ((mail != null || push != null) && database != null) {
            mailer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                MailWorker.run(database, notifier, mail?.let(::SmtpMailer), push?.let { HttpPushSender(it) })
            }
        }
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            module(login, authConfig?.cookies,
                authConfig?.let { PostgresBrowserSessions(requireNotNull(database)) },
                authConfig?.browserOrigin,
                database = database, notifier = notifier)
        }.start(wait = true)
    } finally {
        runBlocking { cleanup?.cancelAndJoin(); retention?.cancelAndJoin(); mailer?.cancelAndJoin() }
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
    notifier: Notifier = Notifier.DISABLED,
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
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.respondText("""{"code":"unavailable","message":"Directory unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
        // The club's name for visitors and not-yet-members (OpenAPI 1.35.0): the name only, nothing else.
        get("/api/v1/public/club") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val unavailable = """{"code":"unavailable","message":"Club unavailable"}"""
            try {
                val found = database?.onDatabaseThread { db ->
                    listOfNotNull(db.fetchOne("SELECT name FROM clubs LIMIT 1")?.get(0, String::class.java))
                }
                when {
                    found == null -> call.respondText(unavailable, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    found.isEmpty() -> call.respondText("""{"code":"not_found","message":"No club yet"}""",
                        ContentType.Application.Json, HttpStatusCode.NotFound)
                    else -> call.respondText(kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive(found.single()))
                    }.toString(), ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                call.logFailure(failure)
                call.respondText(unavailable, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
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
            } catch (failure: Exception) {
                logFailure("Readiness check", failure)
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
        installClubNameRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installClubPlayerPolicyRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMemberHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installTeamHttpRoutes(sessions, requireNotNull(cookiePolicy), database, requireNotNull(publicOrigin))
        installTeamMemberHttpRoutes(sessions, requireNotNull(cookiePolicy), database, requireNotNull(publicOrigin))
        installGrantHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMemberRoleHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMemberInvitationHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, authAdmission)
        installMemberErasureHttpRoute(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMemberDetailsHttpRoute(sessions, requireNotNull(cookiePolicy), database)
        installGuardianHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMyProfileHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installTeamEventHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier)
        installEventAnswerHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier)
        installEventShiftHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installAbsenceHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installRideHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier)
        installJoinRequestHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installCalendarHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installFeeHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installQrBillHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installMemberListHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installAnnouncementHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier)
        installNotificationHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier)
        installVenueHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installHelperDutyHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database)
        installPushHttpRoutes(sessions, requireNotNull(cookiePolicy), requireNotNull(publicOrigin), database, notifier.push)
    }
}
