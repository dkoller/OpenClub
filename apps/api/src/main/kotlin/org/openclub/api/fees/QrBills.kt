package org.openclub.api.fees

import org.openclub.api.database.AuditEvents
import net.codecrete.qrbill.canvas.PDFCanvas
import net.codecrete.qrbill.canvas.PDFFontSettings
import net.codecrete.qrbill.generator.Address
import net.codecrete.qrbill.generator.Bill
import net.codecrete.qrbill.generator.BillFormat
import net.codecrete.qrbill.generator.GraphicsFormat
import net.codecrete.qrbill.generator.Language
import net.codecrete.qrbill.generator.OutputSize
import net.codecrete.qrbill.generator.QRBill
import net.codecrete.qrbill.generator.SPSCharacterSet
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.Record
import org.openclub.api.members.ActorSql
import org.openclub.api.members.AgeSql
import org.openclub.api.members.ClubCapabilityAccess
import org.openclub.api.members.ClubCapabilityAccess.Capability
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Swiss payment rules for QR bills (OpenAPI 1.26.0, ADR-0008 addendum 30), kept free of the database so they are
 * unit-tested: the IBAN check (Switzerland and Liechtenstein, mod 97), QR-IBAN detection (institution ID 30000–31999)
 * and the payment reference built from the period's and the fee's stable numbers (V37).
 */
internal object SwissPayments {
    private val compactIban = Regex("(CH|LI)[0-9]{7}[0-9A-Z]{12}")
    private val mod10Table = intArrayOf(0, 9, 4, 6, 8, 2, 7, 1, 3, 5)

    /** The compact upper-case IBAN, or null unless it is a CH or LI IBAN with a valid checksum. Spaces are ignored. */
    fun iban(input: String): String? {
        val compact = input.filterNot { it == ' ' }.uppercase(Locale.ROOT)
        if (!compactIban.matches(compact)) return null
        return compact.takeIf { mod97(it.substring(4) + it.substring(0, 4)) == 1 }
    }

    /** A QR-IBAN has an institution ID (positions 5–9) from 30000 to 31999; it needs a QR reference. */
    fun isQrIban(iban: String): Boolean = iban.substring(4, 9).toInt() in 30000..31999

    /** ISO 7064 mod 97-10 over digits and letters (A = 10 … Z = 35). */
    fun mod97(text: String): Int = text.fold(0) { sum, char ->
        val value = Character.digit(char, 36)
        require(value >= 0) { "Not alphanumeric" }
        if (value < 10) (sum * 10 + value) % 97 else (sum * 100 + value) % 97
    }

    /** The check digit of the recursive mod-10 method (the old ISR reference) for [digits]. */
    fun mod10(digits: String): Int {
        var carry = 0
        for (char in digits) {
            require(char in '0'..'9') { "Not a digit" }
            carry = mod10Table[(carry + (char - '0')) % 10]
        }
        return (10 - carry) % 10
    }

    /** 27 digits: the period number in 8, the fee number in 18, and the check digit. */
    fun qrReference(periodNo: Int, feeNo: Int): String {
        require(periodNo in 1..99_999_999 && feeNo in 1..999_999_999) { "Reference number out of range" }
        val raw = String.format(Locale.ROOT, "%08d%018d", periodNo, feeNo)
        return raw + mod10(raw)
    }

    /** ISO 11649: "RF", two check digits and "P<period>M<fee>" (at most 21 characters after the check digits). */
    fun creditorReference(periodNo: Int, feeNo: Int): String {
        require(periodNo in 1..99_999_999 && feeNo in 1..999_999_999) { "Reference number out of range" }
        val raw = "P${periodNo}M$feeNo"
        return String.format(Locale.ROOT, "RF%02d", 98 - mod97(raw + "RF00")) + raw
    }

    /** The reference that fits the account: a QR reference for a QR-IBAN, a creditor reference otherwise. */
    fun reference(iban: String, periodNo: Int, feeNo: Int): String =
        if (isQrIban(iban)) qrReference(periodNo, feeNo) else creditorReference(periodNo, feeNo)

    /** As printed on the bill: a QR reference in groups of five from the right, a creditor reference in fours. */
    fun display(reference: String): String =
        if (reference.startsWith("RF")) reference.chunked(4).joinToString(" ")
        else reference.reversed().chunked(5).joinToString(" ").reversed()
}

/**
 * The club's payment details and the QR bill of one open fee (OpenAPI 1.26.0, ADR-0008 addendum 30). Holders of
 * `MANAGE_FEES` read and replace the details; a member downloads the bill of their own open fee or a current minor's
 * (the rule of `GET /v1/me/fees`). The bill is generated on demand and never stored.
 */
internal class QrBills(private val database: DSLContext) {
    data class Details(
        val iban: String, val holderName: String, val street: String?, val buildingNumber: String?,
        val postalCode: String, val town: String, val country: String,
    )

    sealed interface Reading {
        data class Found(val details: Details) : Reading
        data object None : Reading
        data object Denied : Reading
    }

    data class Payable(
        val periodName: String, val dueDate: LocalDate, val amountCents: Int, val firstName: String, val lastName: String,
        val reference: String, val details: Details,
    )

    sealed interface Outcome {
        data class Ready(val bill: Payable) : Outcome
        /** Not an active verified member, or the fee is neither theirs nor a current minor's. */
        data object Denied : Outcome
        /** The member is allowed, but has no fee in that period (unknown period or excluded). */
        data object NotFound : Outcome
        /** Paid, or nothing to pay. */
        data object NotOpen : Outcome
        data object NoPaymentDetails : Outcome
    }

    fun details(actor: UUID): Reading {
        if (!ClubCapabilityAccess(database).has(actor, Capability.MANAGE_FEES)) return Reading.Denied
        return stored(database)?.let { Reading.Found(it) } ?: Reading.None
    }

    /**
     * Replaces the details in one statement that also checks the live grant, and audits the change as
     * PAYMENT_DETAILS_CHANGED in the same transaction; false without `MANAGE_FEES`.
     */
    fun replace(actor: UUID, details: Details): Boolean = database.transactionResult { configuration ->
        val db = DSL.using(configuration)
        val changed = db.execute(
        """INSERT INTO club_payment_details (club_id, iban, holder_name, street, building_number, postal_code, town, country)
           SELECT c.id, ?::text, ?::text, ?::text, ?::text, ?::text, ?::text, ?::text FROM clubs c
           WHERE ${ActorSql.holdsClubCapability(Capability.MANAGE_FEES)}
           ON CONFLICT (club_id) DO UPDATE SET iban = EXCLUDED.iban, holder_name = EXCLUDED.holder_name,
             street = EXCLUDED.street, building_number = EXCLUDED.building_number, postal_code = EXCLUDED.postal_code,
             town = EXCLUDED.town, country = EXCLUDED.country, updated_at = now()""",
        details.iban, details.holderName, details.street, details.buildingNumber, details.postalCode, details.town,
        details.country, actor) == 1
        // The trail needs a member: the change is recorded against the treasurer's own member row.
        if (changed) db.execute(
            """INSERT INTO audit_events (event_type, actor_account_id, target_member_id)
               SELECT ?, ?, m.id FROM members m WHERE m.account_id = ? AND m.archived_at IS NULL""",
            AuditEvents.Type.PAYMENT_DETAILS_CHANGED.name, actor, actor)
        changed
    }

    /** The bill of [memberId]'s fee in [periodId], for the member themselves or a guardian of a current minor. */
    fun bill(actor: UUID, periodId: UUID, memberId: UUID): Outcome {
        val row = database.fetchOne(
            """WITH me AS (SELECT m.id FROM members m WHERE m.account_id = ? AND ${ActorSql.activeVerified("m")}),
                    allowed AS (
                      SELECT me.id FROM me WHERE me.id = ?
                      UNION
                      SELECT minor.id
                      FROM me JOIN member_guardianships gs ON gs.guardian_member_id = me.id
                      JOIN members guardian ON guardian.id = gs.guardian_member_id
                      JOIN members minor ON minor.id = gs.minor_member_id
                      JOIN clubs c ON TRUE
                      WHERE minor.id = ? AND ${AgeSql.adult("guardian")} AND minor.archived_at IS NULL
                        AND ${AgeSql.minor("minor")})
               SELECT m.first_name, m.last_name, f.member_id IS NOT NULL AS has_fee, f.amount_cents, f.paid_on,
                      f.reference_no AS fee_no, p.name, p.due_date, p.reference_no AS period_no
               FROM allowed a JOIN members m ON m.id = a.id
               LEFT JOIN member_fees f ON f.member_id = a.id AND f.period_id = ?
               LEFT JOIN fee_periods p ON p.id = f.period_id""",
            actor, memberId, memberId, periodId) ?: return Outcome.Denied
        if (!row.get("has_fee", Boolean::class.java)) return Outcome.NotFound
        val amount = row.get("amount_cents", Int::class.java)
        if (row.get("paid_on", LocalDate::class.java) != null || amount <= 0) return Outcome.NotOpen
        val details = stored(database) ?: return Outcome.NoPaymentDetails
        return Outcome.Ready(Payable(
            row.get("name", String::class.java), row.get("due_date", LocalDate::class.java), amount,
            row.get("first_name", String::class.java), row.get("last_name", String::class.java),
            SwissPayments.reference(details.iban, row.get("period_no", Int::class.java), row.get("fee_no", Int::class.java)),
            details))
    }

    companion object {
        fun stored(db: DSLContext): Details? = db.fetchOne(
            """SELECT iban, holder_name, street, building_number, postal_code, town, country
               FROM club_payment_details LIMIT 1""")?.let(::toDetails)

        private fun toDetails(it: Record) = Details(
            it.get("iban", String::class.java), it.get("holder_name", String::class.java),
            it.get("street", String::class.java), it.get("building_number", String::class.java),
            it.get("postal_code", String::class.java), it.get("town", String::class.java),
            it.get("country", String::class.java))

        /** Field rules of the Swiss QR bill (structured address); null for anything invalid. */
        fun validate(
            iban: String, holderName: String, street: String?, buildingNumber: String?, postalCode: String,
            town: String, country: String,
        ): Details? {
            fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
            fun valid(value: String?, max: Int) = value == null || (value.length <= max && value.none(Character::isISOControl))
            val compact = SwissPayments.iban(iban) ?: return null
            val name = text(holderName)
            val streetText = text(street)
            val number = text(buildingNumber)
            val postal = text(postalCode)
            val townText = text(town)
            if (name == null || postal == null || townText == null || country !in setOf("CH", "LI")) return null
            if (number != null && streetText == null) return null
            if (!valid(name, 70) || !valid(streetText, 70) || !valid(number, 16) || !valid(postal, 16) || !valid(townText, 35)) {
                return null
            }
            return Details(compact, name, streetText, number, postal, townText, country)
        }
    }
}

/**
 * The A4 PDF: a short header (club, what the fee is for, amount, due date, reference) above the Swiss QR bill
 * (receipt and payment part) drawn by qrbill-generator. The payer's address stays empty: addresses are not stored.
 */
internal object QrBillPdf {
    val languages = setOf("de", "en", "fr", "it")

    private class Labels(val language: Language, val title: String, val forWhom: String, val amount: String,
                         val due: String, val reference: String)

    private val labels = mapOf(
        "de" to Labels(Language.DE, "Mitgliederbeitrag", "Für", "Betrag", "Fällig am", "Referenz"),
        "en" to Labels(Language.EN, "Membership fee", "For", "Amount", "Due on", "Reference"),
        "fr" to Labels(Language.FR, "Cotisation", "Pour", "Montant", "Échéance", "Référence"),
        "it" to Labels(Language.IT, "Quota sociale", "Per", "Importo", "Scadenza", "Riferimento"),
    )

    /** Characters outside the Swiss payment character set become "?", so the embedded font has every glyph. */
    private fun clean(text: String): String =
        text.map { if (SPSCharacterSet.EXTENDED_LATIN.contains(it)) it else '?' }.joinToString("")

    /** "<period> - <first> <last>", trimmed to the 140 characters of the unstructured message. */
    fun message(bill: QrBills.Payable): String = clean("${bill.periodName} - ${bill.firstName} ${bill.lastName}").take(140)

    fun render(bill: QrBills.Payable, language: String): ByteArray {
        val words = labels.getValue(language)
        val details = bill.details
        val qr = Bill()
        qr.setAccount(details.iban)
        qr.setAmount(BigDecimal.valueOf(bill.amountCents.toLong(), 2))
        qr.setCurrency("CHF")
        val creditor = Address()
        creditor.setName(details.holderName)
        details.street?.let(creditor::setStreet)
        details.buildingNumber?.let(creditor::setHouseNo)
        creditor.setPostalCode(details.postalCode)
        creditor.setTown(details.town)
        creditor.setCountryCode(details.country)
        qr.setCreditor(creditor)
        qr.setReference(bill.reference)
        qr.setUnstructuredMessage(message(bill))
        val format = BillFormat()
        format.setGraphicsFormat(GraphicsFormat.PDF)
        format.setOutputSize(OutputSize.A4_PORTRAIT_SHEET)
        format.setLanguage(words.language)
        qr.setFormat(format)

        val amount = String.format(Locale.ROOT, "CHF %d.%02d", bill.amountCents / 100, bill.amountCents % 100)
        val due = bill.dueDate.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
        return PDFCanvas(QRBill.A4_PORTRAIT_WIDTH, QRBill.A4_PORTRAIT_HEIGHT, PDFFontSettings.embeddedLiberationSans()).use { canvas ->
            // Millimetres from the bottom left corner; the QR bill takes the lowest 105 mm.
            canvas.putText(clean(details.holderName), 20.0, 272.0, 10, false)
            canvas.putText(clean("${words.title}: ${bill.periodName}").take(70), 20.0, 250.0, 14, true)
            val lines = listOf(
                words.forWhom to "${bill.firstName} ${bill.lastName}",
                words.amount to amount,
                words.due to due,
                words.reference to SwissPayments.display(bill.reference),
            )
            lines.forEachIndexed { index, (label, value) ->
                val y = 236.0 - index * 7.0
                canvas.putText(clean(label), 20.0, y, 10, true)
                canvas.putText(clean(value), 55.0, y, 10, false)
            }
            QRBill.draw(qr, canvas)
            canvas.toByteArray()
        }
    }
}
