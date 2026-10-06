package com.gccwebos.android.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticUpdateControllerTest {
    private val official = OfficialReleases.RELEASE_APPLICATION_ID
    private fun manifest(code: Long = 8) = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"available","versionCode":$code,"versionName":"0.5.0","minSupportedVersionCode":1,"publishedAt":"2026-10-05T00:00:00Z","downloadUrl":"/android/releases/synthetic.apk","sha256":"${"a".repeat(64)}","notes":{"zh":"合成更新","en":"Synthetic update"}}"""
    private class Store : AutomaticUpdateStore {
        var attempted = 0L
        var prompted = 0L
        var fail = false
        var operationContext: kotlin.coroutines.CoroutineContext? = null
        override suspend fun tryBeginCheck(now: Long, interval: Long): Boolean {
            operationContext = kotlin.coroutines.coroutineContext
            if (fail) error("Synthetic storage failure")
            if (!automaticCheckDue(attempted, now, interval)) return false
            attempted = now
            return true
        }
        override suspend fun wasPrompted(code: Long) = code <= prompted
        override suspend fun recordPrompt(code: Long) { if (fail) error("Synthetic storage failure"); prompted = maxOf(prompted, code) }
    }

    @Test fun firstForegroundChecksOnceAndRecreationUsesPersistedRateLimit() = runTest {
        val store = Store(); var calls = 0
        val repository = UpdateRepository { calls++; manifest() }
        fun controller() = AutomaticUpdateController(backgroundScope, repository, store, 7, official, false,
            clock = { 100_000L }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = controller()
        repeat(3) { first.onForeground() }; runCurrent()
        assertEquals(1, calls); assertNotNull(first.prompt.value)
        first.close()
        val second = controller(); second.onForeground(); runCurrent()
        assertEquals(1, calls); assertNull(second.prompt.value)
    }

    @Test fun failuresAreSilentAndRateLimitedAcrossRestart() = runTest {
        val store = Store(); var calls = 0; var now = 100_000L
        fun controller() = AutomaticUpdateController(backgroundScope, UpdateRepository { calls++; error("Synthetic network failure") },
            store, 7, official, false, clock = { now }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = controller(); first.onForeground(); runCurrent(); assertNull(first.prompt.value); first.close()
        val second = controller(); second.onForeground(); runCurrent(); assertEquals(1, calls)
        now += AUTOMATIC_UPDATE_INTERVAL_MILLIS
        second.onForeground(); runCurrent(); assertEquals(2, calls); assertNull(second.prompt.value)
    }

    @Test fun debugAndCandidateNeverReadStorageOrFetch() = runTest {
        for ((id, debug) in listOf(official to true, "$official.debug" to true, "$official.candidate" to false)) {
            val state = AutomaticUpdateController(backgroundScope, UpdateRepository { error("No fetch") },
                Store().apply { fail = true }, 7, id, debug, ioDispatcher = StandardTestDispatcher(testScheduler))
            state.onForeground(); runCurrent(); assertNull(state.prompt.value)
        }
    }

    @Test fun onlyAnActuallyVisiblePromptIsRecordedAndSameVersionIsNotRepeated() = runTest {
        val store = Store(); var now = 100_000L
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository { manifest() }, store, 7, official, false,
            clock = { now }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val manual = state.suppressForManualCheck()
        state.onForeground(); runCurrent(); assertNull(state.prompt.value); assertEquals(0L, store.prompted)
        state.promptShown(8); runCurrent(); assertEquals(0L, store.prompted)
        manual.close(); assertNotNull(state.prompt.value)
        state.promptShown(8); runCurrent(); assertEquals(8L, store.prompted)
        state.dismiss(); now += AUTOMATIC_UPDATE_INTERVAL_MILLIS
        state.onForeground(); runCurrent(); assertNull(state.prompt.value)
    }

    @Test fun backgroundAndClosedControllersRejectLateResultsWithoutRecordingPrompt() = runTest {
        for (close in listOf(false, true)) {
            val store = Store(); val finish = CompletableDeferred<Unit>()
            val state = AutomaticUpdateController(backgroundScope, UpdateRepository {
                withContext(NonCancellable) { finish.await() }; manifest()
            }, store, 7, official, false, clock = { 100_000L }, ioDispatcher = StandardTestDispatcher(testScheduler))
            state.onForeground(); runCurrent()
            if (close) state.close() else state.onBackground()
            finish.complete(Unit); runCurrent()
            assertNull(state.prompt.value); assertEquals(0L, store.prompted)
        }
    }

    @Test fun alreadyShownPromptDoesNotReappearAfterHomeWithoutAnExplicitDismissal() = runTest {
        val store = Store(); var now = 100_000L
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository { manifest() }, store, 7, official, false,
            clock = { now }, ioDispatcher = StandardTestDispatcher(testScheduler))
        state.onForeground(); runCurrent(); state.promptShown(8); runCurrent()
        state.onBackground(); now += AUTOMATIC_UPDATE_INTERVAL_MILLIS
        state.onForeground(); runCurrent(); assertNull(state.prompt.value)
        assertEquals(8L, store.prompted)
    }

    @Test fun sameOlderUnavailableAndInvalidManifestsNeverPrompt() = runTest {
        for (body in listOf(manifest(7), manifest(6), "invalid", """{"schemaVersion":1,"platform":"android","channel":"stable","status":"unavailable"}""")) {
            val state = AutomaticUpdateController(backgroundScope, UpdateRepository { body }, Store(), 7, official, false,
                clock = { 100_000L }, ioDispatcher = StandardTestDispatcher(testScheduler))
            state.onForeground(); runCurrent(); assertNull(state.prompt.value)
        }
    }

    @Test fun dismissedVersionAndLowerVersionsStaySuppressedAcrossControllerRecreation() = runTest {
        for (code in listOf(8L, 9L)) {
            val state = AutomaticUpdateController(backgroundScope, UpdateRepository { manifest(code) },
                Store().apply { prompted = 9 }, 7, official, false, clock = { 100_000L },
                ioDispatcher = StandardTestDispatcher(testScheduler))
            state.onForeground(); runCurrent(); assertNull(state.prompt.value)
        }
    }

    @Test fun manualPromptSuppressesTheSameAutomaticVersionButDoesNotOwnManualChecks() = runTest {
        val store = Store(); val ready = CompletableDeferred<Unit>()
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository { ready.await(); manifest() },
            store, 7, official, false, clock = { 100_000L }, ioDispatcher = StandardTestDispatcher(testScheduler))
        state.onForeground(); runCurrent()
        val manual = state.suppressForManualCheck()
        state.manualPromptShown(8); ready.complete(Unit); runCurrent()
        manual.close(); assertNull(state.prompt.value); assertEquals(8L, store.prompted)
        // Manual UpdateController deliberately has no automatic frequency or version-dismissal gate.
        var calls = 0
        val explicit = UpdateController(backgroundScope, UpdateRepository { calls++; manifest() }, 7, official)
        explicit.check(); runCurrent(); assertTrue((explicit.state.value as UpdateState.Finished).result is UpdateResult.NewVersion)
        explicit.dismiss(); explicit.check(); runCurrent(); assertEquals(2, calls)
    }

    @Test fun storageFailuresNeverFetchOrPublishAnError() = runTest {
        var calls = 0
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository { calls++; manifest() },
            Store().apply { fail = true }, 7, official, false, ioDispatcher = StandardTestDispatcher(testScheduler))
        state.onForeground(); runCurrent(); assertEquals(0, calls); assertNull(state.prompt.value)
    }

    @Test fun promptPersistenceFailureIsSilentAndDoesNotPreventDismissal() = runTest {
        val store = Store()
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository { manifest() }, store, 7, official, false,
            clock = { 100_000L }, ioDispatcher = StandardTestDispatcher(testScheduler))
        state.onForeground(); runCurrent(); store.fail = true
        state.promptShown(8); runCurrent(); state.dismiss(); assertNull(state.prompt.value)
    }

    @Test fun clockRollbackAndInvalidStoredTimesCannotDisableChecksForever() {
        assertTrue(automaticCheckDue(200, 100, 50))
        assertTrue(automaticCheckDue(-1, 100, 50))
        assertTrue(automaticCheckDue(0, 100, 50))
        assertFalse(automaticCheckDue(100, 149, 50))
        assertTrue(automaticCheckDue(100, 150, 50))
        assertFalse(automaticCheckDue(0, -1, 50))
        assertTrue(automaticCheckDue(1, Long.MAX_VALUE, 50))
    }

    @Test fun persistenceAndManifestFetchRunOnlyOnTheInjectedIoDispatcher() = runTest {
        val io = StandardTestDispatcher(testScheduler, "automatic-update-io")
        val store = Store()
        var fetchContext: kotlin.coroutines.CoroutineContext? = null
        val state = AutomaticUpdateController(backgroundScope, UpdateRepository {
            fetchContext = kotlin.coroutines.coroutineContext; manifest()
        }, store, 7, official, false, clock = { 100_000L }, ioDispatcher = io)
        state.onForeground(); runCurrent()
        assertSame(io, fetchContext?.get(kotlin.coroutines.ContinuationInterceptor))
        assertSame(io, store.operationContext?.get(kotlin.coroutines.ContinuationInterceptor))
        assertEquals(100_000L, store.attempted)
    }
}
