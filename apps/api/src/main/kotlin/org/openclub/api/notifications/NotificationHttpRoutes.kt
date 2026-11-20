package org.openclub.api.notifications

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.members.ActorSql
import org.openclub.api.members.guarded
import org.openclub.api.members.readSession
import org.openclub.api.members.refuse
import org.openclub.api.members.respondJson
import org.openclub.api.receiveJsonObject
import org.openclub.api.requiredBoolean
import org.openclub.api.requiredString
import java.util.UUID

/** A member's own email notification settings (OpenAPI 1.19.0); a missing row means everything on, in German. */
internal class NotificationSettings(private val database: DSLContext) {
    data class Settings(
        val eventChanges: Boolean, val newEvents: Boolean, val announcements: Boolean, val shiftReminders: Boolean,
        val language: String, val hasEmail: Boolean,
    )

    /** Null when the actor is not an active, verified member. */
    fun read(actor: UUID): Settings? = database.fetchOne(
        """SELECT COALESCE(s.event_changes, TRUE), COALESCE(s.new_events, TRUE), COALESCE(s.announcements, TRUE),
                  COALESCE(s.shift_reminders, TRUE), COALESCE(s.language, 'de'), me.email IS NOT NULL
           FROM members me LEFT JOIN notification_settings s ON s.member_id = me.id
           WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}""", actor)?.let {
        Settings(it.get(0, Boolean::class.java), it.get(1, Boolean::class.java), it.get(2, Boolean::class.java),
            it.get(3, Boolean::class.java), it.get(4, String::class.java), it.get(5, Boolean::class.java))
    }

    /** Replaces the actor's settings; false for a non-member. */
    fun replace(actor: UUID, settings: Settings): Boolean = database.execute(
        """INSERT INTO notification_settings (member_id, event_changes, new_events, announcements, shift_reminders, language)
           SELECT me.id, ?, ?, ?, ?, ? FROM members me WHERE me.account_id = ? AND ${ActorSql.activeVerified("me")}
           ON CONFLICT (member_id) DO UPDATE SET event_changes = EXCLUDED.event_changes, new_events = EXCLUDED.new_events,
               announcements = EXCLUDED.announcements, shift_reminders = EXCLUDED.shift_reminders,
               language = EXCLUDED.language, updated_at = now()""",
        settings.eventChanges, settings.newEvents, settings.announcements, settings.shiftReminders, settings.language, actor) == 1
}

/**
 * `GET` and `PUT /api/v1/me/notifications` (OpenAPI 1.19.0, ADR-0008 addendum 23). Session (401); for the change
 * exact Origin and CSRF (403); an active verified member (403); then the body (400). `emailEnabled` says whether this
 * installation sends email at all, `hasEmail` whether the member has an address. Not audited (the member's own
 * preference).
 */
internal fun Application.installNotificationHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    notifier: Notifier,
) {
    routing {
        get("/api/v1/me/notifications") {
            call.readSession(sessions, cookies) { actor ->
                val settings = database.onDatabaseThread { NotificationSettings(it).read(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("eventChanges", settings.eventChanges)
                    put("newEvents", settings.newEvents)
                    put("announcements", settings.announcements)
                    put("shiftReminders", settings.shiftReminders)
                    put("language", JsonPrimitive(settings.language))
                    put("emailEnabled", notifier.enabled)
                    put("hasEmail", settings.hasEmail)
                })
            }
        }
        put("/api/v1/me/notifications") {
            call.guarded(sessions, cookies, origin, database, null) { actor ->
                if (database.onDatabaseThread { NotificationSettings(it).read(actor.accountId) } == null) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val settings = try {
                    val body = call.receiveJsonObject(setOf("eventChanges", "newEvents", "announcements", "shiftReminders", "language"))
                    val language = body.requiredString("language")
                    require(language in setOf("de", "en", "fr", "it")) { "language" }
                    NotificationSettings.Settings(body.requiredBoolean("eventChanges"), body.requiredBoolean("newEvents"),
                        body.requiredBoolean("announcements"), body.requiredBoolean("shiftReminders"), language, hasEmail = false)
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                if (!database.onDatabaseThread { NotificationSettings(it).replace(actor.accountId, settings) }) {
                    return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }
}
