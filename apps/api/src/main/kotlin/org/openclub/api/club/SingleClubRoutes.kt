package org.openclub.api.club

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.MemberAccess

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
                val club = database.onDatabaseThread { db ->
                    val id = MemberAccess(db).authorizedClubId(session.accountId)
                    id?.let {
                        db.fetchOne("SELECT name, slug FROM clubs WHERE id = ?", it)?.let { row ->
                            Triple(it,
                                requireNotNull(row.get("name", String::class.java)),
                                requireNotNull(row.get("slug", String::class.java)))
                        }
                    }
                }
                if (club == null) {
                    call.respondText("""{"code":"forbidden","message":"Club membership required"}""",
                        ContentType.Application.Json, HttpStatusCode.Forbidden)
                } else {
                    call.respondText(buildJsonObject {
                        put("id", club.first.toString())
                        put("name", club.second)
                        put("slug", club.third)
                    }.toString(), ContentType.Application.Json)
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
