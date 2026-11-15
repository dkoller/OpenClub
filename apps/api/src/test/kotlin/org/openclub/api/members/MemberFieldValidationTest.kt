package org.openclub.api.members

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MemberFieldValidationTest {
    @Test
    fun validatesBirthDateBoundaries() {
        val today = LocalDate.now()
        assertEquals(today, MemberFieldValidation.birthDate(today))
        assertEquals(LocalDate.of(1850, 1, 2),
            MemberFieldValidation.birthDate(LocalDate.of(1850, 1, 2)))
        assertThrows(IllegalArgumentException::class.java) {
            MemberFieldValidation.birthDate(LocalDate.of(1850, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberFieldValidation.birthDate(today.plusDays(1))
        }
    }

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
