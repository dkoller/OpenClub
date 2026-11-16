package org.openclub.api

import org.openclub.api.auth.*
import org.openclub.api.database.*
import org.openclub.api.members.*

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun healthReturnsOk() = testApplication {
        application { module() }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }
    @Test
    fun readinessRequiresDatabaseButLivenessDoesNot() = testApplication {
        application { module() }
        val readiness = client.get("/api/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, readiness.status)
        assertEquals("""{"status":"unavailable"}""", readiness.bodyAsText())
        assertEquals("no-store", readiness.headers["Cache-Control"])
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun readinessChecksLivePostgresAndFailsClosedAfterDatabaseShutdown() {
        val env = System.getenv()
        assumeTrue(!env["DB_URL"].isNullOrBlank(), "Requires PostgreSQL")
        val database = requireNotNull(DatabaseRuntime.openIfConfigured(env))
        try {
            testApplication {
                application { module(database = database) }
                val ready = client.get("/api/ready")
                assertEquals(HttpStatusCode.OK, ready.status)
                assertEquals("""{"status":"ready"}""", ready.bodyAsText())
                assertEquals("no-store", ready.headers["Cache-Control"])

                database.close()
                val unavailable = client.get("/api/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
                assertEquals("""{"status":"unavailable"}""", unavailable.bodyAsText())
                assertEquals(HttpStatusCode.OK, client.get("/health").status)
            }
        } finally {
            database.close()
        }
    }

}
