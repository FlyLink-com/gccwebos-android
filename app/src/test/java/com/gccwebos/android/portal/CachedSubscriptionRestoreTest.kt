package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic cache fixtures only. A cached shape failure is not an encrypted-store failure. */
class CachedSubscriptionRestoreTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val url = Secret("https://sub.invalid/sub/a/synthetic-cache")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val ss = """proxies:
  - name: Synthetic cached node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private class Profiles(raw: ByteArray, url: Secret, until: Instant?, observed: LocalSubscriptionStatus?) : LocalProfileStore {
        private val record = CachedSubscription(sessionBinding(url), raw, until, url, observed)
        var saves = 0
        var clears = 0
        override fun read(): CachedSubscription {
            val bytes = record.copyBytes()
            return try { CachedSubscription(record.binding, bytes, record.validUntil, record.url, record.observedStatus) }
            finally { bytes.fill(0) }
        }
        override fun save(value: CachedSubscription) { saves++; error("Restore must not save a cached artifact") }
        override fun clear() { clears++; error("Restore must not clear a cached artifact") }
    }

    private fun manager(raw: ByteArray, hostname: Boolean = true, observed: LocalSubscriptionStatus? = null,
        until: Instant? = now.plusSeconds(3600)): Pair<LocalSubscriptionManager, Profiles> {
        val profiles = Profiles(raw, url, until, observed)
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery save")
            override fun clear() = error("No delivery clear")
        }
        return LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No download during cached restore") },
            policy, { now }, LocalSubscriptionAuthority(), hostname) to profiles
    }

    @Test fun unsupportedCachedOptionRemainsUnsupportedWithItsProfileAndCapability() = runTest {
        val raw = (ss.toString(Charsets.UTF_8) + "    tfo: true\n").toByteArray()
        val (manager, profiles) = manager(raw)
        val restored = manager.restore()
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, restored.status)
        assertTrue(restored.hasProfile && restored.hasCapability)
        assertFalse(restored.nodes.single().supported)
        assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
        assertEquals(0, profiles.saves + profiles.clears)
        profiles.read().use { assertTrue(it.copyBytes().contentEquals(raw)) }
    }

    @Test fun malformedCachedArtifactIsUnsupportedAndNeverReplacedOrDownloaded() = runTest {
        val (manager, profiles) = manager("proxies: [".toByteArray())
        val restored = manager.restore()
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, restored.status)
        assertTrue(restored.hasProfile && restored.hasCapability)
        assertTrue(restored.nodes.isEmpty())
        assertEquals(0, profiles.saves + profiles.clears)
    }

    @Test fun mixedUnsupportedCacheNeverCreatesASelectableSupportedSubset() = runTest {
        val raw = (ss.toString(Charsets.UTF_8) + """  - name: Synthetic unsupported node
    type: vmess
    server: 192.0.2.20
    port: 443
""").toByteArray()
        val (manager, _) = manager(raw)
        val restored = manager.restore()
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, restored.status)
        assertEquals(2, restored.nodes.size)
        assertTrue(restored.nodes[0].supported)
        assertFalse(restored.nodes[1].supported)
        assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun hostnameCacheRestoresOnlyUnderTheApprovedPreparationFlag() = runTest {
        val raw = ss.toString(Charsets.UTF_8).replace("192.0.2.10", "edge.example.invalid").toByteArray()
        val (enabled, _) = manager(raw, hostname = true)
        assertEquals(LocalSubscriptionStatus.READY, enabled.restore().status)
        val (disabled, _) = manager(raw, hostname = false)
        val blocked = disabled.restore()
        assertEquals(LocalSubscriptionStatus.UNSUPPORTED, blocked.status)
        assertTrue(blocked.hasProfile && blocked.hasCapability)
        assertTrue(disabled.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun durableRevocationAndExpiryStayStrongerThanCachedCompatibility() = runTest {
        val raw = (ss.toString(Charsets.UTF_8) + "    tfo: true\n").toByteArray()
        for (observed in listOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) {
            val (manager, _) = manager(raw, observed = observed)
            assertEquals(observed, manager.restore().status)
            assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
        }
    }

    @Test fun actualStoreFailureRemainsAStorageBlock() = runTest {
        val profiles = object : LocalProfileStore {
            override fun read(): CachedSubscription = throw PortalFailure(PortalFailure.Reason.STORAGE)
            override fun save(value: CachedSubscription) = error("No save")
            override fun clear() = error("No clear")
        }
        val delivery = MemoryDeliveryStore()
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No download") }, policy,
            { now }, LocalSubscriptionAuthority(), true)
        val restored = manager.restore()
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, restored.status)
        assertFalse(restored.hasProfile)
        assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
    }
}
