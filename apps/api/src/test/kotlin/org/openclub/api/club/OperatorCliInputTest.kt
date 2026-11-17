package org.openclub.api.club

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Database-free checks of the operator safety gates in BootstrapCli and AdminRecoveryCli. */
class OperatorCliInputTest {
    private val account = "7f53bd36-6b66-4ce0-a4ed-3ca1b8411361"
    private val bootstrap = mapOf(
        "OPENCLUB_BOOTSTRAP_CONFIRM" to "create-first-club",
        "OPENCLUB_BOOTSTRAP_ACCOUNT_ID" to account,
        "OPENCLUB_BOOTSTRAP_CLUB_NAME" to " Synthetic Club ",
        "OPENCLUB_BOOTSTRAP_CLUB_SLUG" to "synthetic-club",
        "OPENCLUB_BOOTSTRAP_FIRST_NAME" to "Ada",
        "OPENCLUB_BOOTSTRAP_LAST_NAME" to "Example",
        "OPENCLUB_BOOTSTRAP_BIRTH_DATE" to "1990-01-01",
        "OPENCLUB_BOOTSTRAP_EMAIL" to "ada@example.invalid",
        "OPENCLUB_BOOTSTRAP_PHONE" to "+41000000000",
    )
    private val recovery = mapOf(
        "OPENCLUB_RECOVERY_CONFIRM" to "rebind-sole-admin",
        "OPENCLUB_RECOVERY_CLUB_ID" to "00000000-0000-4000-8000-000000000001",
        "OPENCLUB_RECOVERY_MEMBER_ID" to "00000000-0000-4000-8000-000000000002",
        "OPENCLUB_RECOVERY_OLD_ACCOUNT_ID" to "00000000-0000-4000-8000-000000000003",
        "OPENCLUB_RECOVERY_NEW_ACCOUNT_ID" to "00000000-0000-4000-8000-000000000004",
    )

    @Test
    fun bootstrapRequiresTheExactConfirmationPhrase() {
        for (confirm in listOf(null, "", "yes", "create-first-club ", "CREATE-FIRST-CLUB", "rebind-sole-admin")) {
            val env = if (confirm == null) bootstrap - "OPENCLUB_BOOTSTRAP_CONFIRM"
            else bootstrap + ("OPENCLUB_BOOTSTRAP_CONFIRM" to confirm)
            val error = assertThrows(IllegalArgumentException::class.java, { bootstrapInput(env) }, "$confirm")
            assertTrue(error.message!!.contains("OPENCLUB_BOOTSTRAP_CONFIRM"))
        }
    }

    @Test
    fun bootstrapRequiresEveryFieldAndTrimsValues() {
        val input = bootstrapInput(bootstrap)
        assertEquals(UUID.fromString(account), input.accountId)
        assertEquals("Synthetic Club", input.clubName)
        assertEquals(LocalDate.of(1990, 1, 1), input.birthDate)
        for (key in bootstrap.keys - "OPENCLUB_BOOTSTRAP_CONFIRM") {
            for (env in listOf(bootstrap - key, bootstrap + (key to "   "))) {
                val error = assertThrows(IllegalArgumentException::class.java, { bootstrapInput(env) }, key)
                assertTrue(error.message!!.contains(key), key)
            }
        }
        assertThrows(DateTimeParseException::class.java) {
            bootstrapInput(bootstrap + ("OPENCLUB_BOOTSTRAP_BIRTH_DATE" to "01.01.1990"))
        }
    }

    @Test
    fun recoveryRequiresConfirmationAndFourCanonicalIds() {
        val input = recoveryInput(recovery)
        assertEquals(UUID.fromString("00000000-0000-4000-8000-000000000004"), input.replacementAccountId)
        for (confirm in listOf("", "create-first-club", "rebind-sole-admin!")) {
            assertThrows(IllegalArgumentException::class.java) {
                recoveryInput(recovery + ("OPENCLUB_RECOVERY_CONFIRM" to confirm))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { recoveryInput(recovery - "OPENCLUB_RECOVERY_CONFIRM") }
        for (key in recovery.keys - "OPENCLUB_RECOVERY_CONFIRM") {
            for (value in listOf(null, " ", "not-a-uuid", "1-1-1-1-1")) {
                val env = if (value == null) recovery - key else recovery + (key to value)
                val error = assertThrows(IllegalArgumentException::class.java, { recoveryInput(env) }, "$key=$value")
                assertTrue(error.message!!.contains(key), "$key=$value")
            }
        }
    }

    @Test
    fun canonicalUuidRejectsShortenedSpellings() {
        assertEquals(UUID.fromString(account), canonicalUuid(account.uppercase(), "K"))
        assertEquals(UUID.fromString(account), canonicalUuid(" $account ", "K"))
        for (bad in listOf("1-1-1-1-1", "7f53bd36-6b66-4ce0-a4ed-3ca1b841136", "7f53bd366b664ce0a4ed3ca1b8411361", "")) {
            assertThrows(IllegalArgumentException::class.java, { canonicalUuid(bad, "K") }, bad)
        }
    }
}
