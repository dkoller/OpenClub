package org.openclub.api.fees

import net.codecrete.qrbill.generator.Payments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Database-free checks of the IBAN rules and both payment references (OpenAPI 1.26.0). */
class SwissPaymentsTest {
    @Test
    fun ibansOfSwitzerlandAndLiechtensteinWithAValidChecksumAreAccepted() {
        assertEquals("CH9300762011623852957", SwissPayments.iban("CH93 0076 2011 6238 5295 7"))
        assertEquals("CH9300762011623852957", SwissPayments.iban("ch9300762011623852957"))
        assertEquals("LI21088100002324013AA", SwissPayments.iban("LI21 0881 0000 2324 013A A"))
        assertEquals("CH4431999123000889012", SwissPayments.iban("CH44 3199 9123 0008 8901 2"))
        // Wrong checksum, another country, wrong length, other characters.
        for (bad in listOf("CH9300762011623852958", "DE89370400440532013000", "CH930076201162385295",
            "CH93007620116238529577", "CH93-0076-2011-6238-5295-7", "")) {
            assertNull(SwissPayments.iban(bad), bad)
        }
    }

    @Test
    fun aQrIbanIsRecognisedByItsInstitutionId() {
        assertTrue(SwissPayments.isQrIban("CH4431999123000889012"))
        assertFalse(SwissPayments.isQrIban("CH9300762011623852957"))
        // The library agrees.
        assertTrue(Payments.isQRIBAN("CH4431999123000889012"))
        assertFalse(Payments.isQRIBAN("CH9300762011623852957"))
    }

    @Test
    fun theRecursiveMod10CheckDigitMatchesThePublishedExample() {
        assertEquals(7, SwissPayments.mod10("21000000000313947143000901"))
        // ISO 11649 example RF18 5390 0754 7034: the remainder 80 gives the check digits 98 - 80 = 18.
        assertEquals(80, SwissPayments.mod97("539007547034RF00"))
    }

    @Test
    fun qrReferencesHave27DigitsAndAValidCheckDigit() {
        assertEquals("000000120000000000000003450", SwissPayments.qrReference(12, 345))
        assertEquals("000000010000000000000000016", SwissPayments.qrReference(1, 1))
        for ((period, fee) in listOf(1 to 1, 12 to 345, 99_999_999 to 999_999_999, 7 to 4_242)) {
            val reference = SwissPayments.qrReference(period, fee)
            assertEquals(27, reference.length)
            assertTrue(Payments.isValidQRReference(reference), reference)
        }
        assertEquals("00 00001 20000 00000 00000 03450", SwissPayments.display("000000120000000000000003450"))
    }

    @Test
    fun creditorReferencesFollowIso11649() {
        assertEquals("RF79P12M345", SwissPayments.creditorReference(12, 345))
        assertEquals("RF53P1M1", SwissPayments.creditorReference(1, 1))
        for ((period, fee) in listOf(1 to 1, 12 to 345, 99_999_999 to 999_999_999)) {
            val reference = SwissPayments.creditorReference(period, fee)
            assertTrue(reference.length <= 25, reference)
            assertTrue(Payments.isValidISO11649Reference(reference), reference)
        }
        assertEquals("RF79 P12M 345", SwissPayments.display("RF79P12M345"))
        // The account decides the kind of reference.
        assertEquals("RF79P12M345", SwissPayments.reference("CH9300762011623852957", 12, 345))
        assertEquals("000000120000000000000003450", SwissPayments.reference("CH4431999123000889012", 12, 345))
    }

    @Test
    fun paymentDetailsFollowTheQrBillFieldLimits() {
        fun details(holder: String = "FC Beispiel", street: String? = "Seeweg", number: String? = "3",
                    postal: String = "8000", town: String = "Zürich", country: String = "CH", iban: String = "CH93 0076 2011 6238 5295 7") =
            QrBills.validate(iban, holder, street, number, postal, town, country)
        val ok = details()
        assertEquals(QrBills.Details("CH9300762011623852957", "FC Beispiel", "Seeweg", "3", "8000", "Zürich", "CH"), ok)
        assertEquals(null, details(street = "  ", number = null)?.street)
        assertNotNull(details(holder = "x".repeat(70), town = "y".repeat(35), postal = "1".repeat(16), number = "9".repeat(16)))
        for (bad in listOf(
            details(holder = "x".repeat(71)), details(holder = "  "), details(street = "s".repeat(71)),
            details(number = "1".repeat(17)), details(street = null, number = "3"), details(postal = "1".repeat(17)),
            details(town = "t".repeat(36)), details(town = ""), details(country = "DE"), details(country = "ch"),
            details(iban = "CH9300762011623852958"), details(iban = "DE89370400440532013000"), details(holder = "FC\nBeispiel"),
        )) assertNull(bad)
    }

    @Test
    fun theMessageNamesThePeriodAndTheMemberWithin140Characters() {
        val details = QrBills.Details("CH9300762011623852957", "FC", null, null, "8000", "Zürich", "CH")
        val bill = QrBills.Payable("Saison 2026", LocalDate.of(2026, 12, 31), 15000, "Anna", "Müller", "RF53P1M1", details)
        assertEquals("Saison 2026 - Anna Müller", QrBillPdf.message(bill))
        assertEquals(140, QrBillPdf.message(bill.copy(periodName = "p".repeat(200))).length)
        // Characters outside the Swiss payment character set are replaced.
        assertEquals("Saison - Anna ?", QrBillPdf.message(bill.copy(periodName = "Saison", lastName = "中")))
    }

    @Test
    fun theBillIsAnA4Pdf() {
        val details = QrBills.Details("CH9300762011623852957", "FC Beispiel", "Seeweg", "3", "8000", "Zürich", "CH")
        val bill = QrBills.Payable("Saison 2026", LocalDate.of(2026, 12, 31), 15050, "Anna", "Müller",
            SwissPayments.creditorReference(1, 1), details)
        for (language in QrBillPdf.languages) {
            val pdf = QrBillPdf.render(bill, language)
            assertEquals("%PDF", pdf.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        }
        val qr = bill.copy(details = details.copy(iban = "CH4431999123000889012"), reference = SwissPayments.qrReference(1, 1))
        assertEquals("%PDF", QrBillPdf.render(qr, "de").copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }
}
