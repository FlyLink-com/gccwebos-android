package com.gccwebos.android.design

import com.gccwebos.android.portal.nodeMatchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeDisplayNameTest {
    @Test fun leadingFlagEmojiIsDroppedBecauseTheBadgeAlreadyShowsIt() {
        assertEquals("United States 01", nodeDisplayName("🇺🇸 United States 01"))
        assertEquals("香港 03", nodeDisplayName("🇭🇰香港 03"))
    }

    @Test fun namesWithoutAFlagOrWithOnlyAFlagAreUnchanged() {
        assertEquals("Hong Kong 01", nodeDisplayName("Hong Kong 01"))
        assertEquals("🇺🇸", nodeDisplayName("🇺🇸"))
        assertEquals("Synthetic 01", nodeDisplayName("Synthetic 01"))
        assertEquals("合成节点 Beta", nodeDisplayName("合成节点 Beta"))
    }

    @Test fun searchMatchesVisibleNameOrRegionNameIgnoringCaseAndFlag() {
        assertTrue(nodeMatchesQuery("🇺🇸 United States 01", "united"))
        assertTrue(nodeMatchesQuery("🇺🇸 United States 01", "美国"))
        assertTrue(nodeMatchesQuery("🇯🇵 Tokyo 02", "日本"))
        assertTrue(nodeMatchesQuery("anything", "  "))
        assertFalse(nodeMatchesQuery("🇺🇸 United States 01", "japan"))
    }
}
