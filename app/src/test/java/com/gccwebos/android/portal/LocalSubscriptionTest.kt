package com.gccwebos.android.portal

import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalSubscriptionTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val yaml = """proxies:
  - name: Synthetic local node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = DeliveryRecord("fixture", "old-session-binding", "resource", "operation", Secret("https://sub.invalid/sub/a/synthetic"), Secret("handoff"), false)
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }
    @Test fun artifactResponseUsesOnlyExplicitExpiryAndNeverLeaksErrorBodies() {
        for ((status, body, expected) in listOf(
            Triple(403, "{\"error\":\"Subscription expired\"}", PortalFailure.Reason.SUBSCRIPTION_EXPIRED),
            Triple(403, "{\"error\":\"Subscription link expired\"}", PortalFailure.Reason.SUBSCRIPTION_REVOKED),
            Triple(403, "{\"error\":\"Subscription expired\",\"extra\":true}", PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE),
            Triple(500, "Subscription expired", PortalFailure.Reason.UNAVAILABLE),
            Triple(401, "synthetic-private", PortalFailure.Reason.SUBSCRIPTION_REVOKED))) {
            val bytes = body.toByteArray()
            val failure = assertThrows(PortalFailure::class.java) { decodeSubscriptionResponse(status, bytes, null) }
            assertEquals(expected, failure.reason); assertTrue(bytes.all { it == 0.toByte() })
            assertFalse(failure.toString().contains("synthetic-private"))
        }
        val valid = decodeSubscriptionResponse(200, yaml.copyOf(), "2026-10-01T00:00:00Z")
        assertEquals(now.plusSeconds(86400), valid.validUntil); valid.bytes.fill(0)
        val malformed = yaml.copyOf()
        assertEquals(PortalFailure.Reason.PROTOCOL, assertThrows(PortalFailure::class.java) {
            decodeSubscriptionResponse(200, malformed, "not-a-date")
        }.reason)
        assertTrue(malformed.all { it == 0.toByte() })
    }
    @Test fun cachedSubscriptionRestoresOfflineWithoutAnyPortalSession() = runTest {
        val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
        val first = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No network") }, policy, {now})
        first.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        val restarted = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No network") }, policy, {now})
        val result = restarted.restore()
        assertTrue(result.hasProfile); assertEquals(LocalSubscriptionStatus.READY, result.status)
        assertEquals("Synthetic local node", result.nodes.single().name)
    }
    @Test fun accountSignoutKeepsCapabilityButConfirmedClearRemovesIt() = runTest {
        val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
        val repo = PortalRepository(object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?) = error("No Portal requests") },
            delivery, policy, {0}, {now}, SubscriptionSource { yaml.copyOf() }, profiles = profiles)
        repo.localSubscriptions.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        repo.signOutAccount()
        assertNotNull(delivery.value); assertTrue(repo.localSubscriptions.restore().hasProfile)
        repo.clearLocalData(); assertNull(delivery.value); assertNull(profiles.read())
        assertEquals(LocalSubscriptionStatus.MISSING, repo.localSubscriptions.restore().status)
    }
    @Test fun everyPreparationDownloadsAgainAndWorksWithoutPortalToken() = runTest {
        var downloads = 0; val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { downloads++; yaml.copyOf() }, policy, {now})
        manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        val dir = Files.createTempDirectory("local-subscription-test").toFile()
        val resolver = LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic-explicit-line", 0) }
        try {
            val first = manager.prepare(null, resolver, AtomicPreparedConfigStore(dir))!!
            assertTrue(first.activate { true }); first.close()
            val second = manager.prepare(null, resolver, AtomicPreparedConfigStore(dir))!!
            assertTrue(second.activate { true }); second.close()
            assertEquals(2, downloads)
        } finally { dir.deleteRecursively() }
    }
    @Test fun downloadFailureNeverFallsBackToCachedActivationOrClaimsExpiry() = runTest {
        val delivery = Delivery()
        val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), SubscriptionSource { throw PortalFailure(PortalFailure.Reason.NETWORK) }, policy, {now})
        manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        val dir = Files.createTempDirectory("local-subscription-test").toFile()
        try {
            assertNull(manager.prepare(null, LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic", 0) }, AtomicPreparedConfigStore(dir)))
            assertEquals(LocalSubscriptionStatus.RETRY_REQUIRED, manager.snapshot().status)
            assertTrue(manager.snapshot().hasProfile); assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun explicitExpiryInvalidLinkAndUnsupportedAreDifferentStates() = runTest {
        for ((failure, expected) in listOf(PortalFailure.Reason.SUBSCRIPTION_EXPIRED to LocalSubscriptionStatus.EXPIRED,
            PortalFailure.Reason.SUBSCRIPTION_REVOKED to LocalSubscriptionStatus.INVALID_LINK,
            PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE to LocalSubscriptionStatus.RETRY_REQUIRED)) {
            val delivery = Delivery(); val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), SubscriptionSource { throw PortalFailure(failure) }, policy, {now})
            manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
            assertEquals(expected, manager.refresh().status)
        }
        val delivery = Delivery(); val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), SubscriptionSource { yaml.toString(Charsets.UTF_8).replace("type: ss", "type: vmess").toByteArray() }, policy, {now})
        manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, manager.refresh().status)
    }
    @Test fun authoritativeExpiredMetadataBlocksPreparationAndGuidesRenewal() = runTest {
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = yaml.copyOf()
            override suspend fun read(url: Secret) = SubscriptionRead(yaml.copyOf(), now.minusSeconds(1))
        }
        val manager = LocalSubscriptionManager(Delivery(), MemoryLocalProfileStore(), source, policy, {now})
        val dir = Files.createTempDirectory("local-subscription-test").toFile()
        try {
            assertNull(manager.prepare(null, LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic", 0) }, AtomicPreparedConfigStore(dir)))
            assertEquals(LocalSubscriptionStatus.EXPIRED, manager.snapshot().status)
        } finally { dir.deleteRecursively() }
    }
    @Test fun clearWhileDownloadIsPendingCannotResurrectProfile() = runTest {
        val start = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { start.complete(Unit); finish.await(); yaml.copyOf() }, policy, {now})
        val job = async { manager.refresh() }; start.await(); manager.unlink(); finish.complete(Unit)
        try { job.await(); fail() } catch (_: CancellationException) { }
        assertNull(profiles.read()); assertFalse(manager.snapshot().hasProfile)
    }
    @Test fun clearDuringCachedRestoreCannotRecreateProfileOrCapability() = runTest {
        val delivery = Delivery(); val memory = MemoryLocalProfileStore()
        val readStarted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var blockRead = false
        val profiles = object : LocalProfileStore {
            override fun read(): CachedSubscription? {
                val snapshot = synchronized(memory) { memory.read() }
                if (blockRead) { blockRead = false; readStarted.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                return snapshot
            }
            override fun save(value: CachedSubscription) = synchronized(memory) { memory.save(value) }
            override fun clear() = synchronized(memory) { memory.clear() }
        }
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No network") }, policy, {now})
        manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        blockRead = true
        val restoring = async(kotlinx.coroutines.Dispatchers.Default) { manager.restore() }
        check(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        manager.unlink(); release.countDown(); restoring.await()
        assertNull(profiles.read()); assertNull(delivery.value)
        assertFalse(manager.snapshot().hasProfile); assertFalse(manager.snapshot().hasCapability)
    }
    @Test fun knownExpiryAndRevocationSurviveProcessRestart() = runTest {
        for (reason in listOf(PortalFailure.Reason.SUBSCRIPTION_EXPIRED, PortalFailure.Reason.SUBSCRIPTION_REVOKED)) {
            val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
            val source = SubscriptionSource { throw PortalFailure(reason) }
            val first = LocalSubscriptionManager(delivery, profiles, source, policy, {now})
            first.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
            val observed = first.refresh().status
            val restarted = LocalSubscriptionManager(delivery, profiles, source, policy, {now})
            assertEquals(observed, restarted.restore().status)
        }
    }
    @Test fun independentCapabilitySurvivesPortalReceiptAccountSwitch() = runTest {
        val delivery = Delivery(); val profiles = MemoryLocalProfileStore()
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { yaml.copyOf() }, policy, {now})
        manager.remember(yaml, delivery.value!!.url!!, now.plusSeconds(86400))
        delivery.clear() // Switching wallet accounts may remove only the old Portal receipt.
        val restarted = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { yaml.copyOf() }, policy, {now})
        assertTrue(restarted.restore().hasProfile)
        assertEquals(LocalSubscriptionStatus.READY, restarted.refresh().status)
        restarted.unlink(); assertNull(profiles.read())
    }
    @Test fun newerFetchOrClearInvalidatesPreparedHandoff() = runTest {
        val manager = LocalSubscriptionManager(Delivery(), MemoryLocalProfileStore(), SubscriptionSource { yaml.copyOf() }, policy, {now})
        val dir = Files.createTempDirectory("local-subscription-test").toFile()
        try {
            val prepared = manager.prepare(null, LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic", 0) }, AtomicPreparedConfigStore(dir))!!
            manager.refresh()
            assertThrows(IllegalStateException::class.java) { prepared.activate { fail("Must not activate stale preparation"); true } }
            prepared.close(); assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
