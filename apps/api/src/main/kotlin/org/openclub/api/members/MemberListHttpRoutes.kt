package org.openclub.api.members

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.exception.DataAccessException
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.database.rejectedTheData
import org.openclub.api.members.ClubCapabilityAccess.Capability
import org.openclub.api.readBounded
import java.time.LocalDate
import org.openclub.api.refuse
import org.openclub.api.readSession

/** At most 512 KiB of CSV, enough for 1,000 members with every column. */
private const val MAX_CSV_BYTES = 512 * 1024

/**
 * Member list export and import for `EDIT_MEMBERS` (OpenAPI 1.13.0, ADR-0008 addendum 17).
 * The export answers session (401), a cross-site request (403), the grant (403), then `lang` (400), and is a
 * `no-store` attachment. The import answers session (401), exact Origin and CSRF (403), the grant (403), then the
 * mode, content type and file (400); `mode=preview` changes nothing, `mode=commit` creates the rows the preview marks OK.
 */
internal fun Application.installMemberListHttpRoutes(
    sessions: BrowserSessionOperations,
    cookies: AuthCookiePolicy,
    origin: String,
    database: DatabaseRuntime,
) {
    routing {
        get("/api/v1/members/export.csv") {
            call.readSession(sessions, cookies) { actor ->
                if (call.isCrossSiteRequest()) return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                if (!database.onDatabaseThread { ClubCapabilityAccess(it).has(actor.accountId, Capability.EDIT_MEMBERS) }) {
                    return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                }
                val query = call.request.queryParameters
                val language = query["lang"] ?: "en"
                if (language !in MemberCsv.languages || query.names().any { it != "lang" } || (query.getAll("lang")?.size ?: 0) > 1) {
                    return@readSession call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val csv = database.onDatabaseThread { MemberListTransfer(it).export(actor.accountId, language) }
                    ?: return@readSession call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "members-${LocalDate.now()}.csv").toString())
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
            }
        }
        post("/api/v1/members/import") {
            call.guarded(sessions, cookies, origin, database, Capability.EDIT_MEMBERS) { actor ->
                val query = call.request.queryParameters
                val commit = when (query["mode"]) { "preview" -> false; "commit" -> true; else -> null }
                val type = call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull()
                    ?.let { runCatching { ContentType.parse(it).withoutParameters() }.getOrNull() }
                if (commit == null || query.names().any { it != "mode" } || type != ContentType.Text.CSV) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val table = try {
                    MemberCsv.parse(MemberCsv.decode(readBounded(call.receiveChannel(), MAX_CSV_BYTES)))
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                }
                val result = try {
                    database.onDatabaseThread { MemberListTransfer(it).importRows(actor.accountId, table, commit) }
                } catch (_: IllegalArgumentException) {
                    return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                } catch (failure: DataAccessException) {
                    if (failure.rejectedTheData()) return@guarded call.refuse(HttpStatusCode.BadRequest, "invalid")
                    throw failure
                } ?: return@guarded call.refuse(HttpStatusCode.Forbidden, "forbidden")
                call.respondJson(buildJsonObject {
                    put("rows", buildJsonArray {
                        result.rows.forEach { item -> add(buildJsonObject {
                            put("line", item.row.line)
                            put("firstName", item.row.firstName)
                            put("lastName", item.row.lastName)
                            put("status", item.status.name)
                            put("problems", buildJsonArray { item.row.problems.sorted().forEach { add(JsonPrimitive(it.name)) } })
                        }) }
                    })
                    put("counts", buildJsonObject {
                        put("ok", result.rows.count { it.status == MemberListTransfer.Status.OK })
                        put("duplicate", result.rows.count { it.status == MemberListTransfer.Status.DUPLICATE })
                        put("invalid", result.rows.count { it.status == MemberListTransfer.Status.INVALID })
                    })
                    put("created", result.created?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
    }
}
