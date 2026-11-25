package org.openclub.api.notifications

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import org.openclub.api.requiredString
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

/** A member's own push subscriptions and the messages their devices fetch when woken (OpenAPI 1.33.0). */
internal class PushSubscriptions(private val database: DSLContext) {
    data class Item(val kind: String, val subject: String, val createdAt: OffsetDateTime)

    private val me = "SELECT m.id FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}"

    fun count(actor: UUID): Int? = database.fetchOne(
        "SELECT (SELECT count(*) FROM push_subscriptions ps WHERE ps.member_id = me.id) FROM ($me) me", actor)
        ?.let { (it.get(0) as Number).toInt() }

    /** Adds or moves [endpoint] to the actor (a device belongs to whoever signed in last); at most [MAX] per member. */
    fun add(actor: UUID, endpoint: String): Boolean? = database.transactionResult { config ->
        val db = org.jooq.impl.DSL.using(config)
        val member = db.fetchOne("$me FOR UPDATE OF m", actor)?.get(0, UUID::class.java) ?: return@transactionResult null
        db.execute(
            """INSERT INTO push_subscriptions (id, member_id, endpoint) VALUES (?, ?, ?)
               ON CONFLICT (endpoint) DO UPDATE SET member_id = EXCLUDED.member_id, created_at = now()""",
            UUID.randomUUID(), member, endpoint)
        // Keep the newest few; older devices of this member must subscribe again.
        db.execute(
            """DELETE FROM push_subscriptions WHERE member_id = ? AND id NOT IN (
                 SELECT id FROM push_subscriptions WHERE member_id = ? ORDER BY created_at DESC, id LIMIT $MAX)""",
            member, member)
        true
    }

    fun remove(actor: UUID, endpoint: String): Boolean = database.fetchOne(me, actor)?.let { row ->
        database.execute("DELETE FROM push_subscriptions WHERE member_id = ? AND endpoint = ?", row.get(0, UUID::class.java), endpoint)
        true
    } ?: false

    /** The actor's messages queued after [since] in the last two days, newest first, at most 10: kind and subject. */
    fun inbox(actor: UUID, since: OffsetDateTime): List<Item>? {
        val member = database.fetchOne(me, actor)?.get(0, UUID::class.java) ?: return null
        return database.fetch(
            """SELECT kind, subject, created_at FROM email_outbox
               WHERE member_id = ? AND created_at > ?::timestamptz AND created_at > now() - INTERVAL '2 days'
               ORDER BY created_at DESC, id LIMIT 10""", member, since.toString()).map {
            Item(it.get("kind", String::class.java), it.get("subject", String::class.java),
                it.get("created_at", OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC))
        }
    }

    companion object {
        const val MAX = 5
    }
}

/**
 * `GET /api/v1/me/push`, `PUT` and `DELETE /api/v1/me/push-subscriptions` and `GET /api/v1/me/push-inbox` (OpenAPI
 * 1.33.0, ADR-0008 addendum 37). Session (401); for changes exact Origin and CSRF (403); an active membership (403);
 * then the body (400): an endpoint on one of the operator's push hosts. Not audited (the member's own device).
 */
internal fun Application.installPushHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
    push: PushConfig?,
) {
    routing {
        get("/api/v1/me/push") {
            call.readSession(sessions, cookies) { actor ->
                val count = database.onDatabaseThread { PushSubscriptions(it).count(actor.accountId) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("enabled", push != null)
                    put("publicKey", push?.publicKey?.let(::JsonPrimitive) ?: JsonNull)
                    put("devices", count)
                })
            }
        }
        put("/api/v1/me/push-subscriptions") { call.subscription(sessions, cookies, origin, database, push, add = true) }
        delete("/api/v1/me/push-subscriptions") { call.subscription(sessions, cookies, origin, database, push, add = false) }
        get("/api/v1/me/push-inbox") {
            call.readSession(sessions, cookies) { actor ->
                val query = call.request.queryParameters
                val since = try {
                    require(query.names().all { it == "since" } && (query.getAll("since")?.size ?: 0) <= 1)
                    query["since"]?.let(OffsetDateTime::parse) ?: OffsetDateTime.now().minusDays(2)
                } catch (_: IllegalArgumentException) {
                    return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (_: DateTimeParseException) {
                    return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val items = database.onDatabaseThread { PushSubscriptions(it).inbox(actor.accountId, since) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("items", buildJsonArray {
                        items.forEach { add(buildJsonObject {
                            put("kind", it.kind); put("subject", it.subject); put("createdAt", it.createdAt.toString())
                        }) }
                    })
                })
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.subscription(
    sessions: BrowserSessionOperations, cookies: AuthCookiePolicy, origin: String, database: DatabaseRuntime,
    push: PushConfig?, add: Boolean,
) = guarded(sessions, cookies, origin, database, null) { actor ->
    if (database.onDatabaseThread { PushSubscriptions(it).count(actor.accountId) } == null) {
        return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    }
    if (push == null) return@guarded refuse(HttpStatusCode.Conflict, "push_off")
    val endpoint = try {
        receiveJsonObject(setOf("endpoint")).requiredString("endpoint").takeIf(push::allows) ?: throw IllegalArgumentException("endpoint")
    } catch (_: IllegalArgumentException) {
        return@guarded refuse(HttpStatusCode.BadRequest, "invalid")
    }
    val done = database.onDatabaseThread {
        if (add) PushSubscriptions(it).add(actor.accountId, endpoint) == true else PushSubscriptions(it).remove(actor.accountId, endpoint)
    }
    if (!done) return@guarded refuse(HttpStatusCode.Forbidden, "forbidden")
    respondText("", status = HttpStatusCode.NoContent)
}
