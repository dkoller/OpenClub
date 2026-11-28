package org.openclub.api.members

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.openclub.api.auth.ActiveSession
import org.openclub.api.auth.AuthCookiePolicy
import org.openclub.api.auth.BrowserSessionOperations
import org.openclub.api.auth.LogoutResult
import org.openclub.api.database.DatabaseRuntime
import org.openclub.api.module
import java.util.UUID
import org.openclub.api.checkContract

/** Member list export and import (OpenAPI 1.13.0): editors only, audited, preview before commit, duplicates skipped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberListHttpTest {
    private var database: DatabaseRuntime? = null
    private val club = UUID.randomUUID()
    private val origin = "http://localhost:5173"
    private val csrf = "x".repeat(43)
    private val cookies = AuthCookiePolicy.localDevelopment()
    private val accounts = mutableMapOf<String, UUID>()
    private val memberOf = mutableMapOf<String, UUID>()
    private val archived = UUID.randomUUID()
    private val tag = "Csv" + UUID.randomUUID().toString().take(6)
    private val sessions = object : BrowserSessionOperations {
        override suspend fun read(handle: String?): ActiveSession? = accounts[handle]?.let { ActiveSession(it, csrf) }
        override suspend fun logout(handle: String?, csrfToken: String?) = LogoutResult.UNAUTHENTICATED
    }

    private fun <T> sql(block: (DSLContext) -> T): T = runBlocking { requireNotNull(database).onDatabaseThread(block) }
    private fun count(query: String, vararg args: Any): Int = sql { (it.fetchValue(query, *args) as Number).toInt() }

    @BeforeAll
    fun fixture() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        sql { tx ->
            tx.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'List HTTP Club', ?)", club, "list-http-$club")
            for (handle in listOf("editor", "plain")) {
                val account = UUID.randomUUID().also { accounts[handle] = it }
                tx.execute("INSERT INTO accounts (id) VALUES (?)", account)
                tx.execute(
                    """INSERT INTO account_identities (id, account_id, issuer, subject)
                       VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "list-$account")
                memberOf[handle] = UUID.randomUUID().also {
                    tx.execute(
                        """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, ?, ?, ?, DATE '1980-01-01', 'l@example.invalid', '+41000000040')""", it, account, handle, tag)
                }
            }
            tx.execute("INSERT INTO club_capability_grants (member_id, capability) VALUES (?, 'EDIT_MEMBERS')", memberOf.getValue("editor"))
            tx.execute(
                """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, archived_at)
                   VALUES (?, 'Archived', ?, DATE '1975-01-01', 'a@example.invalid', '+41000000041', now())""", archived, tag)
        }
    }

    @AfterAll
    fun cleanup() {
        val db = database ?: return
        runBlocking {
            db.onDatabaseThread { tx ->
                tx.execute("DELETE FROM audit_events WHERE target_member_id IN (SELECT id FROM members WHERE last_name = ?)", tag)
                tx.execute("DELETE FROM members WHERE last_name = ?", tag)
                tx.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.values.toTypedArray())
                tx.execute("DELETE FROM clubs WHERE id = ?", club)
            }
        }
        db.close()
        database = null
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { checkContract(); module(sessions = sessions, cookiePolicy = cookies, publicOrigin = origin, database = requireNotNull(database)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.export(handle: String?, query: String = "?lang=de", site: String? = null) =
        client.request("/api/v1/members/export.csv$query") {
            method = HttpMethod.Get
            if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
            if (site != null) header("Sec-Fetch-Site", site)
        }

    private suspend fun ApplicationTestBuilder.upload(
        handle: String?, csv: String, mode: String = "preview", type: String = "text/csv; charset=utf-8", withCsrf: String? = csrf,
    ): HttpResponse = client.request("/api/v1/members/import?mode=$mode") {
        method = HttpMethod.Post
        if (handle != null) header(HttpHeaders.Cookie, "${cookies.sessionName}=$handle")
        header(HttpHeaders.Origin, origin)
        if (withCsrf != null) header("X-CSRF-Token", withCsrf)
        header(HttpHeaders.ContentType, type)
        setBody(csv)
    }

    private class Check { val failures = mutableListOf<String>()
        fun expect(label: String, response: HttpResponse, status: HttpStatusCode) {
            if (response.status != status) failures += "$label: expected $status but was ${response.status}"
            if (response.headers[HttpHeaders.CacheControl] != "no-store") failures += "$label: missing no-store"
        }
        fun done() = assertTrue(failures.isEmpty(), failures.joinToString("\n", "\n"))
    }

    @Test
    fun editorsExportTheActiveMembersAndEveryListedMemberIsAudited() = app {
        // A team name containing '|' and ';' arrives in one cell.
        val team = UUID.randomUUID()
        sql {
            it.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "$tag U12 | Girls; B")
            it.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, memberOf.getValue("editor"))
        }
        val response = try { export("editor") } finally { sql { it.execute("DELETE FROM teams WHERE id = ?", team) } }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"))
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
        val text = response.bodyAsText()
        assertTrue(text.startsWith("﻿\"Vorname\";\"Nachname\";\"Geburtsdatum\""))
        assertTrue(text.contains("\"editor\";\"$tag\";\"01.01.1980\";\"l@example.invalid\";\"'+41000000040\""))
        assertTrue(text.contains("\"$tag U12 | Girls; B\"\r\n"), "the team name is one cell")
        assertFalse(text.contains("Archived"), "archived members are not listed")
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_LISTED' AND target_member_id = ?", memberOf.getValue("plain")))
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_LISTED' AND target_member_id = ?", archived))

        val before = count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_LISTED'")
        val check = Check()
        check.expect("no session", export(null), HttpStatusCode.Unauthorized)
        check.expect("no grant", export("plain"), HttpStatusCode.Forbidden)
        check.expect("cross-site", export("editor", site = "cross-site"), HttpStatusCode.Forbidden)
        check.expect("unknown language", export("editor", "?lang=es"), HttpStatusCode.BadRequest)
        check.expect("unknown parameter", export("editor", "?lang=de&all=1"), HttpStatusCode.BadRequest)
        check.done()
        assertEquals(before, count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'MEMBER_LISTED'"), "refusals list nobody")
    }

    @Test
    fun anImportIsPreviewedThenCreatesOnlyNewValidRows() = app {
        val csv = """
            Vorname;Nachname;Geburtsdatum;E-Mail;Telefon;Spieler
            New;$tag;3.2.2015;;;ja
            Grown;$tag;1.1.1990;g@example.invalid;+41 79 000 00 00;nein
            plain;$tag;01.01.1980;p@example.invalid;+41000000042;nein
            archived;$tag;1975-01-01;q@example.invalid;+41000000043;nein
            Nocontacts;$tag;1.1.1990;;;nein
        """.trimIndent()
        val check = Check()
        check.expect("no session", upload(null, csv), HttpStatusCode.Unauthorized)
        check.expect("no CSRF", upload("editor", csv, withCsrf = null), HttpStatusCode.Forbidden)
        check.expect("no grant, bad file", upload("plain", "x", type = "text/plain"), HttpStatusCode.Forbidden)
        check.expect("wrong type", upload("editor", csv, type = "application/json"), HttpStatusCode.BadRequest)
        check.expect("bad mode", upload("editor", csv, mode = "apply"), HttpStatusCode.BadRequest)
        check.expect("missing column", upload("editor", "Vorname;Nachname\nA;B\n"), HttpStatusCode.BadRequest)
        check.done()

        val preview = upload("editor", csv)
        assertEquals(HttpStatusCode.OK, preview.status)
        val body = Json.parseToJsonElement(preview.bodyAsText()).jsonObject
        assertEquals(listOf("OK", "OK", "DUPLICATE", "DUPLICATE", "INVALID"),
            body.getValue("rows").jsonArray.map { it.jsonObject.getValue("status").jsonPrimitive.content })
        assertEquals("""{"ok":2,"duplicate":2,"invalid":1}""", body.getValue("counts").toString())
        assertEquals(listOf("ADULT_CONTACTS"), body.getValue("rows").jsonArray.last().jsonObject.getValue("problems").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, body.getValue("created"))
        assertEquals(0, count("SELECT COUNT(*) FROM members WHERE last_name = ? AND first_name IN ('New', 'Grown')", tag), "a preview changes nothing")

        val commit = Json.parseToJsonElement(upload("editor", csv, mode = "commit").bodyAsText()).jsonObject
        assertEquals("2", commit.getValue("created").jsonPrimitive.content)
        assertEquals(1, count("SELECT COUNT(*) FROM members WHERE last_name = ? AND first_name = 'New' AND is_player AND date_of_birth = DATE '2015-02-03'", tag))
        assertEquals(1, count("SELECT COUNT(*) FROM members WHERE last_name = ? AND first_name = 'Grown' AND phone = '+41 79 000 00 00'", tag))
        assertEquals(2, count("""SELECT COUNT(*) FROM audit_events e JOIN members m ON m.id = e.target_member_id
                                 WHERE e.event_type = 'MEMBER_CREATED' AND e.actor_account_id = ? AND m.last_name = ?""", accounts.getValue("editor"), tag))
        // Importing the same file again creates nothing: the new members are now duplicates.
        val again = Json.parseToJsonElement(upload("editor", csv, mode = "commit").bodyAsText()).jsonObject
        assertEquals("0", again.getValue("created").jsonPrimitive.content)
        assertEquals(1, count("SELECT COUNT(*) FROM members WHERE last_name = ? AND first_name = 'New'", tag))
    }
}
