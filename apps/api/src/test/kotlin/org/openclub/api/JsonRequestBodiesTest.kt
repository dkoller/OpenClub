package org.openclub.api

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Database-free tests for the shared JSON request-body boundary used by all admin routes. */
class JsonRequestBodiesTest {
    @Test
    fun acceptsOnlyASingleJsonContentType() {
        assertTrue(isJsonContentType(listOf("application/json")))
        assertTrue(isJsonContentType(listOf("application/json; charset=utf-8")))
        assertTrue(isJsonContentType(listOf("Application/JSON")))
        for (bad in listOf(null, emptyList(), listOf("text/plain"), listOf("application/json+evil"),
            listOf("application/json", "application/json"), listOf(""), listOf(";;;"), listOf("application/"))) {
            assertFalse(isJsonContentType(bad), "$bad")
        }
    }

    @Test
    fun readsBodiesUpToTheLimitAndRejectsEmptyOrOversizedOnes() = runBlocking {
        val exact = ByteArray(MAX_JSON_BODY_BYTES) { 'a'.code.toByte() }
        assertArrayEquals(exact, readBounded(ByteReadChannel(exact), MAX_JSON_BODY_BYTES))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readBounded(ByteReadChannel(ByteArray(MAX_JSON_BODY_BYTES + 1)), MAX_JSON_BODY_BYTES) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readBounded(ByteReadChannel(ByteArray(0)), MAX_JSON_BODY_BYTES) }
        }
        Unit
    }

    @Test
    fun parsesOnlyObjectsWithTheAllowedKeySet() {
        fun parse(json: String, required: Set<String>, optional: Set<String> = emptySet()) =
            parseJsonObject(json.toByteArray(), required, optional)
        assertEquals("Ada", parse("""{"name":"Ada"}""", setOf("name")).requiredString("name"))
        assertEquals(setOf("a", "b"), parse("""{"a":"1","b":"2"}""", setOf("a"), setOf("b")).keys)
        assertEquals(setOf("a"), parse("""{"a":"1"}""", setOf("a"), setOf("b")).keys)
        for ((json, required, optional) in listOf(
            Triple("""{"name":"Ada","role":"ADMIN"}""", setOf("name"), emptySet()), // smuggled field
            Triple("""{}""", setOf("name"), emptySet()),                            // missing field
            Triple("""["name"]""", setOf("name"), emptySet()),                      // not an object
            Triple(""""name"""", setOf("name"), emptySet()),
            Triple("""null""", setOf("name"), emptySet()),
            Triple("""{"name":""", setOf("name"), emptySet()),                       // truncated
            Triple("""{"b":"2"}""", setOf("a"), setOf("b")),
        )) {
            assertThrows(IllegalArgumentException::class.java, { parse(json, required, optional) }, json)
        }
    }

    @Test
    fun requiredStringRejectsNonStrings() {
        for (json in listOf("""{"k":1}""", """{"k":true}""", """{"k":null}""", """{"k":{}}""", """{"k":[]}""", """{}""")) {
            assertThrows(IllegalArgumentException::class.java,
                { parseJsonObject(json.toByteArray(), emptySet(), setOf("k")).requiredString("k") }, json)
        }
    }

    @Test
    fun routeMapsEveryViolationToBadRequestWithoutADatabase() = testApplication {
        application {
            routing {
                post("/probe") {
                    try {
                        call.respondText(call.receiveJsonObject(setOf("name")).requiredString("name"))
                    } catch (_: IllegalArgumentException) {
                        call.respondText("invalid", status = HttpStatusCode.BadRequest)
                    }
                }
            }
        }
        suspend fun send(body: String, type: String? = "application/json") = client.post("/probe") {
            if (type != null) header(HttpHeaders.ContentType, type)
            setBody(body)
        }
        val ok = send("""{"name":"Ada"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("Ada", ok.bodyAsText())
        // Malformed header values are covered by acceptsOnlyASingleJsonContentType: the
        // Ktor test client refuses to send them, but they map to 400 rather than 503.
        for (type in listOf(null, "text/plain")) {
            assertEquals(HttpStatusCode.BadRequest, send("""{"name":"Ada"}""", type).status, "$type")
        }
        val oversized = """{"name":"${"a".repeat(MAX_JSON_BODY_BYTES)}"}"""
        for (body in listOf("", oversized, """{"name":"Ada","extra":1}""", """{"name":7}""", "not json")) {
            assertEquals(HttpStatusCode.BadRequest, send(body).status, body.take(40))
        }
    }
}
