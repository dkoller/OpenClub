package org.openclub.api.notifications

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId

/** Email settings come only from the environment (OpenAPI 1.19.0); a partial setup stops startup. */
class MailConfigTest {
    private val complete = mapOf("OPENCLUB_SMTP_HOST" to "mail.example.ch", "OPENCLUB_SMTP_USERNAME" to "club@example.ch",
        "OPENCLUB_SMTP_PASSWORD" to "not-a-real-password", "OPENCLUB_SMTP_FROM" to "FC Example <club@example.ch>")

    @Test
    fun withoutAHostEmailIsOff() {
        assertNull(MailConfig.fromEnvironment(emptyMap()))
        assertNull(MailConfig.fromEnvironment(mapOf("OPENCLUB_SMTP_HOST" to " ")))
    }

    @Test
    fun defaultsAreImplicitTlsOn465AndZurichTime() {
        val config = requireNotNull(MailConfig.fromEnvironment(complete))
        assertEquals(465, config.port)
        assertTrue(config.implicitTls)
        assertEquals("club@example.ch", config.from.address)
        assertEquals(ZoneId.of("Europe/Zurich"), config.zone)
        assertFalse("not-a-real-password" in config.toString(), "the password is never printed")
        val starttls = requireNotNull(MailConfig.fromEnvironment(complete + ("OPENCLUB_SMTP_PORT" to "587")))
        assertFalse(starttls.implicitTls)
        assertEquals(ZoneId.of("Europe/Paris"),
            requireNotNull(MailConfig.fromEnvironment(complete + ("OPENCLUB_TIME_ZONE" to "Europe/Paris"))).zone)
    }

    @Test
    fun partialOrInvalidSettingsAreRejected() {
        for (broken in listOf(
            complete - "OPENCLUB_SMTP_PASSWORD", complete - "OPENCLUB_SMTP_USERNAME", complete - "OPENCLUB_SMTP_FROM",
            complete + ("OPENCLUB_SMTP_HOST" to "Mail.Example.ch"), complete + ("OPENCLUB_SMTP_HOST" to "mail.example.ch:25"),
            complete + ("OPENCLUB_SMTP_PORT" to "0"), complete + ("OPENCLUB_SMTP_PORT" to "smtp"),
            complete + ("OPENCLUB_SMTP_TLS" to "none"), complete + ("OPENCLUB_SMTP_FROM" to "not an address"),
        )) {
            assertThrows<IllegalArgumentException> { MailConfig.fromEnvironment(broken) }
        }
        assertThrows<java.time.DateTimeException> { MailConfig.fromEnvironment(complete + ("OPENCLUB_TIME_ZONE" to "Mars/Base")) }
    }

    @Test
    fun subjectsNeverCarryLineBreaks() {
        assertEquals("[Club] Neu: A B", Texts.of("de").subject("Club", "Neu", "A\r\nB"))
        assertEquals("New", Texts.of("en").newEvents)
        assertEquals("Neu", Texts.of("xx").newEvents, "an unknown language falls back to German")
    }
}
