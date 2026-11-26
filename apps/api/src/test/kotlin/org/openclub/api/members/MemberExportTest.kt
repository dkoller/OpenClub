package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/** Operator export of one member's stored data (privacy proposal section 4). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberExportTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val club = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val training = UUID.randomUUID()
    private val match = UUID.randomUUID()
    private val subject = "export-subject-${UUID.randomUUID()}"

    @BeforeAll
    fun fixture() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        })
        Flyway.configure().dataSource(source).load().migrate()
        db = DSL.using(source, SQLDialect.POSTGRES)
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Export Club', ?)", club, "export-$club")
        db.execute("INSERT INTO accounts (id) VALUES (?)", account)
        db.execute(
            """INSERT INTO account_identities (id, account_id, issuer, subject)
               VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, subject)
        db.execute(
            """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, gender, is_player, role)
               VALUES (?, ?, 'Exporta', 'Person', DATE '1991-04-05', 'exporta@example.invalid', '+41000000011', 'f', TRUE, 'ADMIN')""",
            member, account)
        db.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth) VALUES (?, 'Other', 'Member', DATE '2013-02-02')""", other)
        db.execute("INSERT INTO teams (id, name) VALUES (?, 'Zebras'), (?, 'Aardvarks')", teamA, teamB)
        db.execute("INSERT INTO member_team_assignments VALUES (?, ?), (?, ?)", teamA, member, teamB, member)
        db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", teamA, other)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS'), (?, 'MANAGE_PERMISSIONS')", member, member)
        db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'MANAGE_TEAMS')", teamA, member)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_TEAMS')", other)
        // A past training where both were present, and a match where both are called up (since 1.34.0).
        db.execute("""INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at) VALUES
                      (?, ?, 'TRAINING', 'Export training', TIMESTAMPTZ '2026-10-01 16:00Z', TIMESTAMPTZ '2026-10-01 17:30Z'),
                      (?, ?, 'MATCH', 'Export match', TIMESTAMPTZ '2030-05-04 08:00Z', TIMESTAMPTZ '2030-05-04 10:00Z')""",
            training, teamA, match, teamA)
        db.execute("INSERT INTO event_presence (event_id, member_id, role) VALUES (?, ?, 'LEADER'), (?, ?, 'PARTICIPANT')",
            training, member, training, other)
        db.execute("INSERT INTO event_squads (event_id, meet_at, meeting_point) VALUES (?, TIMESTAMPTZ '2030-05-04 07:15Z', 'Bus stop')", match)
        db.execute("INSERT INTO squad_members (event_id, member_id) VALUES (?, ?), (?, ?)", match, member, match, other)
    }

    @AfterAll
    fun cleanup() {
        if (source == null) return
        db.execute("DELETE FROM audit_events WHERE target_member_id IN (?, ?)", member, other)
        db.execute("DELETE FROM members WHERE id IN (?, ?)", member, other)
        db.execute("DELETE FROM teams WHERE id IN (?, ?)", teamA, teamB)
        db.execute("DELETE FROM accounts WHERE id = ?", account)
        db.execute("DELETE FROM clubs WHERE id = ?", club)
        source?.close()
        source = null
    }

    private fun events(id: UUID) = db.fetchCount(DSL.table("audit_events"),
        DSL.field("event_type").eq("MEMBER_EXPORTED").and(DSL.field("target_member_id").eq(id)))

    @Test
    fun theExportHoldsTheMembersOwnStoredDataAndNothingAboutOtherPeopleOrTheLogin() {
        val at = Instant.parse("2026-11-18T12:00:00Z")
        val export = requireNotNull(MemberExport(db).exportAsOperator(member, at))
        val text = export.toString()
        // Round-trips as JSON.
        assertEquals(export, Json.parseToJsonElement(text))

        assertEquals("2026-11-18T12:00:00Z", export["exportedAt"]!!.jsonPrimitive.content)
        val m = export["member"]!!.jsonObject
        assertEquals(member.toString(), m["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("Exporta", "Person", "1991-04-05", "exporta@example.invalid", "+41000000011", "f", "ADMIN"),
            listOf("firstName", "lastName", "dateOfBirth", "email", "phone", "gender", "role").map { m[it]!!.jsonPrimitive.content })
        assertTrue(m["isPlayer"]!!.jsonPrimitive.boolean)
        assertTrue(m["hasLinkedLogin"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, m["archivedAt"], "an active member has no archive date")
        assertEquals(listOf("Aardvarks", "Zebras"), export["teams"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("EDIT_MEMBERS", "MANAGE_PERMISSIONS"), export["clubGrants"]!!.jsonArray.map { it.jsonPrimitive.content })
        val teamGrant = export["teamGrants"]!!.jsonArray.single().jsonObject
        assertEquals(listOf(teamA.toString(), "Zebras", "MANAGE_TEAMS"),
            listOf("teamId", "teamName", "capability").map { teamGrant[it]!!.jsonPrimitive.content })

        // Who came and call-ups (since 1.34.0): only the member's own rows.
        val present = export["presence"]!!.jsonArray.single().jsonObject
        assertEquals(listOf(training.toString(), teamA.toString(), "Zebras", "2026-10-01T16:00Z", "LEADER"),
            listOf("eventId", "teamId", "teamName", "startsAt", "role").map { present[it]!!.jsonPrimitive.content })
        val squad = export["squads"]!!.jsonArray.single().jsonObject
        assertEquals(listOf(match.toString(), "Zebras", "2030-05-04T08:00Z", "2030-05-04T07:15Z", "Bus stop"),
            listOf("eventId", "teamName", "startsAt", "meetAt", "meetingPoint").map { squad[it]!!.jsonPrimitive.content })

        // Left out on purpose: the login binding, the account id, other members and the audit trail.
        for (secret in listOf(subject, "issuer.example.invalid", account.toString(), other.toString(), "Other", "VIEW_TEAMS", "actor")) {
            assertFalse(text.contains(secret), "must not contain $secret")
        }
        assertEquals(setOf("exportedAt", "member", "teams", "invitations", "guardianships", "fees", "attendance", "shifts", "absences", "rides", "presence", "squads", "clubGrants", "teamGrants"), export.keys)
    }

    @Test
    fun everyExportThatProducedDataIsAuditedWithoutAnActorAndAnUnknownMemberIsNot() {
        val before = events(other)
        assertNotNull(MemberExport(db).exportAsOperator(other))
        assertNotNull(MemberExport(db).exportAsOperator(other))
        assertEquals(before + 2, events(other))
        val actor = db.fetchValue(
            "SELECT actor_account_id FROM audit_events WHERE event_type = 'MEMBER_EXPORTED' AND target_member_id = ? LIMIT 1", other)
        assertNull(actor, "an operator command has no browser-session actor")

        val unknown = UUID.randomUUID()
        assertNull(MemberExport(db).exportAsOperator(unknown))
        assertEquals(0, events(unknown))
    }

    @Test
    fun anArchivedMemberCanBeExportedBeforeErasure() {
        val archived = UUID.randomUUID()
        db.execute(
            """INSERT INTO members (id, first_name, last_name, date_of_birth, archived_at)
               VALUES (?, 'Arch', 'Ived', DATE '2012-01-01', TIMESTAMPTZ '2026-01-02 03:04:05+00')""", archived)
        try {
            val m = requireNotNull(MemberExport(db).exportAsOperator(archived))["member"]!!.jsonObject
            assertEquals("2026-01-02T03:04:05Z", m["archivedAt"]!!.jsonPrimitive.content)
            assertEquals(false, m["hasLinkedLogin"]!!.jsonPrimitive.boolean)
            assertEquals(JsonArray(emptyList()), requireNotNull(MemberExport(db).exportAsOperator(archived))["teams"])
        } finally {
            db.execute("DELETE FROM audit_events WHERE target_member_id = ?", archived)
            db.execute("DELETE FROM members WHERE id = ?", archived)
        }
    }

    @Test
    fun operatorInputIsValidatedBeforeAnyConnection() {
        val id = UUID.randomUUID().toString()
        val ok = mapOf("OPENCLUB_EXPORT_CONFIRM" to "export-member", "OPENCLUB_EXPORT_MEMBER_ID" to id)
        assertEquals(UUID.fromString(id), exportInput(ok))
        for ((label, env) in listOf(
            "no confirmation" to ok - "OPENCLUB_EXPORT_CONFIRM",
            "wrong confirmation" to ok + ("OPENCLUB_EXPORT_CONFIRM" to "erase-archived-member"),
            "no member" to ok - "OPENCLUB_EXPORT_MEMBER_ID",
            "blank member" to ok + ("OPENCLUB_EXPORT_MEMBER_ID" to " "),
            "short id" to ok + ("OPENCLUB_EXPORT_MEMBER_ID" to "1234"),
            "uppercase without dashes" to ok + ("OPENCLUB_EXPORT_MEMBER_ID" to id.uppercase().replace("-", "")),
        )) assertThrows(IllegalArgumentException::class.java, { exportInput(env) }, label)
    }
}
