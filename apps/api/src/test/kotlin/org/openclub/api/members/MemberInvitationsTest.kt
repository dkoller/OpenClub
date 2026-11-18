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
import org.openclub.api.members.MemberInvitations.AcceptResult
import org.openclub.api.members.MemberInvitations.CreateResult
import org.openclub.api.members.MemberInvitations.RevokeResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Invitations and account unlinking (owner-approved design, 2026-11-18). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberInvitationsTest {
    private var source: HikariDataSource? = null
    private lateinit var db: DSLContext
    private val club = UUID.randomUUID()
    private val editorAccount = UUID.randomUUID()
    private val managerAccount = UUID.randomUUID()
    private val outsiderAccount = UUID.randomUUID()
    private val editor = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val accounts = mutableListOf(editorAccount, managerAccount, outsiderAccount)
    private val members = mutableListOf(editor, manager, outsider)

    @BeforeAll
    fun fixture() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL")
        source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 6
        })
        Flyway.configure().dataSource(source).load().migrate()
        db = DSL.using(source, SQLDialect.POSTGRES)
        db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Invitation Club', ?)", club, "invite-$club")
        for ((account, member, role) in listOf(Triple(editorAccount, editor, "ADMIN"), Triple(managerAccount, manager, "ADMIN"),
            Triple(outsiderAccount, outsider, "MEMBER"))) {
            db.execute("INSERT INTO accounts (id) VALUES (?)", account)
            identity(account)
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role)
                   VALUES (?, ?, 'Invite', 'Actor', DATE '1985-01-01', 'a@example.invalid', '+41000000000', ?)""", member, account, role)
        }
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'EDIT_MEMBERS')", editor)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'MANAGE_PERMISSIONS')", manager)
    }

    @AfterAll
    fun cleanup() {
        if (source == null) return
        val ids = members.toTypedArray()
        db.execute("DELETE FROM audit_events WHERE target_member_id = ANY(?::uuid[])", ids)
        db.execute("DELETE FROM members WHERE id = ANY(?::uuid[])", ids)
        db.execute("DELETE FROM accounts WHERE id = ANY(?::uuid[])", accounts.toTypedArray())
        db.execute("DELETE FROM clubs WHERE id = ?", club)
        source?.close()
        source = null
    }

    private fun identity(account: UUID) = db.execute(
        """INSERT INTO account_identities (id, account_id, issuer, subject)
           VALUES (?, ?, 'https://issuer.example.invalid', ?)""", UUID.randomUUID(), account, "inv-$account")

    private fun newAccount(verified: Boolean = true): UUID = UUID.randomUUID().also {
        accounts += it
        db.execute("INSERT INTO accounts (id) VALUES (?)", it)
        if (verified) identity(it)
    }

    private fun newMember(dob: String = "1990-01-01", role: String = "MEMBER", account: UUID? = null, archived: Boolean = false): UUID =
        UUID.randomUUID().also {
            members += it
            db.execute(
                """INSERT INTO members (id, account_id, first_name, last_name, date_of_birth, email, phone, role, archived_at)
                   VALUES (?, ?, 'Invited', 'Member', ?::date, 'i@example.invalid', '+41000000001', ?, ?::timestamptz)""",
                it, account, dob, role, if (archived) "2026-01-01T00:00:00Z" else null)
        }

    private val invitations by lazy { MemberInvitations(db) }
    private fun create(member: UUID, actor: UUID = editorAccount) = invitations.create(actor, member)
    private fun secretOf(member: UUID) = (create(member) as CreateResult.Created).secret
    private fun events(member: UUID, type: String) = db.fetchCount(DSL.table("audit_events"),
        DSL.field("event_type").eq(type).and(DSL.field("target_member_id").eq(member)))
    private fun linked(member: UUID): UUID? = db.fetchValue("SELECT account_id FROM members WHERE id = ?", member) as UUID?
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    @Test
    fun creatingNeedsEditMembersAndAnEligibleMemberAndStoresOnlyTheHash() {
        val member = newMember()
        for ((label, actor) in listOf("a member without the grant" to outsiderAccount, "an administrator without it" to managerAccount,
            "an unknown account" to UUID.randomUUID())) {
            assertEquals(CreateResult.Denied, create(member, actor), label)
        }
        assertEquals(0, events(member, "INVITATION_CREATED"))

        val created = create(member) as CreateResult.Created
        assertEquals(43, created.secret.length)
        assertTrue(created.secret.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "base64url")
        val days = (created.expiresAt.epochSecond - Instant.now().epochSecond) / 86_400.0
        assertTrue(days in 6.99..7.01, "seven days: $days")
        val row = db.fetchOne("SELECT * FROM member_invitations WHERE member_id = ?", member)!!
        assertEquals(sha256(created.secret), row.get("secret_hash"))
        assertTrue(row.intoMap().values.none { it?.toString()?.contains(created.secret) == true }, "the secret itself is never stored")
        assertEquals(editorAccount, row.get("created_by_account_id", UUID::class.java))
        assertEquals(1, events(member, "INVITATION_CREATED"))

        assertEquals(CreateResult.Conflict, create(member), "one open invitation per member")
        assertEquals(1, events(member, "INVITATION_CREATED"))
    }

    @Test
    fun onlyActiveUnlinkedAdultOrdinaryMembersCanBeInvited() {
        assertEquals(CreateResult.NotFound, create(UUID.randomUUID()), "unknown")
        assertEquals(CreateResult.NotFound, create(newMember(archived = true)), "archived")
        assertEquals(CreateResult.Conflict, create(newMember(account = newAccount())), "already linked")
        assertEquals(CreateResult.Conflict, create(newMember(role = "ADMIN")), "an administrator")
        assertEquals(CreateResult.Conflict, create(newMember(dob = "2015-01-01")), "a minor: a guardian signs in for a minor")
        // Eligibility follows the club's adult age: raising it makes a 20-year-old a minor.
        val twenty = newMember(dob = java.time.LocalDate.now().minusYears(20).toString())
        db.execute("UPDATE clubs SET adult_age_years = 25 WHERE id = ?", club)
        try { assertEquals(CreateResult.Conflict, create(twenty)) } finally { db.execute("UPDATE clubs SET adult_age_years = 18 WHERE id = ?", club) }
        assertTrue(create(twenty) is CreateResult.Created)
        // An editor whose verified identity is revoked cannot invite, grant or no grant.
        db.execute("DELETE FROM account_identities WHERE account_id = ?", editorAccount)
        try { assertEquals(CreateResult.Denied, create(newMember())) } finally { identity(editorAccount) }
    }

    @Test
    fun anExpiredInvitationIsRetiredWhenANewOneIsCreated() {
        val member = newMember()
        secretOf(member)
        db.execute("UPDATE member_invitations SET created_at = now() - interval '9 days', expires_at = now() - interval '2 days' WHERE member_id = ?", member)
        assertTrue(create(member) is CreateResult.Created)
        assertEquals(2, db.fetchCount(DSL.table("member_invitations"), DSL.field("member_id").eq(member)))
        assertEquals(1, db.fetchCount(DSL.table("member_invitations"),
            DSL.field("member_id").eq(member).and(DSL.field("used_at").isNull).and(DSL.field("revoked_at").isNull)))
    }

    @Test
    fun acceptingLinksTheAccountGrantsNothingAndWorksOnce() {
        val member = newMember()
        val secret = secretOf(member)
        val account = newAccount()
        assertEquals(AcceptResult.ACCEPTED, invitations.accept(account, secret))
        assertEquals(account, linked(member))
        assertEquals("MEMBER", db.fetchValue("SELECT role FROM members WHERE id = ?", member))
        assertEquals(0, db.fetchCount(DSL.table("club_capability_grants"), DSL.field("member_id").eq(member)))
        assertEquals(0, db.fetchCount(DSL.table("team_capability_grants"), DSL.field("member_id").eq(member)))
        assertEquals(1, events(member, "INVITATION_ACCEPTED"))
        val actor = db.fetchValue("SELECT actor_account_id FROM audit_events WHERE event_type = 'INVITATION_ACCEPTED' AND target_member_id = ?", member)
        assertEquals(account, actor, "the audit event shows which account accepted")
        assertEquals(account, db.fetchValue("SELECT accepted_by_account_id FROM member_invitations WHERE member_id = ?", member))

        // Replay, by the same or another account, changes nothing.
        assertEquals(AcceptResult.REFUSED, invitations.accept(account, secret))
        assertEquals(AcceptResult.REFUSED, invitations.accept(newAccount(), secret))
        assertEquals(account, linked(member))
        assertEquals(1, events(member, "INVITATION_ACCEPTED"))
    }

    @Test
    fun everyReasonToRefuseIsTheSameRefusalAndChangesNothing() {
        fun assertRefusedUnchanged(label: String, member: UUID, account: UUID, secret: String) {
            val before = linked(member)
            val eventsBefore = events(member, "INVITATION_ACCEPTED")
            assertEquals(AcceptResult.REFUSED, invitations.accept(account, secret), label)
            assertEquals(before, linked(member), "$label: link unchanged")
            assertEquals(eventsBefore, events(member, "INVITATION_ACCEPTED"), "$label: no event")
        }
        val member = newMember()
        val secret = secretOf(member)
        val good = newAccount()
        assertRefusedUnchanged("wrong secret", member, good, "A".repeat(43))
        assertRefusedUnchanged("too short", member, good, "short")
        assertRefusedUnchanged("not base64url", member, good, "!".repeat(43))
        assertRefusedUnchanged("empty", member, good, "")
        assertRefusedUnchanged("an account without a verified identity", member, newAccount(verified = false), secret)
        assertRefusedUnchanged("an unknown account", member, UUID.randomUUID(), secret)
        assertRefusedUnchanged("an account that already has a member", member, outsiderAccount, secret)

        db.execute("UPDATE member_invitations SET created_at = now() - interval '9 days', expires_at = now() - interval '2 days' WHERE member_id = ?", member)
        assertRefusedUnchanged("expired", member, good, secret)

        val revokedMember = newMember()
        val revokedSecret = secretOf(revokedMember)
        assertEquals(RevokeResult.REVOKED, invitations.revoke(editorAccount, revokedMember))
        assertRefusedUnchanged("revoked", revokedMember, good, revokedSecret)

        val archived = newMember()
        val archivedSecret = secretOf(archived)
        db.execute("UPDATE members SET archived_at = now() WHERE id = ?", archived)
        assertRefusedUnchanged("the member was archived meanwhile", archived, good, archivedSecret)

        val linkedMeanwhile = newMember()
        val linkedSecret = secretOf(linkedMeanwhile)
        db.execute("UPDATE members SET account_id = ? WHERE id = ?", newAccount(), linkedMeanwhile)
        assertRefusedUnchanged("the member was linked meanwhile", linkedMeanwhile, good, linkedSecret)

        val becameAdmin = newMember()
        val adminSecret = secretOf(becameAdmin)
        db.execute("UPDATE members SET role = 'ADMIN' WHERE id = ?", becameAdmin)
        assertRefusedUnchanged("the member became an administrator", becameAdmin, good, adminSecret)

        val becameMinor = newMember(dob = java.time.LocalDate.now().minusYears(20).toString())
        val minorSecret = secretOf(becameMinor)
        db.execute("UPDATE clubs SET adult_age_years = 25 WHERE id = ?", club)
        try { assertRefusedUnchanged("the adult age was raised", becameMinor, good, minorSecret) } finally {
            db.execute("UPDATE clubs SET adult_age_years = 18 WHERE id = ?", club)
        }
        assertNull(linked(member))
        assertEquals(0, events(member, "INVITATION_ACCEPTED"))
    }

    @Test
    fun twoAccountsRacingOneSecretHaveExactlyOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val member = newMember()
                val secret = secretOf(member)
                val contenders = listOf(newAccount(), newAccount())
                val start = CountDownLatch(1)
                val results = contenders.map { account -> pool.submit<AcceptResult> { start.await(); invitations.accept(account, secret) } }
                start.countDown()
                val outcomes = results.map { it.get() }
                assertEquals(1, outcomes.count { it == AcceptResult.ACCEPTED }, "round $round: $outcomes")
                assertTrue(linked(member) in contenders, "the winner is linked")
                assertEquals(1, events(member, "INVITATION_ACCEPTED"))
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun revokingIsAuditedOnlyWhenSomethingWasOpenAndNeedsEditMembers() {
        val member = newMember()
        assertEquals(RevokeResult.DENIED, invitations.revoke(outsiderAccount, member))
        assertEquals(RevokeResult.NOT_FOUND, invitations.revoke(editorAccount, UUID.randomUUID()))
        assertEquals(RevokeResult.NOTHING_OPEN, invitations.revoke(editorAccount, member))
        val secret = secretOf(member)
        assertEquals(RevokeResult.DENIED, invitations.revoke(managerAccount, member), "MANAGE_PERMISSIONS alone is not enough")
        assertEquals(RevokeResult.REVOKED, invitations.revoke(editorAccount, member))
        assertEquals(RevokeResult.NOTHING_OPEN, invitations.revoke(editorAccount, member))
        assertEquals(1, events(member, "INVITATION_REVOKED"))
        assertEquals(AcceptResult.REFUSED, invitations.accept(newAccount(), secret))
        assertTrue(create(member) is CreateResult.Created, "a new invitation can follow a revoked one")
    }

    @Test
    fun unlinkingNeedsAnAdminManagerEndsSessionsAndClearsRights() {
        val link = MemberAccountLink(db)
        val account = newAccount()
        val member = newMember(account = account)
        db.execute("INSERT INTO club_capability_grants VALUES (?, 'VIEW_MEMBER_DIRECTORY')", member)
        org.openclub.api.auth.AuthSessions(db).create(account)
        assertEquals(1, db.fetchCount(DSL.table("auth_sessions"), DSL.field("account_id").eq(account)))

        assertEquals(MemberAccountLink.Result.DENIED, link.unlink(editorAccount, member), "EDIT_MEMBERS is not enough")
        assertEquals(MemberAccountLink.Result.DENIED, link.unlink(outsiderAccount, member))
        assertEquals(MemberAccountLink.Result.NOT_FOUND, link.unlink(managerAccount, UUID.randomUUID()))
        assertEquals(MemberAccountLink.Result.CONFLICT, link.unlink(managerAccount, editor), "administrators are unlinked only by operator recovery")
        assertEquals(MemberAccountLink.Result.CONFLICT, link.unlink(managerAccount, newMember()), "an unlinked member has nothing to unlink")
        assertEquals(account, linked(member))
        assertEquals(0, events(member, "ACCOUNT_UNLINKED"))

        db.execute("DELETE FROM account_identities WHERE account_id = ?", managerAccount)
        try { assertEquals(MemberAccountLink.Result.DENIED, link.unlink(managerAccount, member), "a revoked identity cannot unlink") }
        finally { identity(managerAccount) }

        assertEquals(MemberAccountLink.Result.UNLINKED, link.unlink(managerAccount, member))
        assertNull(linked(member))
        assertEquals(0, db.fetchCount(DSL.table("auth_sessions"), DSL.field("account_id").eq(account)), "its sessions are gone")
        assertEquals(0, db.fetchCount(DSL.table("club_capability_grants"), DSL.field("member_id").eq(member)))
        assertEquals(1, events(member, "ACCOUNT_UNLINKED"))
        assertEquals(1, db.fetchCount(DSL.table("accounts"), DSL.field("id").eq(account)), "the account itself stays")
        assertEquals(MemberAccountLink.Result.CONFLICT, link.unlink(managerAccount, member), "a second unlink finds nothing")
        // The member can be invited again, and a new account can take the seat.
        val second = newAccount()
        assertEquals(AcceptResult.ACCEPTED, invitations.accept(second, secretOf(member)))
        assertEquals(second, linked(member))
    }

    @Test
    fun anInvitationDiesWithItsMember() {
        val member = newMember()
        secretOf(member)
        db.execute("UPDATE members SET archived_at = now() WHERE id = ?", member)
        assertEquals(1, db.execute("DELETE FROM members WHERE id = ?", member))
        assertEquals(0, db.fetchCount(DSL.table("member_invitations"), DSL.field("member_id").eq(member)), "erasure leaves no invitation")
    }
}
