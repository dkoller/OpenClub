package org.openclub.api.members

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Database-free CSV handling for the member list (OpenAPI 1.13.0, ADR-0008 addendum 17): reading a file saved from
 * Excel (UTF-8 with or without BOM, or Windows-1252; semicolon or comma separated; quoted cells) and writing one that
 * Excel opens directly. Cells that a spreadsheet would treat as a formula are neutralized on export and restored on
 * import. Every limit violation throws [IllegalArgumentException].
 */
internal object MemberCsv {
    const val MAX_ROWS = 1000

    enum class Field { FIRST_NAME, LAST_NAME, DATE_OF_BIRTH, EMAIL, PHONE, GENDER, PLAYER }

    enum class Problem { FIRST_NAME, LAST_NAME, DATE_OF_BIRTH, EMAIL, PHONE, GENDER, PLAYER, ADULT_CONTACTS, GENDER_REQUIRED, DUPLICATE_IN_FILE }

    data class Row(
        val line: Int, val firstName: String, val lastName: String, val dateOfBirth: LocalDate?,
        val email: String?, val phone: String?, val gender: String?, val isPlayer: Boolean, val problems: Set<Problem>,
    )

    /** Header names in German, English, French and Italian, compared after [normalize]. */
    private val headers: Map<String, Field> = mapOf(
        Field.FIRST_NAME to listOf("first name", "firstname", "given name", "vorname", "prénom", "prenom", "nome"),
        Field.LAST_NAME to listOf("last name", "lastname", "surname", "family name", "nachname", "familienname", "nom",
            "nom de famille", "cognome"),
        Field.DATE_OF_BIRTH to listOf("date of birth", "birth date", "birthdate", "born", "geburtsdatum", "geboren",
            "date de naissance", "data di nascita"),
        Field.EMAIL to listOf("email", "e-mail", "mail", "courriel"),
        Field.PHONE to listOf("phone", "telephone", "mobile", "telefon", "handy", "natel", "téléphone", "telefono", "cellulare"),
        Field.GENDER to listOf("gender", "geschlecht", "genre", "genere", "sesso"),
        Field.PLAYER to listOf("player", "spieler", "spielerin", "joueur", "joueuse", "giocatore", "giocatrice"),
    ).flatMap { (field, names) -> names.map { normalize(it) to field } }.toMap()

    private val yes = setOf("yes", "y", "ja", "j", "oui", "si", "sì", "true", "1", "x")
    private val no = setOf("", "no", "n", "nein", "non", "false", "0")

    private fun normalize(value: String) = value.trim().trim('*', ':').trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** UTF-8 (a BOM is dropped) when the bytes are valid UTF-8, otherwise Windows-1252, Excel's older "CSV" format. */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }

    /** RFC 4180 cells; the separator is whichever of ';' and ',' occurs more often outside quotes in the first line. */
    fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val separator = if (firstLine.count { it == ';' } >= firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == separator -> { row += cell.toString(); cell.clear() }
                c == '\r' -> Unit
                c == '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            require(rows.size <= MAX_ROWS + 1) { "Too many rows" }
            i++
        }
        require(!quoted) { "Unterminated quote" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows.filter { line -> line.any { it.isNotBlank() } }
    }

    /** 31.12.2010, 1.2.2010 or 2010-12-31. */
    private fun date(value: String): LocalDate? = listOf("d.M.uuuu", "uuuu-MM-dd").firstNotNullOfOrNull { pattern ->
        // Strict: 31.02.2010 is an error, not 28 February.
        try { LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)) }
        catch (_: DateTimeParseException) { null }
    }

    /** Restores a cell that the export neutralized ('=…, '+…, '-…, '@…). */
    private fun restore(value: String): String =
        if (value.length > 1 && value[0] == '\'' && value[1] in "=+-@\t\r") value.substring(1) else value

    /**
     * The data rows with their problems. Needs first name, last name and date of birth columns; other known columns are
     * optional, unknown ones (such as role or teams from an export) are ignored. A duplicate within the file is flagged
     * on its later rows. [adultAge] and [genderRequired] are the club's current rules.
     */
    fun rows(table: List<List<String>>, adultAge: Int, genderRequired: Boolean, today: LocalDate): List<Row> {
        require(table.isNotEmpty()) { "Empty file" }
        val columns = table.first().mapIndexedNotNull { index, name -> headers[normalize(name)]?.let { it to index } }
        val positions = columns.groupBy({ it.first }, { it.second })
        require(positions.values.all { it.size == 1 }) { "Duplicate column" }
        require(Field.FIRST_NAME in positions && Field.LAST_NAME in positions && Field.DATE_OF_BIRTH in positions) { "Missing column" }
        require(table.size - 1 <= MAX_ROWS) { "Too many rows" }
        val seen = mutableSetOf<Triple<String, String, LocalDate>>()
        return table.drop(1).mapIndexed { index, cells ->
            fun cell(field: Field) = positions[field]?.single()?.let { cells.getOrNull(it) }?.let(::restore)?.trim().orEmpty()
            val problems = mutableSetOf<Problem>()
            fun <T> check(problem: Problem, block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { problems += problem; null }
            val first = check(Problem.FIRST_NAME) { MemberFieldValidation.name(cell(Field.FIRST_NAME)) }
            val last = check(Problem.LAST_NAME) { MemberFieldValidation.name(cell(Field.LAST_NAME)) }
            val born = check(Problem.DATE_OF_BIRTH) {
                MemberFieldValidation.birthDate(requireNotNull(date(cell(Field.DATE_OF_BIRTH))) { "date" })
            }
            val email = check(Problem.EMAIL) { MemberFieldValidation.email(cell(Field.EMAIL)) }
            val phone = check(Problem.PHONE) { MemberFieldValidation.phone(cell(Field.PHONE)) }
            val gender = check(Problem.GENDER) { MemberFieldValidation.gender(cell(Field.GENDER)) }
            val playerText = cell(Field.PLAYER).lowercase(Locale.ROOT)
            val player = when (playerText) { in yes -> true; in no -> false; else -> { problems += Problem.PLAYER; false } }
            if (born != null && !born.isAfter(today.minusYears(adultAge.toLong())) &&
                Problem.EMAIL !in problems && Problem.PHONE !in problems && (email == null || phone == null)) {
                problems += Problem.ADULT_CONTACTS
            }
            if (genderRequired && player && gender == null && Problem.GENDER !in problems) problems += Problem.GENDER_REQUIRED
            if (first != null && last != null && born != null &&
                !seen.add(Triple(first.lowercase(Locale.ROOT), last.lowercase(Locale.ROOT), born))) {
                problems += Problem.DUPLICATE_IN_FILE
            }
            Row(index + 2, first ?: cell(Field.FIRST_NAME).take(200), last ?: cell(Field.LAST_NAME).take(200), born,
                email, phone, gender, player, problems)
        }
    }

    /** One CSV cell: quoted, inner quotes doubled, and a leading =, +, -, @, tab or CR neutralized with an apostrophe. */
    fun cell(value: String?): String {
        val text = value.orEmpty()
        val safe = if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun line(values: List<String?>): String = values.joinToString(";") { cell(it) } + "\r\n"

    private val exportHeaders = mapOf(
        "de" to listOf("Vorname", "Nachname", "Geburtsdatum", "E-Mail", "Telefon", "Geschlecht", "Spieler", "Rolle", "Teams"),
        "en" to listOf("First name", "Last name", "Date of birth", "Email", "Phone", "Gender", "Player", "Role", "Teams"),
        "fr" to listOf("Prénom", "Nom", "Date de naissance", "E-mail", "Téléphone", "Genre", "Joueur", "Rôle", "Équipes"),
        "it" to listOf("Nome", "Cognome", "Data di nascita", "Email", "Telefono", "Genere", "Giocatore", "Ruolo", "Squadre"),
    )
    private val exportYes = mapOf("de" to "ja", "en" to "yes", "fr" to "oui", "it" to "sì")
    private val exportNo = mapOf("de" to "nein", "en" to "no", "fr" to "non", "it" to "no")

    val languages: Set<String> = exportHeaders.keys

    data class ExportRow(
        val firstName: String, val lastName: String, val dateOfBirth: LocalDate, val email: String?, val phone: String?,
        val gender: String?, val isPlayer: Boolean, val role: String, val teams: List<String>,
    )

    /** The whole file with a UTF-8 BOM, so Excel picks the right encoding. Dates as 31.12.2010. */
    fun export(language: String, rows: List<ExportRow>): String {
        val headers = requireNotNull(exportHeaders[language]) { "language" }
        val dates = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        return buildString {
            append('﻿')
            append(line(headers))
            rows.forEach {
                append(line(listOf(it.firstName, it.lastName, it.dateOfBirth.format(dates), it.email, it.phone, it.gender,
                    if (it.isPlayer) exportYes.getValue(language) else exportNo.getValue(language), it.role,
                    it.teams.joinToString(", "))))
            }
        }
    }
}
