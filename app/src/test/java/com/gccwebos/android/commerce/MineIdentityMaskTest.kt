package com.gccwebos.android.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Mine header shows only a masked form of the verified session email. */
class MineIdentityMaskTest {
    @Test fun keepsAtMostThreeLeadingCharactersAndTheDomain() {
        assertEquals("gcc***@gmail.com", maskAccountEmail("gccltd@gmail.com"))
        assertEquals("ab***@example.org", maskAccountEmail("abcd@example.org"))
        assertEquals("a***@x.io", maskAccountEmail("ab@x.io"))
        assertEquals("a***@x.io", maskAccountEmail("a@x.io"))
        assertEquals("abc***@sub.mail.co", maskAccountEmail("  abcdefghij@sub.mail.co "))
    }

    @Test fun neverRevealsMoreThanHalfOfTheLocalPart() {
        for (local in listOf("ab", "abc", "abcd", "abcde", "abcdef", "abcdefghijklmnop")) {
            val masked = maskAccountEmail("$local@d.com")
            val kept = masked.substringBefore("***")
            assert(kept.length <= maxOf(1, (local.length + 1) / 2)) { masked }
            assertFalse(masked, masked.contains(local) && local.length > 1)
        }
    }

    @Test fun nonEmailAndBlankValuesAreHandled() {
        assertEquals("", maskAccountEmail("   "))
        assertEquals("acc***", maskAccountEmail("account-name"))
        assertEquals("***@d.com", maskAccountEmail("@d.com"))
    }
}
