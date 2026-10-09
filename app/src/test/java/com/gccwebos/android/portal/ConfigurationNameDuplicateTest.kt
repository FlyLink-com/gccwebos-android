package com.gccwebos.android.portal

import com.gccwebos.android.manualimport.clipboardLinkText
import com.gccwebos.android.manualimport.looksLikeSubscriptionLink
import org.junit.Assert.*
import org.junit.Test

class ConfigurationNameDuplicateTest {
    @Test fun duplicateNamesCompareTrimmedCaseInsensitiveAndWidthNormalized() {
        val saved = listOf("账户订阅", "配置 2", "Kitty")
        assertTrue(ConfigurationName.conflicts("配置 2", saved))
        assertTrue(ConfigurationName.conflicts("  配置 2  ", saved))
        assertTrue(ConfigurationName.conflicts("配置  2", saved))
        assertTrue(ConfigurationName.conflicts("kitty", saved))
        assertTrue(ConfigurationName.conflicts("ＫＩＴＴＹ", saved)) // full-width letters
        assertTrue(ConfigurationName.conflicts("配置 ２", saved))
        assertTrue(ConfigurationName.conflicts("账户订阅", saved))
        assertFalse(ConfigurationName.conflicts("配置 3", saved))
        assertFalse(ConfigurationName.conflicts("Kitty 2", saved))
        assertFalse(ConfigurationName.conflicts("   ", saved))
        assertFalse(ConfigurationName.conflicts("配置 2", emptyList()))
    }

    @Test fun storedDuplicatesFromOlderVersionsAreFlaggedOnlyAgainstOtherEntries() {
        // Two pre-existing "配置 2" entries: renaming either one must leave the duplicate name.
        val others = listOf("配置 2", "配置 3")
        assertTrue(ConfigurationName.conflicts("配置 2", others))
        assertFalse(ConfigurationName.conflicts("配置 4", others))
        // The default for the next import continues after the highest number and never collides.
        val next = nextConfigurationName(listOf("配置 2", "配置 2", "配置 3"))
        assertEquals("配置 4", next)
        assertFalse(ConfigurationName.conflicts(next, listOf("配置 2", "配置 2", "配置 3")))
    }

    @Test fun duplicateMessageIsTheAgreedChineseText() {
        assertEquals("已有同名订阅，请重新命名你的订阅", ConfigurationName.DUPLICATE_MESSAGE)
    }

    @Test fun clipboardTextIsTrimmedAndEmptyClipboardPastesNothing() {
        assertNull(clipboardLinkText(null))
        assertNull(clipboardLinkText(""))
        assertNull(clipboardLinkText(" \n\t "))
        assertEquals("https://sub.invalid/a", clipboardLinkText("  https://sub.invalid/a\n"))
    }

    @Test fun clipboardSubscriptionLinksNeedAnHttpsPrefix() {
        assertTrue(looksLikeSubscriptionLink("https://sub.invalid/sub/a/synthetic"))
        assertTrue(looksLikeSubscriptionLink("  https://sub.invalid/a\n"))
        assertFalse(looksLikeSubscriptionLink(""))
        assertFalse(looksLikeSubscriptionLink("http://sub.invalid/a"))
        assertFalse(looksLikeSubscriptionLink("HTTPS://sub.invalid/a")) // same case rule as the controller
        assertFalse(looksLikeSubscriptionLink("not a link"))
        assertFalse(looksLikeSubscriptionLink("ss://synthetic"))
    }
}
