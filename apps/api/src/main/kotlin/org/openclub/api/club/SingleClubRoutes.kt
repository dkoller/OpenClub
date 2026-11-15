package org.openclub.api.club

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import java.util.UUID

/** One installation has at most one club. Never accept an arbitrary tenant ID. */
internal fun Application.installSingleClubRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/club") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val session = sessions.read(call.request.cookies[cookies.sessionName])
                if (session == null) {
                    call.respondText("""{"code":"unauthenticated","message":"Authentication required"}""",
                        ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return@get
                }
                val clubId = database.onDatabaseThread { db ->
                    db.fetchOne("""SELECT c.id FROM clubs c WHERE EXISTS (
                        SELECT 1 FROM members m WHERE m.account_id = ?)""", session.accountId)
                        ?.get("id", UUID::class.java)
                }
                if (clubId == null) {
                    call.respondText("""{"code":"forbidden","message":"Club membership required"}""",
                        ContentType.Application.Json, HttpStatusCode.Forbidden)
                } else {
                    call.respondText("""{"id":"$clubId"}""", ContentType.Application.Json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondText("""{"code":"unavailable","message":"Club lookup unavailable"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
