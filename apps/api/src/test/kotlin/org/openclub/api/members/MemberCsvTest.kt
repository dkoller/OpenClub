package org.openclub.api.members

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/** Reading and writing the member list as CSV (OpenAPI 1.13.0). Database-free. */
class MemberCsvTest {
    private val today = LocalDate.of(2026, 11, 19)
    private fun rows(text: String, genderRequired: Boolean = false) =
        MemberCsv.rows(MemberCsv.parse(text), 18, genderRequired, today)

    @Test
    fun excelFilesInEitherSeparatorEncodingAndLanguageAreRead() {
        val german = MemberCsv.decode("﻿Vorname;Nachname;Geburtsdatum;E-Mail;Telefon;Spieler\r\nLea;Brunner;3.2.2015;;;ja\r\n".toByteArray())
        val row = rows(german).single()
        assertEquals(listOf("Lea", "Brunner", LocalDate.of(2015, 2, 3), true, emptySet<MemberCsv.Problem>()),
            listOf(row.firstName, row.lastName, row.dateOfBirth, row.isPlayer, row.problems))
        assertEquals(2, row.line)
        // Older Excel "CSV" is Windows-1252: é and ü must survive.
        val latin = MemberCsv.decode("Prénom;Nom;Date de naissance\nRené;Müller;2010-01-31\n".toByteArray(charset("windows-1252")))
        assertEquals("René Müller", rows(latin).single().let { "${it.firstName} ${it.lastName}" })
        // Comma separated, quoted cells with separators, doubled quotes and line breaks; unknown columns ignored.
        val english = "First name,Last name,Date of birth,Teams,Email,Phone\n\"Ann, Jr.\",\"O\"\"Neil\",1990-05-06,\"U12,\nU17\",ann@example.invalid,\"+41 79 000\"\n"
        val ann = rows(english).single()
        assertEquals("Ann, Jr.", ann.firstName); assertEquals("O\"Neil", ann.lastName); assertEquals("+41 79 000", ann.phone)
        assertEquals(emptySet<MemberCsv.Problem>(), ann.problems)
        val italian = rows("Nome;Cognome;Data di nascita;Giocatore\nLuca;Frei;01.01.2016;sì\n").single()
        assertTrue(italian.isPlayer)
    }

    @Test
    fun eachRowReportsItsProblemsWithoutStoppingTheOthers() {
        val text = """
            First name;Last name;Date of birth;Email;Phone;Gender;Player
            ;Empty;2010-01-01;;;;no
            Bad;Date;31.02.2010;;;;no
            Future;Born;2027-01-01;;;;no
            Adult;Nocontacts;1990-01-01;;;;no
            Adult;Email;1990-01-01;not-an-email;+41;;no
            Kid;Player;2014-01-01;;;;maybe
            Kid;Nogender;2014-01-01;;;;yes
            Same;Twice;2012-01-01;;;;no
            same;twice;2012-01-01;;;;no
        """.trimIndent()
        val problems = rows(text, genderRequired = true).map { it.problems }
        assertEquals(listOf(
            setOf(MemberCsv.Problem.FIRST_NAME), setOf(MemberCsv.Problem.DATE_OF_BIRTH), setOf(MemberCsv.Problem.DATE_OF_BIRTH),
            setOf(MemberCsv.Problem.ADULT_CONTACTS), setOf(MemberCsv.Problem.EMAIL), setOf(MemberCsv.Problem.PLAYER),
            setOf(MemberCsv.Problem.GENDER_REQUIRED), emptySet(), setOf(MemberCsv.Problem.DUPLICATE_IN_FILE),
        ), problems)
    }

    @Test
    fun theFileItselfMustBeUsable() {
        assertThrows<IllegalArgumentException> { rows("Vorname;Geburtsdatum\nLea;2015-01-01\n") }
        assertThrows<IllegalArgumentException> { rows("Vorname;Vorname;Nachname;Geburtsdatum\n") }
        assertThrows<IllegalArgumentException> { MemberCsv.parse("a;\"b\n") }
        assertThrows<IllegalArgumentException> { rows("") }
        val many = "Vorname;Nachname;Geburtsdatum\n" + (1..1001).joinToString("\n") { "A$it;B;2010-01-01" }
        assertThrows<IllegalArgumentException> { rows(many) }
        assertEquals(1000, rows("Vorname;Nachname;Geburtsdatum\n" + (1..1000).joinToString("\n") { "A$it;B;2010-01-01" }).size)
    }

    @Test
    fun theExportNeutralizesFormulasAndReadsBackUnchanged() {
        val csv = MemberCsv.export("de", listOf(MemberCsv.ExportRow("=HYPERLINK(\"x\")", "@Sum", LocalDate.of(1990, 5, 6),
            "a@example.invalid", "+41 79 000 00 00", "-", true, "ADMIN", listOf("U12", "U17"))))
        assertTrue(csv.startsWith("﻿\"Vorname\";\"Nachname\";\"Geburtsdatum\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\";\"'@Sum\";\"06.05.1990\";\"a@example.invalid\";\"'+41 79 000 00 00\";\"'-\";\"ja\";\"ADMIN\";\"U12, U17\""))
        val back = rows(MemberCsv.decode(csv.toByteArray())).single()
        assertEquals(listOf("=HYPERLINK(\"x\")", "@Sum", "+41 79 000 00 00", "-"), listOf(back.firstName, back.lastName, back.phone, back.gender))
        assertThrows<IllegalArgumentException> { MemberCsv.export("es", emptyList()) }
    }
}
