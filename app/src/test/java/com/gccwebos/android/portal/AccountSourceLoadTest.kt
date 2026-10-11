package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * The signed-in account's own subscription: login does not fetch it while another source is selected, so after the last
 * saved configuration is deleted the account row used to sit at 待加载 for good. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSourceLoadTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private fun yaml(name: String): ByteArray = ("proxies:\n  - name: $name\n    type: ss\n    server: 192.0.2.10\n" +
        "    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n").toByteArray()

    private inner class Fixture {
        val store = MemoryManualSourceStore()
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null // the account's delivery was never fetched
            override fun save(record: DeliveryRecord) = error("No account delivery in this fixture")
            override fun clear() = Unit
        }, MemoryLocalProfileStore(), SubscriptionSource { error("No official HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, nodeChoices = MemoryNodeChoiceStore(), nodeOverrides = MemoryNodeOverrideStore())
        suspend fun local(name: String) { val b = yaml(name); try { manager.importManual(b) { it() } } finally { b.fill(0) } }
    }

    private fun TestScope.controller(f: Fixture, needsLoad: () -> Unit) =
        ConfigurationLibraryController(f.manager, this, { true }, { it() }, {},
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
            accountSignedIn = { true }, accountNeedsLoad = needsLoad)

    // ---- root cause and the fix hook ----

    @Test fun deletingTheLastConfigurationLeavesTheSignedInAccountEmptyAndNothingFetchesIt() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        var loads = 0
        val c = controller(f) { loads++ }
        c.refresh(); advanceUntilIdle()
        val local = c.snapshot.items.single { it.kind == ManualSourceKind.ImportedLocal }
        c.remove(local.handle); advanceUntilIdle()
        val account = c.snapshot.items.single()
        assertEquals(ManualSourceKind.LegacyRemote, account.kind)
        assertFalse("nothing is selected after the active configuration was deleted", account.active)
        assertEquals(LocalSubscriptionStatus.MISSING, account.status); assertTrue(account.nodes.isEmpty())
        assertEquals("待加载", configurationStatusLabel(account))
        // The panel's automatic load picks it...
        assertNotNull(accountToAutoLoad(true, c.snapshot.items, nodeChosen = false))
        // ...and activating it is exactly where the fetch used to stop: no capability, so the snapshot stays MISSING.
        assertTrue(c.activate(account.handle)); advanceUntilIdle()
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
        assertFalse(f.manager.snapshot().hasCapability)
        assertTrue(c.snapshot.items.single().active)
        // The fix: activating an account with nothing loaded asks for the account's own delivery request, exactly once.
        assertEquals(1, loads)
    }

    @Test fun activatingASavedConfigurationNeverAsksForAnAccountLoad() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        var loads = 0
        val c = controller(f) { loads++ }
        c.refresh(); advanceUntilIdle()
        c.activate(c.snapshot.items.first { it.kind == ManualSourceKind.ImportedLocal && !it.active }.handle); advanceUntilIdle()
        assertEquals(0, loads)
    }

    @Test fun aRefusedActivationReportsItSoTheAutomaticLoadCanTryAgain() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        var allowed = false
        val c = ConfigurationLibraryController(f.manager, this, { allowed }, { it() }, {},
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher, accountSignedIn = { true })
        val handle = f.manager.configurationLibrarySnapshot(true).items.first().handle
        assertFalse("not allowed right now: nothing started", c.activate(handle))
        allowed = true
        assertTrue(c.activate(handle))
    }

    // ---- state, retries and backoff ----

    private class Attempts(val results: MutableList<AccountLoadOutcome?>, var refuse: Boolean = false) {
        var starts = 0
        var pending: ((AccountLoadOutcome) -> Unit)? = null
        fun start(report: (AccountLoadOutcome) -> Unit): Boolean {
            if (refuse) return false
            starts++
            val next = if (results.isEmpty()) null else results.removeAt(0)
            if (next == null) pending = report else report(next)
            return true
        }
    }
    private fun TestScope.coordinator(a: Attempts) =
        AccountLoadCoordinator(this, a::start, longArrayOf(4_000L, 12_000L, 30_000L))

    @Test fun aSuccessfulLoadGoesLoadingThenIdleWithoutRetries() = runTest {
        val a = Attempts(mutableListOf()); val c = coordinator(a)
        assertTrue(c.request())
        assertEquals(AccountLoadState.Loading, c.state)
        a.pending!!(AccountLoadOutcome.Loaded)
        assertEquals(AccountLoadState.Idle, c.state)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, a.starts)
    }

    @Test fun aFailedLoadShowsFailedAndRetriesAfterGrowingPausesThenStops() = runTest {
        val a = Attempts(mutableListOf(AccountLoadOutcome.Failed, AccountLoadOutcome.Failed, AccountLoadOutcome.Failed, AccountLoadOutcome.Failed))
        val c = coordinator(a)
        c.request()
        assertEquals(AccountLoadState.Failed, c.state); assertEquals(1, a.starts)
        advanceTimeBy(3_999); runCurrent(); assertEquals(1, a.starts)
        advanceTimeBy(1); runCurrent(); assertEquals(2, a.starts)
        advanceTimeBy(11_999); runCurrent(); assertEquals(2, a.starts)
        advanceTimeBy(1); runCurrent(); assertEquals(3, a.starts)
        advanceTimeBy(29_999); runCurrent(); assertEquals(3, a.starts)
        advanceTimeBy(1); runCurrent(); assertEquals(4, a.starts)
        assertEquals(AccountLoadState.Failed, c.state)
        advanceTimeBy(600_000); runCurrent()
        assertEquals("three automatic retries, then only the user's own", 4, a.starts)
    }

    @Test fun aRetrySucceedingEndsInIdle() = runTest {
        val a = Attempts(mutableListOf(AccountLoadOutcome.Failed, AccountLoadOutcome.Loaded)); val c = coordinator(a)
        c.request(); advanceTimeBy(4_000); runCurrent()
        assertEquals(AccountLoadState.Idle, c.state); assertEquals(2, a.starts)
    }

    @Test fun anUnusableSubscriptionFailsVisiblyButIsNotRetriedAutomatically() = runTest {
        val a = Attempts(mutableListOf(AccountLoadOutcome.NotLoaded)); val c = coordinator(a)
        c.request()
        assertEquals(AccountLoadState.Failed, c.state)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(1, a.starts)
    }

    @Test fun theUsersRetryRestartsTheScheduleEvenAfterItWasUsedUp() = runTest {
        val a = Attempts(mutableListOf(AccountLoadOutcome.Failed, AccountLoadOutcome.Failed, AccountLoadOutcome.Failed, AccountLoadOutcome.Failed,
            AccountLoadOutcome.Failed, AccountLoadOutcome.Loaded))
        val c = coordinator(a)
        c.request(); advanceTimeBy(100_000); runCurrent()
        assertEquals(4, a.starts); assertEquals(AccountLoadState.Failed, c.state)
        assertTrue(c.request(manual = true))
        assertEquals(5, a.starts)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(AccountLoadState.Idle, c.state); assertEquals(6, a.starts)
    }

    @Test fun anAttemptThatCannotStartBecauseSomethingIsBusyIsRetriedLater() = runTest {
        val a = Attempts(mutableListOf(), refuse = true); val c = coordinator(a)
        assertFalse(c.request())
        assertEquals(AccountLoadState.Failed, c.state)
        a.refuse = false
        advanceTimeBy(4_000); runCurrent()
        assertEquals(1, a.starts); assertEquals(AccountLoadState.Loading, c.state)
        // A manual tap while busy changes nothing and does not queue a retry.
        val b = Attempts(mutableListOf(), refuse = true); val d = coordinator(b)
        assertFalse(d.request(manual = true))
        advanceTimeBy(600_000); runCurrent(); assertEquals(0, b.starts)
    }

    @Test fun cancelStopsRetriesAndForgetsTheStateAndALateReportIsIgnored() = runTest {
        val a = Attempts(mutableListOf()); val c = coordinator(a)
        c.request()
        val late = a.pending!!
        c.cancel()
        assertEquals(AccountLoadState.Idle, c.state)
        late(AccountLoadOutcome.Failed)
        assertEquals("a superseded attempt's outcome is dropped", AccountLoadState.Idle, c.state)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(1, a.starts)
        val b = Attempts(mutableListOf(AccountLoadOutcome.Failed)); val d = coordinator(b)
        d.request(); d.cancel(); advanceTimeBy(600_000); runCurrent()
        assertEquals("sign-out stops a scheduled retry", 1, b.starts); assertEquals(AccountLoadState.Idle, d.state)
    }

    // ---- wording ----

    @Test fun theAccountRowSaysLoadingOrFailedOnlyWhileItIsTheActiveEmptyAccount() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val c = controller(f) {}
        c.refresh(); advanceUntilIdle()
        val inactive = c.snapshot.items.single { it.kind == ManualSourceKind.LegacyRemote }
        assertEquals("0 条 · 账户订阅 · 待加载", accountCaption(inactive, AccountLoadState.Failed))
        c.activate(inactive.handle); advanceUntilIdle()
        val active = c.snapshot.items.single { it.kind == ManualSourceKind.LegacyRemote }
        assertTrue(active.waitsForAccountLoad())
        assertEquals("0 条 · 账户订阅 · 加载中…", accountCaption(active, AccountLoadState.Loading))
        assertEquals("0 条 · 账户订阅 · 加载失败，点此重试", accountCaption(active, AccountLoadState.Failed))
        assertEquals("0 条 · 账户订阅 · 待加载", accountCaption(active, AccountLoadState.Idle))
        assertEquals("账户订阅加载失败", AccountLoadFailedText); assertEquals("点此重试", AccountLoadRetryText)
        assertFalse(c.snapshot.items.single { it.kind == ManualSourceKind.ImportedLocal }.waitsForAccountLoad())
    }
}
