package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NodeSelectionControllerTest {
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private fun yaml(first: String = "192.0.2.10", second: String = "192.0.2.11") = """proxies:
  - name: duplicate
    type: ss
    server: $first
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: duplicate
    type: ss
    server: $second
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = record("fixture-a")
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
        companion object {
            fun record(key: String) = DeliveryRecord("synthetic-account", "synthetic-session", "synthetic-resource", "synthetic-key",
                Secret("https://sub.invalid/sub/a/$key"), null, false)
        }
    }
    private class Profiles : LocalProfileStore {
        private val memory = MemoryLocalProfileStore()
        var reads = 0
        var saves = 0
        var failSave = false
        var failRead = false
        var failReadsRemaining = 0
        override fun read(): CachedSubscription? {
            reads++
            if (failRead || failReadsRemaining > 0) {
                if (failReadsRemaining > 0) failReadsRemaining--
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
            return memory.read()
        }
        override fun save(value: CachedSubscription) {
            if (failSave) throw PortalFailure(PortalFailure.Reason.STORAGE)
            saves++; memory.save(value)
        }
        override fun clear() = memory.clear()
    }
    private inner class Fixture(scope: CoroutineScope) {
        val delivery = Delivery()
        val profiles = Profiles()
        var time = start
        var until: Instant? = start.plusSeconds(3600)
        var bytes = yaml()
        var reads = 0
        var auth = 1L
        var allowed = true
        var published = 0
        var response: suspend () -> ByteArray = { bytes.copyOf() }
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead { reads++; return SubscriptionRead(response(), until) }
        }
        val authority = LocalSubscriptionAuthority()
        val manager = LocalSubscriptionManager(delivery, profiles, source,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time }, authority)
        val controller = NodeSelectionController(manager, scope, { auth }, { allowed },
            ioDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher) { published++ }
        fun selected() = controller.snapshot.selected
        fun selectSecond() { controller.select(controller.snapshot.choices[1].selection) }
    }

    @Test fun strictImportPersistsAllNodesAndSelectingNeverDownloadsOrPrepares() = runTest {
        val f = Fixture(this)
        assertEquals(0, f.reads)
        f.controller.refresh(); runCurrent()
        assertEquals(LocalSubscriptionStatus.READY, f.controller.snapshot.status)
        assertEquals(2, f.controller.snapshot.choices.size)
        assertEquals(1, f.reads); assertEquals(1, f.profiles.saves)
        val selected = f.controller.snapshot.choices[1].selection
        f.controller.select(selected)
        assertSame(selected, f.selected())
        assertEquals(1, f.reads); assertEquals(1, f.profiles.saves)
        val cached = f.profiles.read()!!
        try { val copy = cached.copyBytes(); try { assertArrayEquals(f.bytes, copy) } finally { copy.fill(0) } }
        finally { cached.close() }
        assertFalse(selected.toString().contains("fixture"))
    }

    @Test fun displayClockTicksNeverReadStoresOrDownloadAndExpireOnTime() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val selected = f.selected()
        val before = f.profiles.reads
        repeat(60) { f.controller.expireIfNeeded() }
        assertEquals(before, f.profiles.reads)
        assertEquals(1, f.reads); assertSame(selected, f.selected())
        f.time = requireNotNull(f.until)
        f.controller.expireIfNeeded()
        assertEquals(LocalSubscriptionStatus.EXPIRED, f.controller.snapshot.status)
        assertTrue(f.controller.snapshot.choices.isEmpty()); assertNull(f.selected())
        assertEquals(before, f.profiles.reads)
    }

    @Test fun visitingAlreadyReadyNodesRevalidatesLocallyWithoutDownloadingOrLosingSelection() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val selected = f.selected()
        repeat(3) { f.controller.refreshIfNeeded(); runCurrent() }
        assertEquals(1, f.reads); assertSame(selected, f.selected())
        assertEquals(LocalSubscriptionStatus.READY, f.controller.snapshot.status)
    }

    @Test fun leavingDuringLocalReadPreservesRegistryAndDoesNotForceNetworkNextVisit() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val selected = f.selected()
        f.controller.refreshIfNeeded()
        assertTrue(f.controller.busy)
        f.controller.cancel()
        f.controller.refreshIfNeeded(); runCurrent()
        assertEquals(1, f.reads); assertSame(selected, f.selected())
    }

    @Test fun identicalFreshReadsKeepSelectionButEveryContentChangeRequiresReselection() = runTest {
        val mutations = listOf(yaml("192.0.2.11", "192.0.2.10"), yaml("192.0.2.12"),
            (yaml().toString(Charsets.UTF_8) + "# new comment\n").toByteArray(),
            yaml().toString(Charsets.UTF_8).replace("synthetic-only", "new-synthetic").toByteArray())
        for (changed in mutations) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val old = f.selected()!!
            f.controller.refresh(); runCurrent()
            assertSame(old, f.selected()); assertEquals(2, f.reads)
            f.bytes = changed; f.controller.refresh(); runCurrent()
            assertNull(f.selected()); assertEquals(3, f.reads)
            assertTrue(f.controller.snapshot.choices.none { it.selection === old })
            assertEquals("配置已变化，请重新选择节点。", f.controller.message)
            f.controller.select(old); assertNull(f.selected())
        }
    }

    @Test fun mixedUnsupportedArtifactNeverAllowsPartialSelectionOrReplacesValidProfile() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val old = f.selected()!!
        f.bytes = yaml().toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray()
        f.controller.refresh(); runCurrent()
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, f.controller.snapshot.status)
        assertTrue(f.controller.snapshot.choices.isEmpty()); assertNull(f.selected())
        assertEquals(1, f.profiles.saves)
        assertTrue(f.manager.snapshot().nodes.any { it.supported })
        f.controller.select(old); assertNull(f.selected())
    }

    @Test fun expiryAndInvalidLinkDestroySelectionWithoutDeletingLocalProfile() = runTest {
        for (expiry in listOf(true, false)) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val old = f.selected()!!
            if (expiry) { f.time = start.plusSeconds(3600); f.controller.sync() }
            else {
                f.response = { throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_REVOKED) }
                f.controller.refresh(); runCurrent()
            }
            assertEquals(if (expiry) LocalSubscriptionStatus.EXPIRED else LocalSubscriptionStatus.INVALID_LINK, f.controller.snapshot.status)
            assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.isEmpty())
            val cached = f.profiles.read(); assertNotNull(cached); cached!!.close()
            f.controller.select(old); assertNull(f.selected())
        }
    }

    @Test fun capabilityChangeAndClearCannotReuseOpaqueObjectsEvenForIdenticalBytes() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val old = f.selected()!!
        f.manager.unlink(); f.delivery.value = Delivery.record("fixture-b")
        f.controller.refresh(); runCurrent()
        assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.none { it.selection === old })
        f.controller.select(old); assertNull(f.selected())
        f.selectSecond(); val current = f.selected()!!
        f.controller.cancel(); f.controller.sync()
        assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.isEmpty())
        f.controller.refresh(); runCurrent()
        f.controller.select(current); assertNull(f.selected())
    }

    @Test fun cancelOrAccountChangeFenceNonCooperativeLateSuccessAndError() = runTest {
        for (accountChange in listOf(false, true)) for (fails in listOf(false, true)) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val siblingRelease = CompletableDeferred<Unit>()
            val sibling = launch { siblingRelease.await() }
            val release = CompletableDeferred<Unit>()
            val late = yaml("192.0.2.80")
            f.response = {
                withContext(NonCancellable) { release.await() }
                if (fails) throw PortalFailure(PortalFailure.Reason.NETWORK)
                late
            }
            f.controller.refresh(); runCurrent(); assertTrue(f.controller.busy)
            if (accountChange) { f.auth++; f.controller.sync() } else f.controller.cancel()
            assertTrue(sibling.isActive)
            val published = f.published
            release.complete(Unit); runCurrent()
            assertFalse(f.controller.busy); assertNull(f.selected())
            assertEquals(1, f.profiles.saves); assertEquals(published, f.published)
            if (!fails) assertTrue(late.all { it == 0.toByte() })
            f.controller.sync(); assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.isEmpty())
            assertTrue(sibling.isActive)
            siblingRelease.complete(Unit); sibling.join()
        }
    }

    @Test fun newRefreshWinsOverLateCancelledReadWithoutRevivingOldChoice() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        val old = yaml("192.0.2.80")
        f.response = { if (f.reads == 1) { withContext(NonCancellable) { release.await() }; old } else f.bytes.copyOf() }
        f.controller.refresh(); runCurrent()
        f.controller.refresh(); runCurrent()
        assertTrue(f.controller.busy)
        release.complete(Unit); runCurrent()
        assertFalse(f.controller.busy); assertEquals(2, f.reads); assertEquals(1, f.profiles.saves)
        assertEquals(1, f.published); assertEquals(LocalSubscriptionStatus.READY, f.controller.snapshot.status)
        assertTrue(old.all { it == 0.toByte() })
    }

    @Test fun restorationRevalidatesMetadataButCannotRecoverCancelledSelection() = runTest {
        val f = Fixture(this)
        f.controller.refresh(); runCurrent(); f.selectSecond()
        val old = f.selected()!!
        f.controller.cancel()
        f.manager.restore(); f.controller.sync()
        assertEquals(1, f.reads)
        assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.none { it.selection === old })
        f.controller.select(old); assertNull(f.selected())
    }

    @Test fun missingCapabilityStorageAndNetworkAreExplicitBlocksWithoutCachedSelectionFallback() = runTest {
        val f = Fixture(this)
        f.delivery.clear(); f.controller.refresh(); runCurrent()
        assertEquals(LocalSubscriptionStatus.MISSING, f.controller.snapshot.status); assertEquals(0, f.reads)
        f.delivery.value = Delivery.record("fixture-a")
        f.controller.refresh(); runCurrent(); f.selectSecond()
        f.profiles.failSave = true; f.controller.refresh(); runCurrent()
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.controller.snapshot.status); assertNull(f.selected())
        f.profiles.failSave = false; f.controller.refresh(); runCurrent(); f.selectSecond()
        f.response = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        f.controller.refresh(); runCurrent()
        assertEquals(LocalSubscriptionStatus.RETRY_REQUIRED, f.controller.snapshot.status)
        assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.isEmpty())
    }

    @Test fun accountWorkGuardAndForgedHandleCannotIssueRequestsOrSelectByName() = runTest {
        val f = Fixture(this)
        f.allowed = false; f.controller.refresh(); runCurrent(); assertEquals(0, f.reads)
        f.allowed = true; f.controller.refresh(); runCurrent()
        f.controller.select(ConnectionSelection()); assertNull(f.selected())
        assertEquals(2, f.controller.snapshot.choices.count { it.name == "duplicate" })
        f.selectSecond(); assertSame(f.controller.snapshot.choices[1].selection, f.selected())
        assertEquals(1, f.reads)
        f.allowed = false; f.controller.select(f.controller.snapshot.choices[0].selection)
        assertSame(f.controller.snapshot.choices[1].selection, f.selected())
    }

    @Test fun failedCapabilityOrStorageSnapshotRemainsBlockedAcrossRepeatedReadsAndSelect() = runTest {
        for (storage in listOf(true, false)) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val old = f.selected()!!
            if (storage) f.profiles.failRead = true else {
                f.profiles.clear()
                f.delivery.value = DeliveryRecord("fixture", "fixture", "fixture", "fixture",
                    Secret("https://foreign.invalid/sub/a/fixture"), null, false)
            }
            val expected = if (storage) LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.INVALID_LINK
            repeat(2) { assertEquals(expected, f.manager.nodeSelectionSnapshot().status) }
            f.controller.select(old)
            assertEquals(expected, f.controller.snapshot.status); assertNull(f.selected())
            assertEquals(1, f.reads)
        }
    }
    @Test fun otherManagerExpiryOrFailedRememberCannotExposeSelectableSharedMetadata() = runTest {
        for (saveFailure in listOf(false, true)) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val old = f.selected()!!
            val other = LocalSubscriptionManager(f.delivery, f.profiles, f.source,
                OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { f.time },
                f.authority)
            assertSame(old, other.nodeSelectionSnapshot().selected)
            if (saveFailure) {
                f.profiles.failSave = true
                assertThrows(PortalFailure::class.java) {
                    other.remember(yaml("192.0.2.80"), f.delivery.value!!.url!!, f.until)
                }
            } else { f.until = start; other.refresh() }
            f.controller.sync()
            assertEquals(if (saveFailure) LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.EXPIRED,
                f.controller.snapshot.status)
            assertNull(f.selected()); assertTrue(f.controller.snapshot.choices.isEmpty())
            f.controller.select(old); assertNull(f.selected())
            other.restore(); f.controller.sync()
            assertEquals(if (saveFailure) LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.EXPIRED,
                f.controller.snapshot.status)
            assertTrue(f.controller.snapshot.choices.isEmpty())
            val cached = f.profiles.read(); assertNotNull(cached); cached!!.close()
        }
    }
    @Test fun failedRestoreOrMissingCapabilityCannotBeMaskedByEarlierGlobalReady() = runTest {
        for (storage in listOf(true, false)) {
            val f = Fixture(this)
            f.controller.refresh(); runCurrent(); f.selectSecond()
            val old = f.selected()!!
            if (storage) f.profiles.failReadsRemaining = 1
            else { f.profiles.clear(); f.delivery.clear() }
            f.manager.restore(); f.controller.sync()
            val expected = if (storage) LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.MISSING
            repeat(2) { assertEquals(expected, f.manager.nodeSelectionSnapshot().status) }
            f.controller.select(old)
            assertEquals(expected, f.controller.snapshot.status); assertNull(f.selected())
            assertEquals(1, f.reads)
        }
    }
}
