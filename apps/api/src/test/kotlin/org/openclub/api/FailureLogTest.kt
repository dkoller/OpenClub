package org.openclub.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

class FailureLogTest {
    @Test
    fun describesClassesStateAndFramesButNeverTheMessage() {
        val failure = IllegalStateException("member mara@example.invalid could not be saved",
            SQLException("duplicate key value (email)=(mara@example.invalid)", "23505"))
        val text = describeFailure(failure)
        assertTrue(text.startsWith("java.lang.IllegalStateException"), text)
        assertTrue(text.contains("caused by java.sql.SQLException [SQL state 23505]"), text)
        assertTrue(text.contains("at org.openclub.api.FailureLogTest.describesClassesStateAndFramesButNeverTheMessage"), text)
        assertFalse(text.contains("mara@example.invalid"), "exception messages can quote member data and stay out")
    }

    @Test
    fun logsTheRouteShapeWithoutIdsOrSecrets() {
        assertEquals("/api/v1/members/{id}/role", routeShape("/api/v1/members/1b4e28ba-2fa1-11d2-883f-0016d3cca427/role"))
        assertEquals("/api/v1/calendar/{secret}", routeShape("/api/v1/calendar/Zq3xV0aB_9fK2mLp7RtY4wQe"))
        assertEquals("/api/v1/fee-periods", routeShape("/api/v1/fee-periods"))
    }
}
