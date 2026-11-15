package org.openclub.api

import org.openclub.api.auth.*
import org.openclub.api.database.*
import org.openclub.api.members.*

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun healthReturnsOk() = testApplication {
        application { module() }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }
}
