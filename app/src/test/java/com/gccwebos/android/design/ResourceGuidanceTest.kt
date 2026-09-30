package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ResourceGuidanceTest {
    private fun ResourceGuidanceState.refresh(
        authenticated: Boolean = true,
        account: String = "account-a",
        session: Long = 1,
        resource: Resource? = Resource.None,
        busy: Boolean = false,
        error: String? = null,
        connect: Boolean = true,
    ) = reconcile(authenticated, account, session, resource, busy, error, connect)

    @Test fun authenticatedConfirmedNoneShowsOnce() {
        val shown = ResourceGuidanceState().refresh()
        assertTrue(shown.visible)
        assertTrue(shown.shown)
        assertEquals(shown, shown.refresh())
    }
    @Test fun signedOutNeverShowsEvenWithOldNone() {
        val state = ResourceGuidanceState().refresh(authenticated = false)
        assertFalse(state.visible)
        assertFalse(state.shown)
    }
    @Test fun loadingAndErrorAndUnknownNeverConsumePrompt() {
        listOf(
            ResourceGuidanceState().refresh(busy = true),
            ResourceGuidanceState().refresh(error = "offline"),
            ResourceGuidanceState().refresh(resource = null),
            ResourceGuidanceState().refresh(resource = Resource.Reconciliation),
        ).forEach {
            assertFalse(it.visible)
            assertFalse(it.shown)
            assertTrue(it.refresh().visible)
        }
    }
    @Test fun availableDoesNotPromptEvenIfExpired() {
        val available = Resource.Available("fixture", "plan", Instant.EPOCH, false, Usage(0, null, null, "pending", null))
        assertFalse(ResourceGuidanceState().refresh(resource = available).visible)
    }
    @Test fun dismissDoesNotReturnAfterRecompositionOrSuccessfulRefresh() {
        var state = ResourceGuidanceState().refresh().dismiss()
        repeat(10) { state = state.refresh(); assertFalse(state.visible) }
        state = state.refresh(busy = true).refresh()
        assertTrue(state.shown)
        assertFalse(state.visible)
    }
    @Test fun navigationDoesNotResetDismissal() {
        val state = ResourceGuidanceState().refresh().dismiss().refresh(connect = false).refresh(connect = true)
        assertFalse(state.visible)
        assertTrue(state.shown)
    }
    @Test fun otherDestinationDoesNotConsumePromptBeforeConnect() {
        val state = ResourceGuidanceState().refresh(connect = false)
        assertFalse(state.visible)
        assertFalse(state.shown)
        assertTrue(state.refresh().visible)
    }
    @Test fun interruptedVisiblePromptIsNotRepeated() {
        val state = ResourceGuidanceState().refresh().refresh(busy = true)
        assertFalse(state.visible)
        assertFalse(state.refresh().visible)
        assertFalse(state.refresh(error = "offline").refresh().visible)
    }
    @Test fun signingOutClearsAccountLifecycle() {
        val state = ResourceGuidanceState().refresh().dismiss().refresh(authenticated = false)
        assertNull(state.accountKey)
        assertNull(state.sessionGeneration)
        assertTrue(state.refresh().visible)
    }
    @Test fun newSameAccountSessionResetsEvenWithoutObservedSignedOutFrame() {
        val state = ResourceGuidanceState().refresh().dismiss().refresh(session = 2)
        assertTrue(state.visible)
        assertEquals(2L, state.sessionGeneration)
    }
    @Test fun switchingAccountCannotInheritAnotherAccountsDismissal() {
        val state = ResourceGuidanceState().refresh().dismiss().refresh(account = "account-b")
        assertTrue(state.visible)
        assertEquals("account-b", state.accountKey)
    }
    @Test fun navigationActionConsumesPromptLikeDismissal() {
        val afterPurchase = ResourceGuidanceState().refresh().dismiss().refresh(connect = false)
        assertFalse(afterPurchase.refresh().visible)
        val afterWallet = ResourceGuidanceState().refresh().dismiss().refresh(connect = false)
        assertFalse(afterWallet.refresh().visible)
    }
}
