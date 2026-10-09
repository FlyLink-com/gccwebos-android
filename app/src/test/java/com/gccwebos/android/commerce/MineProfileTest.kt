package com.gccwebos.android.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure presentation rules of the 我的 header and menu (round 2). */
class MineProfileTest {
    @Test fun knownEmailIsTheTitleAndTheShortIdIsASecondaryLine() {
        val text = mineIdentityText("gccltd@gmail.com", "acct_0123456789abcdef01234567")
        assertEquals("gcc***@gmail.com", text.title)
        assertEquals("ID acct_…234567", text.idLine)
    }

    @Test fun restoredSessionWithoutEmailShowsTheIdAsTitleNotTheWordAccount() {
        val text = mineIdentityText(null, "acct_0123456789abcdef01234567")
        assertEquals("ID acct_…234567", text.title)
        assertNull(text.idLine)
        assertEquals(MineIdentityText("ID acct_…234567", null), mineIdentityText("   ", "acct_0123456789abcdef01234567"))
    }

    @Test fun nothingKnownFallsBackToANeutralTitleWithoutInventingANickname() {
        assertEquals(MineIdentityText("我的账户", null), mineIdentityText(null, null))
        assertEquals(MineIdentityText("我的账户", null), mineIdentityText(null, "  "))
        assertEquals(MineIdentityText("gcc***@gmail.com", null), mineIdentityText("gccltd@gmail.com", null))
    }

    @Test fun accountIdDisplayIsShortenedMaskedOrRejected() {
        assertEquals("acct_portal_1", displayAccountId("acct_portal_1"))
        assertEquals("acct_…fedcba", displayAccountId("acct_00000000000000000fedcba"))
        assertEquals("abcd…uvwxyz", displayAccountId("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("fix***@example.invalid", displayAccountId("fixture@example.invalid"))
        assertNull(displayAccountId(""))
        assertNull(displayAccountId("acct 1"))
        assertNull(displayAccountId("acct\n1"))
    }

    @Test fun copyCopiesTheExactFullIdOnlyWhenItIsDisplayable() {
        assertEquals("acct_0123456789abcdef01234567", copyableAccountId("acct_0123456789abcdef01234567"))
        assertNull(copyableAccountId(null))
        assertNull(copyableAccountId("bad id"))
    }

    @Test fun avatarInitialComesFromTheEmailOrFallsBackToTheGlyph() {
        assertEquals("G", mineAvatarInitial("gccltd@gmail.com"))
        assertEquals("7", mineAvatarInitial("7up@x.io"))
        assertNull(mineAvatarInitial(null))
        assertNull(mineAvatarInitial(""))
        assertNull(mineAvatarInitial("_x@x.io"))
        assertNull(mineAvatarInitial("飞连@x.io"))
    }

    @Test fun onlyAnExactTelegramChannelUrlGetsThePaperPlaneAndHandle() {
        assertEquals("@flylinevpn", telegramHandle(MINE_OFFICIAL_CHANNEL_URL))
        assertTrue(isTelegramChannel("https://telegram.me/flylinevpn/"))
        assertFalse(isTelegramChannel("http://t.me/flylinevpn"))
        assertFalse(isTelegramChannel("https://t.me.evil.example/flylinevpn"))
        assertFalse(isTelegramChannel("https://x.com/flylinevpn"))
        assertFalse(isTelegramChannel("https://t.me/flylinevpn?start=1"))
        assertNull(telegramHandle("https://t.me/ab"))
    }

    @Test fun versionLabelUsesTheBuildVersionName() {
        assertEquals("当前版本 v0.4.2", mineVersionLabel("0.4.2"))
        assertEquals("当前版本 v0.4.2-debug", mineVersionLabel("0.4.2-debug"))
        assertEquals("当前版本 v1.0", mineVersionLabel("v1.0"))
        assertNull(mineVersionLabel(null))
        assertNull(mineVersionLabel(" "))
    }
}
