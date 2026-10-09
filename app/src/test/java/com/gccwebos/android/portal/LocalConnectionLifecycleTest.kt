package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalConnectionLifecycleTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private fun node(name: String = "same name", host: String = "192.0.2.10") = """  - name: $name
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
"""
    private val yaml get() = ("proxies:\n" + node() + node(host = "192.0.2.11")).toByteArray()
    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = record("synthetic-a")
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
        companion object {
            fun record(key: String) = DeliveryRecord("fixture", "old-account", "resource", "operation",
                Secret("https://sub.invalid/sub/a/$key"), Secret("handoff"), false)
        }
    }
    private inner class Fixture {
        val delivery = Delivery()
        val profiles = MemoryLocalProfileStore()
        val authority = LocalSubscriptionAuthority()
        var reads = 0
        var bytes = yaml
        var read: suspend () -> ByteArray = { bytes.copyOf() }
        var time = now
        val source = SubscriptionSource { reads++; read() }
        val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
        val manager = LocalSubscriptionManager(delivery, profiles, source, policy, { time }, authority)
        val directory = Files.createTempDirectory("local-contract").toFile()
        var failWrite = false
        // Pure contract writer: no OS permission/atomic-move claim. Production writer stays fail-closed.
        val files = PreparedConfigStore {
            check(directory.isDirectory)
            if (failWrite) throw java.io.IOException("Synthetic write failure")
            java.io.File(directory, "synthetic-prepared-marker").writeText("synthetic-only")
        }
        val gateway = LocalSubscriptionConfigGateway(manager, files)
        suspend fun choices() = (gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.SelectionRequired).choices
        fun owner(scope: CoroutineScope, tunnel: Engine = Engine(), permission: PermissionGateway = object : PermissionGateway {
            override suspend fun hasVpnPermission() = true
        }) = ConnectionCoordinator(gateway, permission, tunnel, object : TrafficProbe {
            override suspend fun isUsable() = true
        }, scope, ConnectionTimeouts(1000, 1000, 1000))
    }
    private class Engine : LifecycleTunnel {
        val callbacks = mutableListOf<(TunnelTerminal) -> Unit>()
        val accepted = mutableListOf<ByteArray>()
        var running = false
        var stops = 0
        var failStop = false
        var atAccept: (() -> Unit)? = null
        override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean {
            callbacks += terminal; accepted += config.copyMaterial(); running = true
            atAccept?.invoke()
            return true
        }
        override suspend fun stop() {
            stops++
            if (failStop) error("synthetic cleanup failure")
            accepted.forEach { it.fill(0) }; running = false
        }
    }
    @Test fun identicalFreshBytesRetainOpaqueSelectionAndEveryAttemptDownloads() = runTest {
        val f = Fixture()
        try {
            val choices = f.choices(); assertEquals(2, choices.size)
            assertNotSame(choices[0].selection, choices[1].selection)
            repeat(2) {
                val ready = f.gateway.prepare(SelectionIntent.Selected(choices[1].selection)) as ConfigPreparation.Ready
                assertTrue(ready.config.activate { true }); ready.config.close()
            }
            assertEquals(3, f.reads)
            assertFalse(choices[0].selection.toString().contains("synthetic"))
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun contentChangesRequireReselectionAndNeverGuessDuplicateNames() = runTest {
        val replacements = listOf(
            "proxies:\n" + node(),
            yaml.toString(Charsets.UTF_8).replace("password: synthetic-only", "password: new-synthetic"),
            yaml.toString(Charsets.UTF_8).replace("port: 443", "port: 444"),
            yaml.toString(Charsets.UTF_8).replace("same name", "renamed"))
        for (replacement in replacements) {
            val f = Fixture()
            try {
                val selected = f.choices()[1].selection; f.bytes = replacement.toByteArray()
                assertTrue(f.gateway.prepare(SelectionIntent.Selected(selected)) is ConfigPreparation.SelectionRequired)
                assertEquals(0, f.directory.listFiles()!!.size)
            } finally { f.directory.deleteRecursively() }
        }
    }
    @Test fun reorderedOrCosmeticallyChangedContentKeepsTheSameDuplicateNamedNode() = runTest {
        val replacements = listOf(
            "proxies:\n" + node(host = "192.0.2.11") + node(),
            yaml.toString(Charsets.UTF_8) + "# comment\n")
        for (replacement in replacements) {
            val f = Fixture()
            try {
                val selected = f.choices()[1].selection; f.bytes = replacement.toByteArray()
                val ready = f.gateway.prepare(SelectionIntent.Selected(selected)) as ConfigPreparation.Ready
                val material = ready.config.copyMaterial().toString(Charsets.UTF_8)
                assertTrue(material.contains("192.0.2.11")); assertFalse(material.contains("192.0.2.10"))
                ready.config.close()
            } finally { f.directory.deleteRecursively() }
        }
    }
    @Test fun forgedForeignAndClearedSelectionsAreNotAuthority() = runTest {
        val f = Fixture()
        try {
            val selection = f.choices()[0].selection
            assertTrue(f.gateway.prepare(SelectionIntent.Selected(ConnectionSelection())) is ConfigPreparation.SelectionRequired)
            f.manager.unlink(); f.delivery.value = Delivery.record("synthetic-a")
            assertTrue(f.gateway.prepare(SelectionIntent.Selected(selection)) is ConfigPreparation.SelectionRequired)
            val current = f.choices()[0].selection
            f.manager.unlink(); f.delivery.value = Delivery.record("synthetic-b")
            assertTrue(f.gateway.prepare(SelectionIntent.Selected(current)) is ConfigPreparation.SelectionRequired)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun mixedUnsupportedArtifactFailsBeforeAnySelectionOrFileWrite() = runTest {
        val f = Fixture()
        try {
            f.bytes = ("proxies:\n" + node() + node().replace("type: ss", "type: vmess")).toByteArray()
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.UNSUPPORTED), f.gateway.prepare(SelectionIntent.Choose))
            assertEquals(0, f.directory.listFiles()!!.size)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun explicitFailuresRemainTypedWithoutCachedFallback() = runTest {
        for ((reason, expected) in listOf(
            PortalFailure.Reason.SUBSCRIPTION_EXPIRED to PreparationFailure.EXPIRED,
            PortalFailure.Reason.SUBSCRIPTION_REVOKED to PreparationFailure.INVALID_LINK,
            PortalFailure.Reason.NETWORK to PreparationFailure.NETWORK,
            PortalFailure.Reason.STORAGE to PreparationFailure.STORAGE)) {
            val f = Fixture()
            try {
                val selection = f.choices()[0].selection
                f.read = { throw PortalFailure(reason) }
                assertEquals(ConfigPreparation.Unavailable(expected), f.gateway.prepare(SelectionIntent.Selected(selection)))
                assertEquals(0, f.directory.listFiles()!!.size)
            } finally { f.directory.deleteRecursively() }
        }
    }
    @Test fun localConnectRestartSwitchNeverRequirePortalAndRestoreStartsDisconnected() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val choose = owner.start().await() as ConnectionState.Failed
            assertEquals(Failure.SELECTION_REQUIRED, choose.reason)
            assertEquals(ConnectionState.Connected("local-selection"), owner.start(SelectionIntent.Selected(choose.choices[0].selection)).await())
            assertTrue(owner.restart().await() is ConnectionState.Connected)
            assertTrue(owner.switchSelection(choose.choices[1].selection).await() is ConnectionState.Connected)
            assertEquals(4, f.reads); assertEquals(3, engine.callbacks.size)
            owner.close()
            val restored = f.owner(backgroundScope)
            assertEquals(ConnectionState.Disconnected, restored.state.value)
            assertEquals(4, f.reads)
            restored.close()
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun uiAwaitCancellationDoesNotStopButExplicitStopDoes() = runTest {
        val f = Fixture(); val permission = CompletableDeferred<Boolean>(); val engine = Engine()
        val owner = f.owner(backgroundScope, engine, object : PermissionGateway { override suspend fun hasVpnPermission() = permission.await() })
        try {
            val selected = f.choices()[0].selection
            val command = owner.start(SelectionIntent.Selected(selected))
            val ui = launch { command.await() }; runCurrent(); ui.cancelAndJoin()
            permission.complete(true); runCurrent()
            assertTrue(owner.state.value is ConnectionState.Connected); assertTrue(engine.running)
            assertEquals(ConnectionState.Disconnected, owner.stop().await()); assertFalse(engine.running)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun stopDuringIgnoringCancellationDownloadCannotSaveWriteOrActivate() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            f.read = { withContext(NonCancellable) { entered.complete(Unit); finish.await(); yaml } }
            val pending = owner.start(SelectionIntent.Selected(selected)); entered.await()
            f.profiles.clear()
            val stop = owner.stop(); finish.complete(Unit)
            assertEquals(ConnectionState.Disconnected, stop.await())
            assertEquals(ConnectionState.Disconnected, pending.await())
            assertNull(f.profiles.read()); assertEquals(0, f.directory.listFiles()!!.size); assertTrue(engine.callbacks.isEmpty())
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun stopDuringPermissionCannotBeRevivedByLateResult() = runTest {
        val f = Fixture(); val finish = CompletableDeferred<Boolean>(); val engine = Engine()
        val owner = f.owner(backgroundScope, engine, object : PermissionGateway {
            override suspend fun hasVpnPermission() = withContext(NonCancellable) { finish.await() }
        })
        try {
            val selected = f.choices()[0].selection
            val pending = owner.start(SelectionIntent.Selected(selected)); runCurrent()
            val stop = owner.stop(); finish.complete(true)
            assertEquals(ConnectionState.Disconnected, stop.await()); assertEquals(ConnectionState.Disconnected, pending.await())
            assertTrue(engine.callbacks.isEmpty()); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun staleTerminalCallbacksCannotKillNewGeneration() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            owner.start(SelectionIntent.Selected(selected)).await(); val old = engine.callbacks.last()
            owner.restart().await(); old(TunnelTerminal.ENGINE_DIED); runCurrent()
            assertTrue(owner.state.value is ConnectionState.Connected); assertTrue(engine.running)
            engine.callbacks.last()(TunnelTerminal.PERMISSION_REVOKED); runCurrent()
            assertEquals(ConnectionState.Failed(Failure.PERMISSION_REVOKED), owner.state.value); assertFalse(engine.running)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun synchronousEngineDeathNeverPublishesConnected() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            engine.atAccept = { engine.callbacks.last()(TunnelTerminal.ENGINE_DIED) }
            val selected = f.choices()[0].selection
            assertEquals(ConnectionState.Failed(Failure.ENGINE_DIED), owner.start(SelectionIntent.Selected(selected)).await())
            assertFalse(engine.running)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun clearFromAnotherManagerStopsOwnerAndFencesOldPreparation() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            owner.start(SelectionIntent.Selected(selected)).await()
            val other = LocalSubscriptionManager(f.delivery, f.profiles, f.source, f.policy, { now }, f.authority)
            other.unlink(); runCurrent()
            assertEquals(ConnectionState.Disconnected, owner.state.value); assertFalse(engine.running)
            assertNull(f.profiles.read()); assertNull(f.delivery.read())
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun timeoutIsTypedAndCancellationDoesNotBecomeNetworkError() = runTest {
        val f = Fixture(); val owner = f.owner(backgroundScope)
        try {
            f.read = { awaitCancellation() }
            val command = owner.start(); advanceTimeBy(1001); runCurrent()
            assertEquals(ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.TIMEOUT), command.await())
            val second = owner.start(); runCurrent(); second.cancel(); runCurrent()
            assertEquals(ConnectionState.Disconnected, second.await())
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun oneOwnerLeaseAndCleanupFailurePreventAnotherStart() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            assertThrows(IllegalStateException::class.java) { f.owner(backgroundScope) }
            val selected = f.choices()[0].selection
            owner.start(SelectionIntent.Selected(selected)).await(); engine.failStop = true
            assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), owner.restart().await())
            assertEquals(1, engine.callbacks.size)
            assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), owner.close())
            assertThrows(IllegalStateException::class.java) { f.owner(backgroundScope) }
            engine.failStop = false; owner.close()
            f.owner(backgroundScope).close()
        } finally { engine.failStop = false; owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun expiryWhileAwaitingPermissionRemainsTypedAndNeverWritesOrAccepts() = runTest {
        val f = Fixture(); val permission = CompletableDeferred<Boolean>(); val engine = Engine()
        f.manager.remember(yaml, f.delivery.value!!.url!!, now.plusSeconds(10))
        val owner = f.owner(backgroundScope, engine, object : PermissionGateway {
            override suspend fun hasVpnPermission() = permission.await()
        })
        try {
            val selected = f.choices()[0].selection
            val command = owner.start(SelectionIntent.Selected(selected)); runCurrent()
            f.time = now.plusSeconds(11); permission.complete(true)
            assertEquals(ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.EXPIRED), command.await())
            assertTrue(engine.callbacks.isEmpty()); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun storageFailureNeverCrossesTheEngineAcceptanceBoundary() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            f.directory.delete(); f.directory.writeText("not a directory")
            assertEquals(ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.STORAGE),
                owner.start(SelectionIntent.Selected(selected)).await())
            assertTrue(engine.callbacks.isEmpty())
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun injectedWriterFailureCannotAcceptEngineOrCreatePreparedFile() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            f.failWrite = true
            assertEquals(ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.STORAGE),
                owner.start(SelectionIntent.Selected(selected)).await())
            assertTrue(engine.callbacks.isEmpty()); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }
    @Test fun otherManagerRefreshInvalidatesPreparedAcceptanceEvenForIdenticalContent() = runTest {
        val f = Fixture()
        try {
            val selected = f.choices()[0].selection
            val prepared = (f.gateway.prepare(SelectionIntent.Selected(selected)) as ConfigPreparation.Ready).config
            val other = LocalSubscriptionManager(f.delivery, f.profiles, f.source, f.policy, { now }, f.authority)
            other.refresh()
            assertEquals(PreparationFailure.STALE, assertThrows(PreparationRejected::class.java) {
                prepared.activate { fail("Stale material must not activate"); true }
            }.reason)
            prepared.close(); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun differentCapabilityWithIdenticalBytesCannotReuseASelection() = runTest {
        val f = Fixture()
        try {
            val selected = f.choices()[0].selection
            f.profiles.clear(); f.delivery.value = Delivery.record("different-subscription")
            assertTrue(f.gateway.prepare(SelectionIntent.Selected(selected)) is ConfigPreparation.SelectionRequired)
            assertEquals(0, f.directory.listFiles()!!.size)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun clearFromOtherManagerDuringDownloadCannotResurrectStateOrStorage() = runTest {
        val f = Fixture(); val engine = Engine(); val owner = f.owner(backgroundScope, engine)
        try {
            val selected = f.choices()[0].selection
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            f.read = { withContext(NonCancellable) { entered.complete(Unit); finish.await(); yaml } }
            val command = owner.start(SelectionIntent.Selected(selected)); entered.await()
            val other = LocalSubscriptionManager(f.delivery, f.profiles, f.source, f.policy, { now }, f.authority)
            other.unlink(); finish.complete(Unit)
            assertEquals(ConnectionState.Disconnected, command.await()); runCurrent()
            assertEquals(ConnectionState.Disconnected, owner.state.value)
            assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
            assertNull(f.profiles.read()); assertNull(f.delivery.read())
            assertTrue(engine.callbacks.isEmpty()); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }

    @Test fun unreadableProfileReturnsTypedStorageWithoutRetryingFailedRead() = runTest {
        val f = Fixture(); var reads = 0
        val broken = object : LocalProfileStore {
            override fun read(): CachedSubscription? { reads++; throw PortalFailure(PortalFailure.Reason.STORAGE) }
            override fun save(value: CachedSubscription) = error("Unexpected save")
            override fun clear() = Unit
        }
        val manager = LocalSubscriptionManager(f.delivery, broken, f.source, f.policy, { now }, LocalSubscriptionAuthority())
        try {
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.STORAGE), manager.prepare(SelectionIntent.Choose, f.files))
            assertEquals(1, reads); assertEquals(0, f.reads)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun invalidStoredCapabilityReturnsTypedInvalidLinkWithoutNetwork() = runTest {
        val f = Fixture()
        try {
            for (url in listOf("https://evil.invalid/sub/a/key", "https://sub.invalid/sub/a/[")) {
                f.delivery.value = DeliveryRecord("fixture", "binding", "resource", "operation", Secret(url), null, false)
                assertEquals(ConfigPreparation.Unavailable(PreparationFailure.INVALID_LINK), f.gateway.prepare(SelectionIntent.Choose))
                assertEquals(0, f.reads)
            }
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun observationPersistenceFailureRemainsTypedStorage() = runTest {
        val f = Fixture(); var failSave = false
        val profiles = object : LocalProfileStore {
            override fun read() = f.profiles.read()
            override fun save(value: CachedSubscription) {
                if (failSave) throw PortalFailure(PortalFailure.Reason.STORAGE)
                f.profiles.save(value)
            }
            override fun clear() = f.profiles.clear()
        }
        val manager = LocalSubscriptionManager(f.delivery, profiles, f.source, f.policy, { now }, LocalSubscriptionAuthority())
        try {
            manager.remember(yaml, f.delivery.value!!.url!!, now.plusSeconds(100))
            failSave = true; f.read = { throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_EXPIRED) }
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.STORAGE), manager.prepare(SelectionIntent.Choose, f.files))
        } finally { f.directory.deleteRecursively() }
    }

    @Test fun observedInterveningContentChangeCannotReviveOldSelectionAcrossManagers() = runTest {
        for (otherManager in listOf(false, true)) {
            val f = Fixture()
            try {
                val selected = f.choices()[0].selection
                f.bytes = yaml.toString(Charsets.UTF_8).replace("password: synthetic-only", "password: changed").toByteArray()
                val observer = if (otherManager) LocalSubscriptionManager(f.delivery, f.profiles, f.source,
                    f.policy, { now }, f.authority) else f.manager
                observer.refresh(); f.bytes = yaml
                assertTrue(f.gateway.prepare(SelectionIntent.Selected(selected)) is ConfigPreparation.SelectionRequired)
                assertEquals(0, f.directory.listFiles()!!.size)
            } finally { f.directory.deleteRecursively() }
        }
    }
    @Test fun recreatedManagerPreservesCapabilityBoundKnownExpiryWithoutRestore() = runTest {
        val f = Fixture()
        try {
            f.manager.remember(yaml, f.delivery.value!!.url!!, now.minusSeconds(1))
            val recreated = LocalSubscriptionManager(f.delivery, f.profiles, f.source, f.policy, { now }, f.authority)
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.EXPIRED), recreated.prepare(SelectionIntent.Choose, f.files))
            f.profiles.read()!!.use { assertEquals(now.minusSeconds(1), it.validUntil) }
            // Metadata from another capability cannot block this new subscription.
            f.profiles.clear(); f.delivery.value = Delivery.record("new-capability")
            assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.SelectionRequired)
        } finally { f.directory.deleteRecursively() }
    }
    @Test fun storageFailureAtPermissionCompletionStaysTyped() = runTest {
        val f = Fixture(); var broken = false
        val profiles = object : LocalProfileStore {
            override fun read(): CachedSubscription? {
                if (broken) throw PortalFailure(PortalFailure.Reason.STORAGE)
                return f.profiles.read()
            }
            override fun save(value: CachedSubscription) = f.profiles.save(value)
            override fun clear() = f.profiles.clear()
        }
        val manager = LocalSubscriptionManager(f.delivery, profiles, f.source, f.policy, { now }, f.authority)
        val gateway = LocalSubscriptionConfigGateway(manager, f.files)
        val permission = CompletableDeferred<Boolean>(); val engine = Engine()
        val owner = ConnectionCoordinator(gateway, object : PermissionGateway {
            override suspend fun hasVpnPermission() = permission.await()
        }, engine, object : TrafficProbe { override suspend fun isUsable() = true }, backgroundScope)
        try {
            val selected = (gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.SelectionRequired).choices[0].selection
            val command = owner.start(SelectionIntent.Selected(selected)); runCurrent()
            broken = true; permission.complete(true)
            assertEquals(ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.STORAGE), command.await())
            assertTrue(engine.callbacks.isEmpty()); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { owner.close(); f.directory.deleteRecursively() }
    }

    @Test fun failedRememberWriteStillInvalidatesEarlierPreparedMaterial() = runTest {
        val f = Fixture(); var failSave = false
        val profiles = object : LocalProfileStore {
            override fun read() = f.profiles.read()
            override fun save(value: CachedSubscription) {
                if (failSave) throw PortalFailure(PortalFailure.Reason.STORAGE)
                f.profiles.save(value)
            }
            override fun clear() = f.profiles.clear()
        }
        val manager = LocalSubscriptionManager(f.delivery, profiles, f.source, f.policy, { now }, f.authority)
        try {
            val selected = (manager.prepare(SelectionIntent.Choose, f.files) as ConfigPreparation.SelectionRequired).choices[0].selection
            val prepared = (manager.prepare(SelectionIntent.Selected(selected), f.files) as ConfigPreparation.Ready).config
            failSave = true
            assertThrows(PortalFailure::class.java) {
                manager.remember((yaml.toString(Charsets.UTF_8) + "# changed\n").toByteArray(), f.delivery.value!!.url!!, null)
            }
            assertEquals(PreparationFailure.STALE, assertThrows(PreparationRejected::class.java) {
                prepared.activate { fail("Prior material must not activate after failed remember"); true }
            }.reason)
            prepared.close(); assertEquals(0, f.directory.listFiles()!!.size)
        } finally { f.directory.deleteRecursively() }
    }

}
