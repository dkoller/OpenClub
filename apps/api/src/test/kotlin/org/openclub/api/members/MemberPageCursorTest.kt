package org.openclub.api.members

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MemberPageCursorTest {
    @Test
    fun bindsOpaqueCursorToAccountAndSearchWithoutDisclosingName() {
        val actor = UUID.randomUUID()
        val after = MemberDirectory.After("PrivateSurname", "Nina", UUID.randomUUID())
        val token = MemberPageCursor.encode(actor, "nina", after)

        assertEquals(after, MemberPageCursor.decode(actor, "nina", token))
        assertFalse(token.contains("PrivateSurname"))
        assertFalse(token.contains(after.id.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            MemberPageCursor.decode(UUID.randomUUID(), "nina", token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberPageCursor.decode(actor, "other", token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val changed = token.dropLast(1) + (if (token.last() == 'A') 'B' else 'A')
            MemberPageCursor.decode(actor, "nina", changed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemberPageCursor.decode(actor, "nina", "x".repeat(2049))
        }
    }
}
