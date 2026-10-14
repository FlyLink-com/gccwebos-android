package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountUsageReadTimeoutTest {
    private val firstOwner = AccountUsageOwner.fromAccepted("fixture_account_a", sessionBinding(Secret("fixture_token_a")))
    private val repositoryEpoch = 7L
    private val uiAuthEpoch = 11L
    private val cycleStart = Instant.parse("2026-10-01T00:00:00Z")
    private val cycleEnd = Instant.parse("2026-11-01T00:00:00Z")

    private fun available(ref: Char = 'a', used: Long = 40): Resource.Available = Resource.Available(
        "resource_" + ref.toString().repeat(64), "fixture_plan", Instant.parse("2026-12-01T00:00:00Z"), false,
        Usage(100, used, (100 - used).coerceAtLeast(0), "measured", cycleStart.plusSeconds(3600), cycleStart, cycleEnd),
        30, 50.0)

    private fun bound(resource: Resource, sequence: Long = 1, revision: Long = 1) =
        BoundAccountOverview(AccountUsageReadContext(firstOwner, repositoryEpoch, revision, sequence), resource)

    @Test fun aHungOverviewReadEndsAsARecoverableTimeout() = runTest {
        var hang = false
        val never = CompletableDeferred<Unit>()
        var calls = 0
        var expirations = 0
        val controller = AccountUsageController(this, { _, _ ->
            calls++
            if (hang) never.await()
            bound(available(used = calls.toLong()), calls.toLong(), 1)
        }, { expirations++ })
        controller.bind(firstOwner, repositoryEpoch, uiAuthEpoch)
        val cached = available()
        assertTrue(controller.observeExternal(bound(cached), uiAuthEpoch))
        assertSame(cached, controller.state.value.resource)

        hang = true
        controller.update(); runCurrent()
        assertTrue(controller.state.value.busy)
        advanceTimeBy(AccountUsageController.ReadTimeoutMillis + 1); runCurrent()
        assertFalse("a hung overview read must not load forever", controller.state.value.busy)
        assertEquals(AccountUsageController.ReadTimeoutMessage, controller.state.value.error)
        assertTrue(controller.state.value.notUpdated)
        assertSame(cached, controller.state.value.resource)
        assertEquals(0, expirations)

        hang = false
        controller.update(); advanceUntilIdle()
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.notUpdated)
        assertEquals(0, expirations)
        assertNotNull(controller.state.value.resource)
    }
}
