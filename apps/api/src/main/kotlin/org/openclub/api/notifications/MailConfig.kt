package org.openclub.api.notifications

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.time.ZoneId
import java.util.Properties

/**
 * Outgoing email settings (OpenAPI 1.19.0, ADR-0008 addendum 23), read from the environment that the operator's
 * deployment config writes; nothing is hard-coded. Without `OPENCLUB_SMTP_HOST` email is off and nothing is queued.
 *
 * - `OPENCLUB_SMTP_HOST`, `OPENCLUB_SMTP_PORT` (default 465), `OPENCLUB_SMTP_TLS` (`implicit`, the default for 465,
 *   or `starttls`, the default otherwise; plain text is never used), `OPENCLUB_SMTP_USERNAME`,
 *   `OPENCLUB_SMTP_PASSWORD` and `OPENCLUB_SMTP_FROM` (an address, optionally `Name <address>`).
 * - `OPENCLUB_TIME_ZONE` for times in emails (default Europe/Zurich).
 */
internal data class MailConfig(
    val host: String, val port: Int, val implicitTls: Boolean, val username: String, val password: String,
    val from: InternetAddress, val zone: ZoneId,
) {
    override fun toString() = "MailConfig(host=$host, port=$port, implicitTls=$implicitTls, from=${from.address})"

    companion object {
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /** Null when email is not configured; an exception for a partial or invalid configuration (fail at startup). */
        fun fromEnvironment(env: Map<String, String>): MailConfig? {
            val host = env["OPENCLUB_SMTP_HOST"]?.trim().orEmpty()
            if (host.isEmpty()) return null
            require(HOST.matches(host)) { "OPENCLUB_SMTP_HOST must be a lowercase DNS name" }
            val port = env["OPENCLUB_SMTP_PORT"]?.trim()?.takeIf(String::isNotEmpty)?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { value -> value in 1..65535 }) { "OPENCLUB_SMTP_PORT must be a port number" }
            } ?: 465
            val tls = when (env["OPENCLUB_SMTP_TLS"]?.trim()?.ifEmpty { null } ?: if (port == 465) "implicit" else "starttls") {
                "implicit" -> true
                "starttls" -> false
                else -> throw IllegalArgumentException("OPENCLUB_SMTP_TLS must be implicit or starttls")
            }
            val username = env["OPENCLUB_SMTP_USERNAME"].orEmpty()
            val password = env["OPENCLUB_SMTP_PASSWORD"].orEmpty()
            require(username.isNotBlank() && password.isNotEmpty()) { "OPENCLUB_SMTP_USERNAME and OPENCLUB_SMTP_PASSWORD are required" }
            val from = try {
                InternetAddress(requireNotNull(env["OPENCLUB_SMTP_FROM"]?.trim()?.ifEmpty { null }) { "OPENCLUB_SMTP_FROM is required" }, true)
            } catch (failure: jakarta.mail.internet.AddressException) {
                throw IllegalArgumentException("OPENCLUB_SMTP_FROM is not an email address", failure)
            }
            val zone = ZoneId.of(env["OPENCLUB_TIME_ZONE"]?.trim()?.ifEmpty { null } ?: "Europe/Zurich")
            return MailConfig(host, port, tls, username, password, from, zone)
        }
    }
}

/** Sends one plain-text message; throws on any failure so the outbox can retry. */
internal fun interface Mailer {
    fun send(to: String, subject: String, body: String)
}

/** SMTP through Jakarta Mail (Eclipse Angus), always encrypted and with certificate and host name checks. */
internal class SmtpMailer(private val config: MailConfig) : Mailer {
    private val session: Session = Session.getInstance(Properties().apply {
        val protocol = if (config.implicitTls) "smtps" else "smtp"
        put("mail.transport.protocol", protocol)
        put("mail.$protocol.host", config.host)
        put("mail.$protocol.port", config.port.toString())
        put("mail.$protocol.auth", "true")
        put("mail.$protocol.ssl.checkserveridentity", "true")
        put("mail.$protocol.connectiontimeout", "10000")
        put("mail.$protocol.timeout", "20000")
        put("mail.$protocol.writetimeout", "20000")
        if (!config.implicitTls) {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
    })

    override fun send(to: String, subject: String, body: String) {
        val message = MimeMessage(session).apply {
            setFrom(config.from)
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            setHeader("Auto-Submitted", "auto-generated")
        }
        Transport.send(message, config.username, config.password)
    }
}
