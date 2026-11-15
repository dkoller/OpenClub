package org.openclub.api.members

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemberFieldValidationTest {
    @Test
    fun trimsAndValidatesNames() {
        assertEquals("Sam", MemberFieldValidation.name(" Sam "))
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.name(" ") }
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.name("x".repeat(201)) }
        assertEquals("x".repeat(200), MemberFieldValidation.name("x".repeat(200)))
    }

    @Test
    fun trimsOptionalFieldsAndEnforcesExistingLimits() {
        assertNull(MemberFieldValidation.email("  "))
        assertNull(MemberFieldValidation.phone(null))
        assertNull(MemberFieldValidation.gender(""))
        assertEquals("a@b", MemberFieldValidation.email(" a@b "))
        assertEquals("123", MemberFieldValidation.phone(" 123 "))
        assertEquals("Other", MemberFieldValidation.gender(" Other "))
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.email("ab") }
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.email("x".repeat(321)) }
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.phone("x".repeat(41)) }
        assertThrows(IllegalArgumentException::class.java) { MemberFieldValidation.gender("x".repeat(201)) }
        assertEquals("x".repeat(320), MemberFieldValidation.email("x".repeat(320)))
        assertEquals("x".repeat(40), MemberFieldValidation.phone("x".repeat(40)))
        assertEquals("x".repeat(200), MemberFieldValidation.gender("x".repeat(200)))
    }
}
