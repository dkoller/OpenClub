package org.openclub.api.members

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.openclub.api.module
import org.openclub.api.database.DatabaseRuntime
import java.time.LocalDate
import java.util.UUID
import org.openclub.api.checkContract

class PublicMemberNamesTest {
    @Test
    fun anonymousDirectoryMasksMinorsAndNeverLeaksPrivateFields() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        val club = UUID.randomUUID()
        val adult = UUID.randomUUID()
        val minor = UUID.randomUUID()
        val archived = UUID.randomUUID()
        val today = LocalDate.now()

        try {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("INSERT INTO clubs (id, name, slug) VALUES (?, 'Public synthetic', ?)",
                        club, "public-" + club)
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, 'Peter', 'Smith', ?, 'adult@example.invalid', '+41000000000')""",
                        adult, today.minusYears(25),
                    )
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone)
                           VALUES (?, 'Nina', 'SensitiveSurname', ?, 'minor@example.invalid', '+41000000000')""",
                        minor, today.minusYears(17),
                    )
                    db.execute(
                        """INSERT INTO members (id, first_name, last_name, date_of_birth,
                           email, phone, archived_at) VALUES (?, 'Former', 'Member', ?,
                           'former@example.invalid', '+41000000000', NOW())""",
                        archived, today.minusYears(30),
                    )
                }
            }
            testApplication {
                application { checkContract(); module(database = database) }
                val public = client.get("/api/v1/public/members")
                assertEquals(HttpStatusCode.OK, public.status)
                assertEquals("no-store", public.headers[HttpHeaders.CacheControl])
                val body = public.bodyAsText()
                // PostgreSQL JSON text may contain insignificant spaces.
                assertEquals(2, Regex("displayName").findAll(body).count())
                assertTrue(body.indexOf("Nina S.") < body.indexOf("Peter Smith"))
                assertTrue(body.contains("Nina S."))
                assertTrue(body.contains("Peter Smith"))
                for (secret in listOf("SensitiveSurname", "example.invalid", adult.toString(),
                        minor.toString(), "Former", "dateOfBirth", "phone")) {
                    assertFalse(body.contains(secret), "Leaked private field: $secret")
                }

                // Changing the age policy must immediately change public display.
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("UPDATE clubs SET adult_age_years = 17 WHERE id = ?", club)
                    }
                }
                assertTrue(client.get("/api/v1/public/members").bodyAsText()
                    .contains("Nina SensitiveSurname"))
                runBlocking {
                    database.onDatabaseThread {
                        it.execute("UPDATE clubs SET adult_age_years = 18 WHERE id = ?", club)
                        it.execute("UPDATE members SET date_of_birth = ? WHERE id = ?",
                            today.minusYears(18), minor)
                    }
                }
                assertTrue(client.get("/api/v1/public/members").bodyAsText()
                    .contains("Nina SensitiveSurname"))
            }
        } finally {
            runBlocking {
                database.onDatabaseThread { db ->
                    db.execute("DELETE FROM members WHERE id IN (?, ?, ?)", adult, minor, archived)
                    db.execute("DELETE FROM clubs WHERE id = ?", club)
                }
            }
            database.close()
        }
    }

    @Test
    fun unavailableWithoutDatabase() = testApplication {
        application { checkContract(); module() }
        val response = client.get("/api/v1/public/members")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertFalse(response.bodyAsText().contains("members"))
    }
}
