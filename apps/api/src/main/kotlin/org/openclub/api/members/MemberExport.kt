package org.openclub.api.members

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.openclub.api.database.AuditEvents
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Export of everything the installation stores about one member (privacy proposal section 4: JSON for a
 * verified access or portability request), produced by an operator (`MemberExportCli`) or, since OpenAPI 1.7.0,
 * by an editor (`GET /api/v1/members/{id}/export`, owner-approved 2026-11-19, ADR-0008 addendum 10). It contains
 * the member's own stored fields, their team assignments, their invitation timestamps, their grants and their
 * guardianships (the other member only as an ID, with the date), membership fees (since OpenAPI 1.12.0),
 * attendance answers (since 1.14.1), volunteer shifts (since 1.16.0), absences (since 1.20.0), ride offers and bookings (since 1.21.0), recorded presence (who came) and match call-ups (since 1.34.0). It deliberately leaves out the login binding
 * (issuer and subject), sessions, other people's names and contacts, and the audit trail, whose actor identifiers
 * belong to other people; whether a login is linked is reported only as a yes or no. The read and its
 * `MEMBER_EXPORTED` audit event happen in one transaction, so every export that produced data is audited. Active,
 * archived and linked members can all be exported.
 */
internal class MemberExport(private val database: DSLContext) {
    /** The export as a JSON object, or null when no such member exists (nothing is audited then). */
    fun exportAsOperator(memberId: UUID, exportedAt: Instant = Instant.now()): JsonObject? =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            build(db, memberId, exportedAt)?.also { AuditEvents.record(db, AuditEvents.Type.MEMBER_EXPORTED, null, memberId) }
        }

    sealed interface EditorExport {
        data class Exported(val json: JsonObject) : EditorExport
        data object NotFound : EditorExport
        data object Denied : EditorExport
    }

    /**
     * The same export for an editor with a live EDIT_MEMBERS grant and a current verified identity, locked for the
     * transaction so a concurrent revocation cannot be outrun; audited with the editor as actor.
     */
    fun exportAsEditor(actorAccountId: UUID, memberId: UUID, exportedAt: Instant = Instant.now()): EditorExport =
        database.transactionResult { configuration ->
            val db = DSL.using(configuration)
            val editor = db.fetchOne(
                """SELECT 1 FROM members actor JOIN club_capability_grants g ON g.member_id = actor.id JOIN clubs club ON TRUE
                   WHERE actor.account_id = ? AND ${ActorSql.activeVerified("actor")} AND g.capability = 'EDIT_MEMBERS'
                   FOR SHARE OF actor, g""", actorAccountId) != null
            if (!editor) return@transactionResult EditorExport.Denied
            val json = build(db, memberId, exportedAt) ?: return@transactionResult EditorExport.NotFound
            AuditEvents.record(db, AuditEvents.Type.MEMBER_EXPORTED, actorAccountId, memberId)
            EditorExport.Exported(json)
        }

    private fun build(db: DSLContext, memberId: UUID, exportedAt: Instant): JsonObject? {
            val member = db.fetchOne(
                """SELECT first_name, last_name, date_of_birth, email, phone, gender, is_player, role, listed_publicly, js_person_number, created_at,
                          archived_at, account_id IS NOT NULL AS has_login
                   FROM members WHERE id = ?""", memberId,
            ) ?: return null
            val teams = db.fetch(
                """SELECT t.id, t.name FROM member_team_assignments a JOIN teams t ON t.id = a.team_id
                   WHERE a.member_id = ? ORDER BY t.name, t.id""", memberId)
            val clubGrants = db.fetch(
                "SELECT capability FROM club_capability_grants WHERE member_id = ? ORDER BY capability", memberId)
            val teamGrants = db.fetch(
                """SELECT g.team_id, t.name, g.capability FROM team_capability_grants g JOIN teams t ON t.id = g.team_id
                   WHERE g.member_id = ? ORDER BY t.name, g.capability""", memberId)

            val guardianships = db.fetch(
                """SELECT 'guardianOf' AS relation, minor_member_id AS other, created_at FROM member_guardianships WHERE guardian_member_id = ?
                   UNION ALL
                   SELECT 'guardedBy', guardian_member_id, created_at FROM member_guardianships WHERE minor_member_id = ?
                   ORDER BY 1, 3, 2""", memberId, memberId)

            // Membership fees (OpenAPI 1.12.0): the member's own fee rows, with the period name and due date.
            val fees = db.fetch(
                """SELECT p.id, p.name, p.due_date, f.amount_cents, f.paid_on
                   FROM member_fees f JOIN fee_periods p ON p.id = f.period_id
                   WHERE f.member_id = ? ORDER BY p.due_date, p.name, p.id""", memberId)

            // Attendance answers (since OpenAPI 1.14.1, security review): the event ID and start with the answer.
            val attendance = db.fetch(
                """SELECT e.id, e.starts_at, a.response, a.answered_at
                   FROM event_attendance a JOIN team_events e ON e.id = a.event_id
                   WHERE a.member_id = ? ORDER BY e.starts_at, e.id""", memberId)

            // Volunteer shifts (since OpenAPI 1.16.0): the shift, its event and times, and when the member signed up.
            val shifts = db.fetch(
                """SELECT s.id, s.event_id, s.title, s.starts_at, s.ends_at, v.signed_up_at
                   FROM shift_volunteers v JOIN event_shifts s ON s.id = v.shift_id
                   WHERE v.member_id = ? ORDER BY s.starts_at, s.id""", memberId)

            val absences = db.fetch(
                "SELECT from_date, to_date FROM member_absences WHERE member_id = ? ORDER BY from_date, to_date, id", memberId)

            val rides = db.fetch(
                """SELECT r.id, r.event_id, 'DRIVER' AS role, r.direction, r.departs_at FROM event_rides r WHERE r.driver_member_id = ?
                   UNION ALL
                   SELECT r.id, r.event_id, 'PASSENGER', r.direction, r.departs_at
                   FROM ride_passengers p JOIN event_rides r ON r.id = p.ride_id WHERE p.member_id = ?
                   ORDER BY 5, 1""", memberId, memberId)

            // Who came (since OpenAPI 1.34.0): each event where the member was recorded, with the team and role.
            val presence = db.fetch(
                """SELECT e.id, t.id, t.name, e.starts_at, p.role, p.recorded_at
                   FROM event_presence p JOIN team_events e ON e.id = p.event_id JOIN teams t ON t.id = e.team_id
                   WHERE p.member_id = ? ORDER BY e.starts_at, e.id""", memberId)

            // Match call-ups (since OpenAPI 1.34.0): the match, its team and start, and the meeting time and point.
            val squads = db.fetch(
                """SELECT e.id, t.id, t.name, e.starts_at, s.meet_at, s.meeting_point
                   FROM squad_members m JOIN event_squads s ON s.event_id = m.event_id
                   JOIN team_events e ON e.id = m.event_id JOIN teams t ON t.id = e.team_id
                   WHERE m.member_id = ? ORDER BY e.starts_at, e.id""", memberId)

            val invitations = db.fetch(
                "SELECT created_at, expires_at, used_at, revoked_at FROM member_invitations WHERE member_id = ? ORDER BY created_at, id",
                memberId)

            fun text(name: String) = member.get(name)?.toString()?.let(::JsonPrimitive) ?: JsonNull
            val result = buildJsonObject {
                put("exportedAt", exportedAt.toString())
                put("member", buildJsonObject {
                    put("id", memberId.toString())
                    put("firstName", text("first_name"))
                    put("lastName", text("last_name"))
                    put("dateOfBirth", text("date_of_birth"))
                    put("email", text("email"))
                    put("phone", text("phone"))
                    put("gender", text("gender"))
                    put("isPlayer", member.get("is_player", Boolean::class.java))
                    put("role", text("role"))
                    put("createdAt", text("created_at"))
                    put("archivedAt", text("archived_at"))
                    put("hasLinkedLogin", member.get("has_login", Boolean::class.java))
                    put("listedPublicly", member.get("listed_publicly", Boolean::class.java))
                    put("jsPersonNumber", text("js_person_number"))
                })
                put("teams", buildJsonArray {
                    teams.forEach { add(buildJsonObject { put("id", it.get(0).toString()); put("name", it.get(1).toString()) }) }
                })
                put("invitations", buildJsonArray {
                    invitations.forEach {
                        add(buildJsonObject {
                            for ((i, name) in listOf("createdAt", "expiresAt", "usedAt", "revokedAt").withIndex()) {
                                put(name, it.get(i)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        })
                    }
                })
                put("guardianships", buildJsonArray {
                    guardianships.forEach {
                        add(buildJsonObject {
                            put("relation", it.get(0).toString()); put("memberId", it.get(1).toString())
                            put("since", it.get(2).toString())
                        })
                    }
                })
                put("fees", buildJsonArray {
                    fees.forEach {
                        add(buildJsonObject {
                            put("periodId", it.get(0).toString()); put("periodName", it.get(1).toString())
                            put("dueDate", it.get(2).toString()); put("amountCents", it.get(3, Int::class.java))
                            put("paidOn", it.get(4)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }
                })
                put("attendance", buildJsonArray {
                    attendance.forEach {
                        add(buildJsonObject {
                            put("eventId", it.get(0).toString())
                            put("startsAt", it.get(1, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                            put("response", it.get(2).toString())
                            put("answeredAt", it.get(3, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                        })
                    }
                })
                put("shifts", buildJsonArray {
                    shifts.forEach {
                        add(buildJsonObject {
                            put("shiftId", it.get(0).toString())
                            put("eventId", it.get(1).toString())
                            put("title", it.get(2).toString())
                            put("startsAt", it.get(3, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                            put("endsAt", it.get(4, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                            put("signedUpAt", it.get(5, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                        })
                    }
                })
                put("absences", buildJsonArray {
                    absences.forEach { add(buildJsonObject { put("from", it.get(0).toString()); put("to", it.get(1).toString()) }) }
                })
                put("rides", buildJsonArray {
                    rides.forEach { add(buildJsonObject {
                        put("rideId", it.get(0).toString()); put("eventId", it.get(1).toString()); put("role", it.get(2).toString())
                        put("direction", it.get(3).toString())
                        put("departsAt", it.get(4, OffsetDateTime::class.java).withOffsetSameInstant(ZoneOffset.UTC).toString())
                    }) }
                })
                fun utc(value: Any?) = (value as OffsetDateTime?)?.withOffsetSameInstant(ZoneOffset.UTC)?.toString()
                    ?.let(::JsonPrimitive) ?: JsonNull
                put("presence", buildJsonArray {
                    presence.forEach { add(buildJsonObject {
                        put("eventId", it.get(0).toString()); put("teamId", it.get(1).toString()); put("teamName", it.get(2).toString())
                        put("startsAt", utc(it.get(3, OffsetDateTime::class.java))); put("role", it.get(4).toString())
                        put("recordedAt", utc(it.get(5, OffsetDateTime::class.java)))
                    }) }
                })
                put("squads", buildJsonArray {
                    squads.forEach { add(buildJsonObject {
                        put("eventId", it.get(0).toString()); put("teamId", it.get(1).toString()); put("teamName", it.get(2).toString())
                        put("startsAt", utc(it.get(3, OffsetDateTime::class.java)))
                        put("meetAt", utc(it.get(4, OffsetDateTime::class.java)))
                        put("meetingPoint", it.get(5)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                    }) }
                })
                put("clubGrants", buildJsonArray { clubGrants.forEach { add(JsonPrimitive(it.get(0).toString())) } })
                put("teamGrants", buildJsonArray {
                    teamGrants.forEach {
                        add(buildJsonObject {
                            put("teamId", it.get(0).toString()); put("teamName", it.get(1).toString())
                            put("capability", it.get(2).toString())
                        })
                    }
                })
            }
            return result
    }
}
