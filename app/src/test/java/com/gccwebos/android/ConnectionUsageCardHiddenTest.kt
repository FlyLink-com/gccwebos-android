package com.gccwebos.android

import com.gccwebos.android.portal.LocalSubscriptionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUsageCardHiddenTest {
    private val ready = LocalSubscriptionStatus.READY

    @Test fun localConfigWithoutVerifiedUsageHidesTheCardWhenSignedOutOrManual() {
        assertTrue(connectionUsageCardHidden(authenticated = false, manualSource = true, hasSubscriptionUsage = false, status = ready))
        assertTrue(connectionUsageCardHidden(authenticated = false, manualSource = false, hasSubscriptionUsage = false, status = ready))
        assertTrue(connectionUsageCardHidden(authenticated = true, manualSource = true, hasSubscriptionUsage = false, status = ready))
    }

    @Test fun verifiedSubscriptionUsageKeepsTheCard() {
        assertFalse(connectionUsageCardHidden(false, true, hasSubscriptionUsage = true, status = ready))
    }

    @Test fun signedInAccountSourceAndFirstRunKeepTheCard() {
        assertFalse(connectionUsageCardHidden(authenticated = true, manualSource = false, hasSubscriptionUsage = false, status = ready))
        assertFalse(connectionUsageCardHidden(authenticated = false, manualSource = false, hasSubscriptionUsage = false,
            status = LocalSubscriptionStatus.MISSING))
    }
}
