package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Offline read-only probe admission, not a network or tunnel test. */
class NodeProbeTargetsTest {
    private val start = Instant.parse("2026-10-04T00:00:00Z")
    private fun artifact(vararg hosts: String) = ("proxies:\n" + hosts.joinToString("") { host -> """  - name: same display name
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""" }).toByteArray()

    private class Store : LocalProfileStore {
        val memory = MemoryLocalProfileStore()
        var reads = 0
        var writes = 0
        var clears = 0
        var failure: Exception? = null
        var lastRead: CachedSubscription? = null
        override fun read(): CachedSubscription? {
            reads++
            failure?.let { throw it }
            return memory.read().also { lastRead = it }
        }
        override fun save(value: CachedSubscription) { writes++; memory.save(value) }
        override fun clear() { clears++; memory.clear() }
        fun replace(raw: ByteArray? = null, binding: String? = null, url: Secret? = null,
            withoutUrl: Boolean = false, status: LocalSubscriptionStatus? = null) {
            memory.read()!!.use { old ->
                val bytes = raw?.copyOf() ?: old.copyBytes()
                try {
                    CachedSubscription(binding ?: old.binding, bytes, old.validUntil,
                        if (withoutUrl) null else url ?: old.url, status).use(memory::save)
                } finally { bytes.fill(0) }
            }
        }
    }

    private inner class Fixture(raw: ByteArray = artifact("192.0.2.10", "192.0.2.11"),
        allowHostname: Boolean = false, until: Instant? = start.plusSeconds(600)) {
        val profiles = Store()
        val authority = LocalSubscriptionAuthority()
        val delivery = MemoryDeliveryStore()
        val url = Secret("https://sub.invalid/sub/a/probe-fixture")
        val receipt = DeliveryRecord("synthetic-account", "synthetic-session", "synthetic-resource",
            "synthetic-operation", url, null, false)
        var time = start
        var downloads = 0
        val manager: LocalSubscriptionManager
        init {
            delivery.save(receipt)
            manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource {
                downloads++; error("Probe target reads must not download")
            }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time }, authority, allowHostname)
            manager.remember(raw, url, until)
            profiles.reads = 0; profiles.writes = 0
        }
        fun assertNoWrites() {
            assertEquals(0, downloads); assertEquals(0, profiles.writes); assertEquals(0, profiles.clears)
        }
    }

    @Test fun batchReadsOneValidatedCacheWithoutChangingSelectionOrEpoch() {
        val f = Fixture()
        val selected = f.authority.choices[1].selection
        assertTrue(f.manager.selectNode(selected))
        f.profiles.reads = 0
        val epoch = f.authority.epoch
        val targets = f.manager.captureNodeProbeTargets()
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), targets.map { it.endpoint })
        assertTrue(targets.all { it.protocol == "ss" })
        assertNotSame(targets[0].selection, targets[1].selection)
        targets.forEachIndexed { index, target -> assertSame(f.authority.choices[index].selection, target.selection) }
        assertEquals(1, f.profiles.reads)
        assertEquals(epoch, f.authority.epoch); assertSame(selected, f.authority.selected)
        assertEquals("NodeProbeTarget(<redacted>)", targets[0].toString())
        assertThrows(IllegalArgumentException::class.java) { f.profiles.lastRead!!.copyBytes() }
        f.assertNoWrites()
    }

    @Test fun hostnameTargetsUseExistingValidationWithoutResolvingOrDownloading() {
        val f = Fixture(artifact("one.invalid", "two.invalid"), allowHostname = true)
        val targets = f.manager.captureNodeProbeTargets()
        assertEquals(listOf("one.invalid", "two.invalid"), targets.map { it.endpoint })
        targets.forEach { assertTrue(f.manager.isCurrentNodeProbeTarget(it)) }
        f.assertNoWrites()
    }

    @Test fun expiredUnknownAndBlockedRegistriesAreReadOnlyRejections() {
        val expired = Fixture()
        val target = expired.manager.captureNodeProbeTargets().first()
        expired.time = start.plusSeconds(600)
        val epoch = expired.authority.epoch
        assertTrue(expired.manager.captureNodeProbeTargets().isEmpty())
        assertFalse(expired.manager.isCurrentNodeProbeTarget(target))
        assertFalse(expired.manager.isNodeProbeTargetRegistryCurrent(target))
        assertEquals(epoch, expired.authority.epoch)
        assertEquals(LocalSubscriptionStatus.READY, expired.authority.selectionStatus)
        assertTrue(Fixture(until = null).manager.captureNodeProbeTargets().isEmpty())
        LocalSubscriptionStatus.values().filter { it != LocalSubscriptionStatus.READY }.forEach { status ->
            val f = Fixture(); f.authority.selectionStatus = status
            val before = f.authority.epoch
            assertTrue(status.name, f.manager.captureNodeProbeTargets().isEmpty())
            assertEquals(before, f.authority.epoch); f.assertNoWrites()
        }
        expired.assertNoWrites()
    }

    @Test fun changedCacheDigestCannotPublishResultsOrModifyTheCurrentRegistry() {
        val f = Fixture()
        val target = f.manager.captureNodeProbeTargets().first()
        val choices = f.authority.choices
        val epoch = f.authority.epoch
        f.profiles.replace(raw = artifact("192.0.2.90", "192.0.2.91"))
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        // Memory-only masking is explicitly not IO admission for an externally changed file.
        assertTrue(f.manager.isNodeProbeTargetRegistryCurrent(target))
        assertSame(choices, f.authority.choices); assertEquals(epoch, f.authority.epoch)
        f.assertNoWrites()
    }

    @Test fun fullValidationRejectsMixedUnsupportedDataEvenWithAMatchingInjectedFingerprint() {
        val f = Fixture()
        val raw = artifact("192.0.2.10", "192.0.2.11").toString(Charsets.UTF_8)
            .replaceFirst("type: ss", "type: vmess").toByteArray()
        f.profiles.replace(raw = raw)
        f.authority.selectionRevision = MessageDigest.getInstance("SHA-256").digest(raw)
        raw.fill(0)
        val epoch = f.authority.epoch
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertEquals(epoch, f.authority.epoch)
        assertThrows(IllegalArgumentException::class.java) { f.profiles.lastRead!!.copyBytes() }
        f.assertNoWrites()
    }

    @Test fun revokedCapabilityBindingAndPendingRecoveryCannotCreateTargets() {
        val binding = Fixture()
        binding.profiles.replace(binding = "not-the-current-binding")
        assertTrue(binding.manager.captureNodeProbeTargets().isEmpty())
        val capability = Fixture()
        capability.profiles.replace(url = Secret("https://other.invalid/sub/a/foreign"))
        assertTrue(capability.manager.captureNodeProbeTargets().isEmpty())
        val recovery = Fixture()
        recovery.delivery.saveLegacyRecovery(LegacyRecoveryJournal(recovery.receipt, recovery.receipt, LegacyRecoveryPhase.ATTEMPT))
        assertTrue(recovery.manager.captureNodeProbeTargets().isEmpty())
        listOf(binding, capability, recovery).forEach { it.assertNoWrites() }
    }

    @Test fun acknowledgedProfileRemainsIndependentOfAccountReceiptButLegacyFallbackMustBeAcknowledged() {
        val f = Fixture()
        f.delivery.clear()
        assertEquals(2, f.manager.captureNodeProbeTargets().size)
        f.profiles.replace(withoutUrl = true)
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        f.delivery.save(DeliveryRecord(f.receipt.accountId, f.receipt.sessionBinding, f.receipt.resource,
            f.receipt.operationKey, f.receipt.url, f.receipt.handoff, true))
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        f.delivery.save(f.receipt)
        assertEquals(2, f.manager.captureNodeProbeTargets().size)
        f.assertNoWrites()
    }

    @Test fun selectionAndPendingEpochChangesKeepTargetsButRegistryClearSchemaAndExpiryDoNot() {
        val f = Fixture()
        val target = f.manager.captureNodeProbeTargets().first()
        assertTrue(f.manager.selectNode(f.authority.choices[1].selection))
        f.manager.invalidatePending()
        val epoch = f.authority.epoch
        assertTrue(f.manager.isCurrentNodeProbeTarget(target))
        assertTrue(f.manager.isNodeProbeTargetRegistryCurrent(target))
        assertEquals(epoch, f.authority.epoch)
        f.authority.selectionSchema = SafeProxyConfig.SCHEMA_VERSION + 1
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        assertFalse(f.manager.isNodeProbeTargetRegistryCurrent(target))
        f.authority.selectionSchema = SafeProxyConfig.SCHEMA_VERSION
        f.authority.clearVersion++
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        assertFalse(f.manager.isNodeProbeTargetRegistryCurrent(target))
        f.assertNoWrites()
    }

    @Test fun foreignRemovedAndModifiedTargetsNeverPassFullAdmission() {
        val f = Fixture()
        val target = f.manager.captureNodeProbeTargets().first()
        assertFalse(f.manager.isCurrentNodeProbeTarget(NodeProbeTarget(ConnectionSelection(), target.endpoint, target.protocol)))
        assertFalse(f.manager.isCurrentNodeProbeTarget(NodeProbeTarget(target.selection, "192.0.2.99", target.protocol)))
        assertFalse(f.manager.isCurrentNodeProbeTarget(NodeProbeTarget(target.selection, target.endpoint, "tuic")))
        f.manager.invalidateNodeSelection()
        val epoch = f.authority.epoch
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        assertFalse(f.manager.isNodeProbeTargetRegistryCurrent(target))
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertEquals(epoch, f.authority.epoch); f.assertNoWrites()
    }

    @Test fun missingBlockedAndFailedStorageStayUnknownWithoutWritesOrRawFailures() {
        val f = Fixture()
        val target = f.manager.captureNodeProbeTargets().first()
        f.profiles.replace(status = LocalSubscriptionStatus.RETRY_REQUIRED)
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        f.profiles.failure = IOException("synthetic-sensitive-diagnostic")
        val epoch = f.authority.epoch
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        f.profiles.failure = null
        f.profiles.memory.clear()
        assertTrue(f.manager.captureNodeProbeTargets().isEmpty())
        assertEquals(epoch, f.authority.epoch); f.assertNoWrites()
    }

    @Test fun cancellationPropagatesAndDisplayMaskingNeverReadsStorage() {
        val f = Fixture()
        val target = f.manager.captureNodeProbeTargets().first()
        f.profiles.reads = 0
        f.profiles.failure = CancellationException("synthetic cancellation")
        assertTrue(f.manager.isNodeProbeTargetRegistryCurrent(target))
        assertEquals(0, f.profiles.reads)
        assertThrows(CancellationException::class.java) { f.manager.captureNodeProbeTargets() }
        assertThrows(CancellationException::class.java) { f.manager.isCurrentNodeProbeTarget(target) }
        f.assertNoWrites()
    }
}
