package com.gccwebos.android.portal

import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.core.ConnectionTransportLease
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.PreparationRejected
import com.gccwebos.android.core.SelectionIntent
import com.gccwebos.android.vpn.SafeProxyConfig
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic admission fences only: no DNS, Android services, filesystem writer or native engine. */
class AddressPreparationFenceTest {
    private val start = Instant.parse("2026-10-04T00:00:00Z")

    private fun artifact(vararg hosts: String): ByteArray =
        ("proxies:\n" + hosts.mapIndexed { index, host ->
            """  - name: synthetic node $index
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: $PRIVATE_MARKER
"""
        }.joinToString("")).toByteArray()

    private class Delivery : DeliveryStore {
        private val capability = Secret(URI("https", "fixture.subscription.invalid",
            "/sub/a/synthetic_capability", null).toASCIIString())
        var record: DeliveryRecord? = DeliveryRecord("synthetic-account", "synthetic-session",
            "synthetic-resource", "synthetic-operation-key", capability, Secret("synthetic-handoff"), false)
        override fun read() = record
        override fun save(record: DeliveryRecord) { this.record = record }
        override fun clear() { record = null }
    }

    private class Lease : ConnectionTransportLease {
        override val networkHandle = 71L
        override val validUntilElapsedMillis = Long.MAX_VALUE
        var current = true
        var closes = 0
        override fun isCurrent() = current && closes == 0
        override fun close() { if (closes == 0) { closes++; current = false } }
    }

    private class Writer : PreparedConfigStore {
        var writes = 0
        var duringWrite: () -> Unit = {}
        val copies = mutableListOf<ByteArray>()
        override fun replace(bytes: ByteArray) {
            writes++
            copies += bytes.copyOf()
            duringWrite()
        }
        override fun clear() { copies.forEach { it.fill(0) }; copies.clear() }
    }

    private inner class Fixture(var raw: ByteArray = artifact(HOST)) : AutoCloseable {
        val delivery = Delivery()
        val profiles = MemoryLocalProfileStore()
        val writer = Writer()
        var time = start
        val until = start.plusSeconds(600)
        var nativeStarts = 0
        val manager = LocalSubscriptionManager(delivery, profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret) = SubscriptionRead(raw.copyOf(), until)
        }, OriginPolicy(URI("https", "fixture.portal.invalid", null, null).toASCIIString(),
            setOf(URI("https", "fixture.subscription.invalid", null, null).toASCIIString())),
            { time }, authority = LocalSubscriptionAuthority(), allowHostnamePreparation = true)

        suspend fun select(index: Int = 0): ConnectionSelection {
            assertEquals(LocalSubscriptionStatus.READY, manager.refresh().status)
            val selected = manager.nodeSelectionSnapshot().choices[index].selection
            assertTrue(manager.selectNode(selected))
            return selected
        }

        suspend fun prepare(selection: ConnectionSelection, preparer: ConfigAddressPreparer): AuthorizedConfig {
            val result = manager.prepare(SelectionIntent.Selected(selection), writer, preparer)
            assertTrue("A current validated address may be prepared", result is ConfigPreparation.Ready)
            return (result as ConfigPreparation.Ready).config
        }

        fun activate(config: AuthorizedConfig) = config.activate { nativeStarts++; true }
        override fun close() { writer.clear(); profiles.clear(); delivery.clear(); raw.fill(0) }
    }

    private fun resolver(lease: Lease = Lease(), address: String = "192.0.2.44") =
        ConfigAddressPreparer { raw, index ->
            PreparedAddressConfig(SafeProxyConfig.projectResolved(raw, index, address), lease)
        }

    private suspend fun rejectedPreparation(block: suspend () -> ConfigPreparation) {
        try {
            val result = block()
            if (result is ConfigPreparation.Ready) {
                result.config.close()
                fail("A stale preparation must never publish Ready")
            }
        } catch (failure: Exception) {
            assertRedactedFailure(failure)
        }
    }

    private fun rejectedActivation(f: Fixture, config: AuthorizedConfig, writes: Int = 0, starts: Int = 0) {
        var accepted = false
        try { accepted = f.activate(config) }
        catch (failure: Exception) {
            assertRedactedFailure(failure)
        }
        assertFalse("A revoked preparation cannot cross engine acceptance", accepted)
        assertEquals(writes, f.writer.writes)
        assertEquals(starts, f.nativeStarts)
    }

    private fun assertRedactedFailure(failure: Exception) {
        for (privateValue in listOf(PRIVATE_MARKER, HOST, "synthetic_capability")) {
            assertFalse("Fence errors must not expose private material", failure.toString().contains(privateValue))
        }
        assertNull("Fence errors must not retain an unsafe cause", failure.cause)
    }

    /** The other thread prevents a monitor held across suspension from passing by reentrancy. */
    private fun revokeWithoutWaitingForResolution(action: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit(Runnable(action)).get(2, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    @Test fun invalidatePendingDoesNotWaitForSuspendedResolverAndRejectsLateResult() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease = Lease()
            var borrowed: ByteArray? = null
            var produced: ByteArray? = null
            val preparer = ConfigAddressPreparer { raw, index ->
                borrowed = raw
                entered.complete(Unit)
                release.await()
                SafeProxyConfig.projectResolved(raw, index, "192.0.2.44").also { produced = it }
                    .let { PreparedAddressConfig(it, lease) }
            }
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                rejectedPreparation { f.manager.prepare(SelectionIntent.Selected(selected), f.writer, preparer) }
            }
            entered.await()
            try {
                revokeWithoutWaitingForResolution(f.manager::invalidatePending)
                assertFalse("Revocation must finish while resolution is still pending", work.isCompleted)
            } finally { release.complete(Unit) }
            work.await()
            assertEquals(0, f.writer.writes)
            assertEquals(0, f.nativeStarts)
            assertTrue("Borrowed raw material must be wiped", requireNotNull(borrowed).all { it == 0.toByte() })
            assertTrue("Rejected address output must be wiped", requireNotNull(produced).all { it == 0.toByte() })
            assertEquals(1, lease.closes)
        }
    }

    @Test fun unlinkDoesNotWaitForSuspendedResolverOrRestoreClearedCapability() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease = Lease()
            val preparer = ConfigAddressPreparer { raw, index ->
                entered.complete(Unit); release.await()
                PreparedAddressConfig(SafeProxyConfig.projectResolved(raw, index, "192.0.2.44"), lease)
            }
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                rejectedPreparation { f.manager.prepare(SelectionIntent.Selected(selected), f.writer, preparer) }
            }
            entered.await()
            try {
                revokeWithoutWaitingForResolution(f.manager::unlink)
                assertNull(f.delivery.read())
                assertNull(f.profiles.read())
                assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
            } finally { release.complete(Unit) }
            work.await()
            assertNull(f.delivery.read())
            assertNull(f.profiles.read())
            assertEquals(0, f.writer.writes)
            assertEquals(0, f.nativeStarts)
            assertEquals(1, lease.closes)
        }
    }

    @Test fun invalidationAfterPreparationPreventsWriteAndEngineAcceptance() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            f.prepare(selected, resolver()).use { config ->
                f.manager.invalidatePending()
                rejectedActivation(f, config)
            }
        }
    }

    @Test fun changingSelectionAfterPreparationPreventsWriteAndEngineAcceptance() = runTest {
        Fixture(artifact(HOST, "second.fixture.invalid")).use { f ->
            val selected = f.select()
            f.prepare(selected, resolver()).use { config ->
                val second = f.manager.nodeSelectionSnapshot().choices[1].selection
                assertTrue(f.manager.selectNode(second))
                rejectedActivation(f, config)
            }
        }
    }

    @Test fun exactExpiryAfterPreparationPreventsWriteAndEngineAcceptance() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            f.prepare(selected, resolver()).use { config ->
                f.time = f.until
                rejectedActivation(f, config)
            }
        }
    }

    @Test fun revokedLeaseAfterPreparationPreventsWriteAndEngineAcceptance() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val lease = Lease()
            f.prepare(selected, resolver(lease)).use { config ->
                lease.current = false
                rejectedActivation(f, config)
            }
            assertEquals(1, lease.closes)
        }
    }

    @Test fun writeBoundaryRechecksEpochAndLeaseBeforeEngineAcceptance() = runTest {
        for (revokeEpoch in listOf(true, false)) {
            Fixture().use { f ->
                val selected = f.select()
                val lease = Lease()
                f.prepare(selected, resolver(lease)).use { config ->
                    f.writer.duringWrite = {
                        if (revokeEpoch) f.manager.invalidatePending() else lease.current = false
                    }
                    rejectedActivation(f, config, writes = 1)
                    assertTrue("A rejected write boundary clears prepared material", f.writer.copies.isEmpty())
                }
            }
        }
    }

    @Test fun resolvedAddressChangesDoNotChangeLogicalSelectionOrPersistResolvedBytes() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            for (address in listOf("192.0.2.44", "192.0.2.45")) {
                f.prepare(selected, resolver(address = address)).use { config ->
                    assertSame(selected, f.manager.nodeSelectionSnapshot().selected)
                    assertTrue(f.activate(config))
                    val written = requireNotNull(f.writer.copies.lastOrNull())
                    val node = JSONObject(String(written, Charsets.UTF_8)).getJSONArray("proxies").getJSONObject(0)
                    assertTrue("Only the selected native endpoint is replaced", node.getString("server") == address)
                    val cached = requireNotNull(f.profiles.read())
                    cached.use {
                        val saved = it.copyBytes()
                        try { assertTrue("Persisted subscription stays logical and original", f.raw.contentEquals(saved)) }
                        finally { saved.fill(0) }
                    }
                }
            }
            assertEquals(2, f.writer.writes)
            assertEquals(2, f.nativeStarts)
            assertSame(selected, f.manager.nodeSelectionSnapshot().selected)
        }
    }

    @Test fun preparedMaterialIsWipedAndLeaseTransfersUntilAuthorizedConfigCloses() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val lease = Lease()
            var borrowed: ByteArray? = null
            var produced: ByteArray? = null
            val config = f.prepare(selected, ConfigAddressPreparer { raw, index ->
                borrowed = raw
                val bytes = SafeProxyConfig.projectResolved(raw, index, "192.0.2.44").also { produced = it }
                PreparedAddressConfig(bytes, lease)
            })
            try {
                assertTrue("Preparation raw copy must be wiped", requireNotNull(borrowed).all { it == 0.toByte() })
                assertTrue("Transferred DTO copy must be wiped", requireNotNull(produced).all { it == 0.toByte() })
                assertTrue("Closing preparation bytes must not revoke the transferred lease", lease.isCurrent())
                assertSame(lease, config.transportLease)
                assertTrue(f.activate(config))
                rejectedActivation(f, config, writes = 1, starts = 1)
            } finally { config.close() }
            config.close()
            assertEquals(1, lease.closes)
            assertThrows(IllegalStateException::class.java) { config.copyMaterial() }
        }
    }

    @Test fun cancelledResolutionWipesLateOutputAndClosesLeaseWithoutPublishingOrWriting() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease = Lease()
            var borrowed: ByteArray? = null
            var produced: ByteArray? = null
            val preparer = ConfigAddressPreparer { raw, index ->
                borrowed = raw
                val output = SafeProxyConfig.projectResolved(raw, index, "192.0.2.44").also { produced = it }
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                PreparedAddressConfig(output, lease)
            }
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                f.manager.prepare(SelectionIntent.Selected(selected), f.writer, preparer)
            }
            entered.await()
            work.cancel()
            release.complete(Unit)
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(0, f.writer.writes)
            assertEquals(0, f.nativeStarts)
            assertTrue("Cancelled borrowed raw copy must be wiped", requireNotNull(borrowed).all { it == 0.toByte() })
            assertTrue("Cancelled address output must be wiped", requireNotNull(produced).all { it == 0.toByte() })
            assertEquals(1, lease.closes)
        }
    }

    @Test fun addressPreparationFailureHasNoWriteOrEngineFallback() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            rejectedPreparation {
                f.manager.prepare(SelectionIntent.Selected(selected), f.writer, ConfigAddressPreparer { _, _ ->
                    throw PreparationRejected(PreparationFailure.NETWORK)
                })
            }
            assertEquals(0, f.writer.writes)
            assertEquals(0, f.nativeStarts)
        }
    }

    @Test fun invalidUnselectedNodeBlocksAllAddressPreparationWithoutDroppingNode() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            f.raw = artifact(HOST, "second.fixture.invalid")
                .toString(Charsets.UTF_8).replace("synthetic node 1", "synthetic node 1\n    plugin: forbidden").toByteArray()
            var calls = 0
            val result = f.manager.prepare(SelectionIntent.Selected(selected), f.writer, ConfigAddressPreparer { raw, index ->
                calls++
                PreparedAddressConfig(SafeProxyConfig.projectResolved(raw, index, "192.0.2.44"))
            })
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.UNSUPPORTED), result)
            assertEquals(0, calls)
            assertEquals(0, f.writer.writes)
            assertEquals(0, f.nativeStarts)
        }
    }

    @Test fun preparationAndAuthorizedDiagnosticsNeverContainPrivateMaterial() = runTest {
        Fixture().use { f ->
            val selected = f.select()
            val lease = Lease()
            val owned = PreparedAddressConfig(PRIVATE_MARKER.toByteArray(), lease)
            try { assertFalse(owned.toString().contains(PRIVATE_MARKER)) }
            finally { owned.close() }
            val result = f.manager.prepare(SelectionIntent.Selected(selected), f.writer, resolver())
            assertTrue(result is ConfigPreparation.Ready)
            (result as ConfigPreparation.Ready).config.use { config ->
                for (privateValue in listOf(PRIVATE_MARKER, HOST, "synthetic_capability")) {
                    assertFalse("Ready diagnostics are redacted", result.toString().contains(privateValue))
                    assertFalse("Authorized diagnostics are redacted", config.toString().contains(privateValue))
                }
            }
            rejectedPreparation {
                f.manager.prepare(SelectionIntent.Selected(selected), f.writer, ConfigAddressPreparer { _, _ ->
                    throw PreparationRejected(PreparationFailure.NETWORK)
                })
            }
        }
    }

    private companion object {
        const val HOST = "node.fixture.invalid"
        const val PRIVATE_MARKER = "synthetic_private_address_fixture"
    }
}
