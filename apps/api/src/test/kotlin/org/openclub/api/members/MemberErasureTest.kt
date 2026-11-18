package org.openclub.api.members

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.time.LocalDate
import java.util.UUID

/** Erasure of an archived member (privacy proposal section 4): hard delete after archive, typed confirmation, audited. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberErasureTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val club = UUID.randomUUID()
    private val editorAccount = UUID.randomUUID()
    private val plainAccount = UUID.randomUUID()
    private val editor = UUID.randomUUID()
    private val plain = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val created = mutableListOf<UUID>()

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
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Erasure Club', ?)", club, "erasure-$club")
        db.execute("INSERT INTO accounts (id) VALUES (?), (?)", editorAccount, plainAccount)
        for ((account, member) in listOf(editorAccount to editor, plainAccount to plain)) {
            db.execute(
                """INSERT INTO account_identities (id, account_id, issuer, subject)
                   VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "erase-$account")
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone)
                   VALUES (?, ?, 'Erasure', 'Actor', DATE '1990-01-01', 'a@example.invalid', '+41000000000')""", member, account)
        }
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
        db.execute("INSERT INTO teams (id, name) VALUES (?, ?)", team, "Erasure team $team")
    }

    @AfterAll
    fun cleanup() {
        if (source == null) return
        val ids = (created + editor + plain).toTypedArray()
        db.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", ids)
        db.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", ids)
        db.execute("DELETE FROM teams WHERE id = ?", team)
        db.execute("DELETE FROM accounts WHERE id IN (?, ?)", editorAccount, plainAccount)
        db.execute("DELETE FROM clubs WHERE id = ?", club)
        source?.close()
        source = null
    }

    /** An archived member who had a team assignment and grants before archiving, as in real use. */
    private fun archivedMember(first: String = "Erika", last: String = "Musterfrau"): UUID {
        val id = requireNotNull(MemberCreation(db).create(editorAccount, MemberCreation.Input(
            first, last, LocalDate.of(2012, 5, 5), "e@example.invalid", "+41000000009")))
        created += id
        db.execute("INSERT INTO member_team_assignments VALUES (?, ?)", team, id)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", id)
        db.execute("INSERT INTO team_capability_grants VALUES (?, ?, 'VIEW_TEAMS')", team, id)
        assertTrue(MemberArchive(db).archive(editorAccount, id))
        return id
    }

    private fun present(id: UUID) = db.fetchCount(DSL.table("members"), DSL.field("id").eq(id)) == 1
    private fun erasedEvents(id: UUID) = db.fetch(
        "SELECT actor_account_id FROM audit_events WHERE event_type = 'MEMBER_ERASED' AND target_member_id = ?", id)

    @Test
    fun anAuthorizedEditorErasesAnArchivedMemberCompletelyAndTheEventKeepsOnlyTheIdentifier() {
        val id = archivedMember()
        assertEquals(MemberErasure.Result.ERASED, MemberErasure(db).erase(editorAccount, id, "  Erika Musterfrau "))
        assertFalse(present(id))
        for (table in listOf("member_team_assignments", "club_capability_grants", "team_capability_grants")) {
            assertEquals(0, db.fetchCount(DSL.table(table), DSL.field("member_id").eq(id)), table)
        }
        val events = erasedEvents(id)
        assertEquals(1, events.size)
        assertEquals(editorAccount, events[0].get(0, UUID::class.java))
        // The earlier lifecycle events survive for their retention period; they hold identifiers only.
        assertTrue(db.fetchCount(DSL.table("audit_events"), DSL.field("target_member_id").eq(id)) >= 3)
        assertEquals(MemberErasure.Result.DENIED, MemberErasure(db).erase(editorAccount, id, "Erika Musterfrau"),
            "a repeated erase finds nothing")
        assertEquals(1, erasedEvents(id).size)
    }

    @Test
    fun everyRefusalIsTheSameDeniedAndChangesNothing() {
        val erasure = MemberErasure(db)
        val archived = archivedMember("Hans", "Beispiel")
        val active = requireNotNull(MemberCreation(db).create(editorAccount, MemberCreation.Input(
            "Active", "Person", LocalDate.of(2012, 1, 1)))).also { created += it }
        val refusals = listOf(
            "an active member" to { erasure.erase(editorAccount, active, "Active Person") },
            "a wrong typed name" to { erasure.erase(editorAccount, archived, "Hans Beispiele") },
            "a name in the wrong case" to { erasure.erase(editorAccount, archived, "hans beispiel") },
            "an empty confirmation" to { erasure.erase(editorAccount, archived, "") },
            "an actor without a grant" to { erasure.erase(plainAccount, archived, "Hans Beispiel") },
            "an unknown account" to { erasure.erase(UUID.randomUUID(), archived, "Hans Beispiel") },
            "an unknown member" to { erasure.erase(editorAccount, UUID.randomUUID(), "Hans Beispiel") },
            "a linked administrator" to { erasure.erase(editorAccount, editor, "Erasure Actor") },
            "operator, active member" to { erasure.eraseAsOperator(active, "Active Person") },
            "operator, wrong name" to { erasure.eraseAsOperator(archived, "Someone Else") },
        )
        for ((label, call) in refusals) assertEquals(MemberErasure.Result.DENIED, call(), label)
        assertTrue(present(archived) && present(active) && present(editor))
        assertEquals(0, erasedEvents(archived).size + erasedEvents(active).size + erasedEvents(editor).size)

        // An editor whose verified identity is revoked cannot erase, even with the grant still in place.
        db.execute("DELETE FROM account_identities WHERE account_id = ?", editorAccount)
        try {
            assertEquals(MemberErasure.Result.DENIED, erasure.erase(editorAccount, archived, "Hans Beispiel"))
        } finally {
            db.execute(
                """INSERT INTO account_identities (id, account_id, issuer, subject)
                   VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), editorAccount, "erase-again-$editorAccount")
        }
        assertTrue(present(archived))
    }

    @Test
    fun theOperatorPathErasesWithoutAnActor() {
        val id = archivedMember("Operator", "Erased")
        assertEquals(MemberErasure.Result.ERASED, MemberErasure(db).eraseAsOperator(id, "Operator Erased"))
        assertFalse(present(id))
        val events = erasedEvents(id)
        assertEquals(1, events.size)
        assertNull(events[0].get(0), "an operator command has no browser-session actor")
    }

    @Test
    fun operatorInputIsValidatedBeforeAnyConnection() {
        val member = UUID.randomUUID().toString()
        val ok = mapOf("OPENCLUB_ERASE_CONFIRM" to "erase-archived-member", "OPENCLUB_ERASE_MEMBER_ID" to member,
            "OPENCLUB_ERASE_NAME" to "  Erika Musterfrau ")
        assertEquals(ErasureInput(UUID.fromString(member), "Erika Musterfrau"), erasureInput(ok))
        for ((label, env) in listOf(
            "no confirmation" to ok - "OPENCLUB_ERASE_CONFIRM",
            "wrong confirmation" to ok + ("OPENCLUB_ERASE_CONFIRM" to "yes"),
            "no member" to ok - "OPENCLUB_ERASE_MEMBER_ID",
            "short member id" to ok + ("OPENCLUB_ERASE_MEMBER_ID" to "1234"),
            "uppercase hex without dashes" to ok + ("OPENCLUB_ERASE_MEMBER_ID" to member.uppercase().replace("-", "")),
            "no name" to ok - "OPENCLUB_ERASE_NAME",
            "blank name" to ok + ("OPENCLUB_ERASE_NAME" to "   "),
        )) {
            assertThrows(IllegalArgumentException::class.java, { erasureInput(env) }, label)
        }
    }
}
