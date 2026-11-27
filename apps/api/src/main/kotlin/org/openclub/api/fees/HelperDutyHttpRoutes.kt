package org.openclub.api.fees

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.members.guarded
import org.openclub.api.readSession
import org.openclub.api.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject

/**
 * Helper duty (OpenAPI 1.32.0, ADR-0008 addendum 36). `GET /v1/me/helper-duty` for any active member;
 * `GET /v1/helper-duty` and `PUT /v1/club/helper-duty` for `MANAGE_FEES` holders. Not audited; never cacheable.
 */
internal fun Application.installHelperDutyHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/me/helper-duty") {
            call.readSession(sessions, cookies) { actor ->
                val (settings, season, mine) = database.onDatabaseThread { db ->
                    val duty = HelperDuty(db)
                    val settings = duty.settings()
                    val season = duty.season(settings)
                    Triple(settings, season, duty.mine(actor.accountId, season))
                }
                if (mine == null) return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("quota", if (mine.first) settings.quota else 0)
                    put("done", mine.second)
                    put("seasonFrom", season.from.toString()); put("seasonTo", season.to.toString())
                })
            }
        }
        get("/api/v1/helper-duty") {
            call.readSession(sessions, cookies) { actor ->
                val (settings, season, rows) = database.onDatabaseThread { db ->
                    val duty = HelperDuty(db)
                    val settings = duty.settings()
                    val season = duty.season(settings)
                    Triple(settings, season, duty.overview(actor.accountId, season))
                }
                if (rows == null) return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("quota", settings.quota)
                    put("seasonStartMonth", settings.seasonStartMonth)
                    put("seasonFrom", season.from.toString()); put("seasonTo", season.to.toString())
                    put("items", buildJsonArray {
                        rows.forEach { add(buildJsonObject {
                            put("memberId", it.memberId.toString()); put("displayName", it.displayName); put("done", it.done)
                            put("owed", (settings.quota - it.done).coerceAtLeast(0))
                        }) }
                    })
                })
            }
        }
        put("/api/v1/club/helper-duty") {
            call.guarded(sessions, cookies, origin, database, Capability.MANAGE_FEES) { actor ->
                val settings = try {
                    val body = call.receiveJsonObject(setOf("quota", "seasonStartMonth"))
                    fun int(name: String, range: IntRange): Int {
                        val value = body[name] as? JsonPrimitive ?: throw IllegalArgumentException(name)
                        require(!value.isString)
                        return value.content.toIntOrNull()?.takeIf { it in range } ?: throw IllegalArgumentException(name)
                    }
                    HelperDuty.Settings(int("quota", 0..50), int("seasonStartMonth", 1..12))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                if (!database.onDatabaseThread { HelperDuty(it).replace(actor.accountId, settings) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }
}
