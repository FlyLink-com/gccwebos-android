package com.gccwebos.android.portal

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** 退出登录: the session and this device's account subscription go; manually imported configurations stay. */
class LogoutClearsAccountSubscriptionTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val url = Secret("https://sub.invalid/sub/a/logout-fixture")
    private val saved = SavedSession(Secret("native_synthetic"), "synthetic@example.invalid", now.plusSeconds(600), 600000, now, 0)
    private fun yaml(vararg names: String): ByteArray = buildString {
        append("proxies:\n")
        names.forEachIndexed { index, name ->
            append("  - name: $name\n    type: ss\n    server: 192.0.2.${10 + index}\n")
            append("    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n")
        }
    }.toByteArray()

    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = DeliveryRecord("fixture", "old-session-binding", "resource", "operation",
            Secret("https://sub.invalid/sub/a/logout-fixture"), Secret("handoff"), false)
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }

    private class FlakySessions(val backing: MemorySessionStore = MemorySessionStore()) : SessionStore {
        var failuresLeft = 0; var clearCalls = 0
        override fun read() = backing.read()
        override fun save(value: SavedSession) = backing.save(value)
        override fun clear() { clearCalls++; if (failuresLeft > 0) { failuresLeft--; throw PortalFailure(PortalFailure.Reason.STORAGE) }; backing.clear() }
    }

    private class FlakyProfiles(val backing: MemoryLocalProfileStore = MemoryLocalProfileStore()) : LocalProfileStore {
        var failClear = false
        override fun read() = backing.read()
        override fun save(value: CachedSubscription) = backing.save(value)
        override fun clear() { if (failClear) throw PortalFailure(PortalFailure.Reason.STORAGE); backing.clear() }
    }

    private inner class Fixture {
        val delivery = Delivery()
        val profiles = FlakyProfiles()
        val sessions = FlakySessions().apply { save(saved) }
        val sources = MemoryManualSourceStore()
        val choices = MemoryNodeChoiceStore()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = error("No Portal requests")
        }, delivery, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0 }, { now },
            SubscriptionSource { yaml("Account node") }, profiles = profiles, sessions = sessions, manualSources = sources,
            nodeChoices = choices, authority = LocalSubscriptionAuthority()) // isolated: failures must not leak process state
        val manager get() = repo.localSubscriptions
        suspend fun seedAccountAndImport(vararg imported: String) {
            manager.remember(yaml("Account node"), url, now.plusSeconds(3600))
            imported.forEach { name -> yaml(name).let { manager.importManual(it) { run -> run() } } }
        }
        fun items() = manager.configurationLibrarySnapshot().items
        fun imported() = items().filter { it.kind != ManualSourceKind.LegacyRemote }
        suspend fun activateAccount() {
            manager.activateConfiguration(items().single { it.kind == ManualSourceKind.LegacyRemote }.handle) { it() }
        }
    }

    @Test fun logoutWhileAnImportedConfigIsActiveClearsAccountDataAndKeepsTheImportedOne() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha")
        assertTrue(f.imported().single().active)
        var ownerStops = 0
        f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() })
        assertNull("session revoked", f.sessions.read())
        assertNull("delivery record forgotten", f.delivery.value)
        assertNull("cached account configuration forgotten", f.profiles.read())
        assertTrue("no account row is left", f.items().none { it.kind == ManualSourceKind.LegacyRemote })
        val kept = f.imported().single()
        assertEquals(listOf("Local Alpha"), kept.nodes.map { it.name })
        assertTrue("the active imported source is still selected", kept.active)
        assertEquals("imported source needs no owner stop", 0, ownerStops)
        assertEquals(LocalSubscriptionStatus.READY, f.manager.snapshot().status)
    }

    @Test fun logoutWhileTheAccountIsActiveMovesToNoSourceButKeepsEveryImportedEntry() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha", "Local Beta")
        f.activateAccount()
        assertTrue(f.items().single { it.kind == ManualSourceKind.LegacyRemote }.active)
        var ownerStops = 0; var fenced = 0
        f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { fenced++; it() })
        assertEquals(1, ownerStops); assertEquals(1, fenced)
        assertNull(f.sessions.read()); assertNull(f.delivery.value); assertNull(f.profiles.read())
        assertEquals(ManualSourceKind.None, f.manager.sourceKind())
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
        val imported = f.imported()
        assertEquals(setOf("Local Alpha", "Local Beta"), imported.flatMap { it.nodes }.map { it.name }.toSet())
        assertTrue("nothing is auto-selected", f.items().none { it.active })
        assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun logoutForgetsTheRememberedAccountNodeButNotTheImportedOnes() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha")
        f.choices.put("account", "account-node-key"); f.choices.put("entry:keep", "imported-node-key")
        f.repo.logoutClearingAccountSubscription(commitFence = { it() })
        assertNull(f.choices.read()["account"]); assertEquals("imported-node-key", f.choices.read()["entry:keep"])
    }

    @Test fun importedConfigsSurviveARestartAfterLogout() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha")
        f.repo.logoutClearingAccountSubscription(commitFence = { it() })
        val restarted = LocalSubscriptionManager(f.delivery, f.profiles, SubscriptionSource { error("No network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = f.sources)
        restarted.restore()
        val items = restarted.configurationLibrarySnapshot().items
        assertEquals(listOf("Local Alpha"), items.flatMap { it.nodes }.map { it.name })
        assertTrue(items.none { it.kind == ManualSourceKind.LegacyRemote })
    }

    @Test fun logoutWithNoImportedConfigsLeavesNothingBehind() = runTest {
        val f = Fixture(); f.seedAccountAndImport()
        f.activateAccount()
        f.repo.logoutClearingAccountSubscription(commitFence = { it() })
        assertNull(f.sessions.read()); assertNull(f.delivery.value); assertNull(f.profiles.read())
        assertTrue(f.items().isEmpty())
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
    }

    private suspend fun assertStorageFailure(block: suspend () -> Unit) {
        try { block(); fail("Logout with a storage failure was reported complete") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
    }

    @Test fun secondSessionClearFailureStillStopsTheConnectedAccountOwnerAndRevokesTheSource() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha"); f.activateAccount()
        f.sessions.failuresLeft = 2 // first (synchronous) and second (mutex) clear both fail
        var ownerStops = 0
        assertStorageFailure { f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() }) }
        assertEquals("owner stop must not be short-circuited", 1, ownerStops)
        assertNull(f.profiles.read()); assertNull(f.delivery.value)
        assertTrue(f.items().none { it.kind == ManualSourceKind.LegacyRemote })
        assertNotEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
        assertNotEquals(LocalSubscriptionStatus.READY, f.manager.snapshot().status)
    }

    @Test fun persistentSessionFailureIsReportedEveryTimeAndRetrySucceedsAndFullyClears() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha"); f.activateAccount()
        f.sessions.failuresLeft = 4
        var ownerStops = 0
        repeat(2) { assertStorageFailure { f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() }) } }
        assertNotNull("session still stored, so logout is not complete", f.sessions.read())
        f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() })
        assertNull(f.sessions.read()); assertNull(f.profiles.read()); assertNull(f.delivery.value)
        assertTrue(f.items().none { it.kind == ManualSourceKind.LegacyRemote })
        assertEquals(setOf("Local Alpha"), f.imported().flatMap { it.nodes }.map { it.name }.toSet())
        assertTrue(ownerStops >= 1)
    }

    @Test fun restartAfterFailedLogoutDoesNotReadmitTheAccountSource() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha"); f.activateAccount()
        f.sessions.failuresLeft = 2
        assertStorageFailure { f.repo.logoutClearingAccountSubscription(commitFence = { it() }) }
        val restarted = LocalSubscriptionManager(f.delivery, f.profiles, SubscriptionSource { error("No network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = f.sources)
        restarted.restore()
        assertNotEquals(ManualSourceKind.LegacyRemote, restarted.sourceKind())
        assertTrue(restarted.configurationLibrarySnapshot().items.none { it.kind == ManualSourceKind.LegacyRemote })
    }

    @Test fun sessionFailureWithAManualImportActiveNeverStopsOrDeletesTheImport() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha")
        f.sessions.failuresLeft = 2
        var ownerStops = 0
        assertStorageFailure { f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() }) }
        assertEquals(0, ownerStops)
        val kept = f.imported().single(); assertTrue(kept.active)
        assertEquals(LocalSubscriptionStatus.READY, f.manager.snapshot().status)
    }

    @Test fun cancelledCallerStillCompletesTheOwnerStop() = runTest {
        val f = Fixture(); f.seedAccountAndImport(); f.activateAccount()
        f.sessions.failuresLeft = 2
        var ownerStops = 0
        val job = launch { try { f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() }) } catch (_: PortalFailure) { } }
        job.cancel(); job.join()
        // Either the call never started (nothing changed) or NonCancellable cleanup finished the stop.
        if (f.profiles.read() == null) assertEquals(1, ownerStops)
    }

    @Test fun accountDataClearFailureStopsOwnerAndPersistsRevocationAcrossRestart() = runTest {
        val f = Fixture(); f.seedAccountAndImport("Local Alpha"); f.activateAccount()
        f.profiles.failClear = true
        var ownerStops = 0
        assertStorageFailure { f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() }) }
        assertEquals(1, ownerStops)
        assertNotNull("fixture: cached account config could not be deleted", f.profiles.read())
        val restarted = LocalSubscriptionManager(f.delivery, f.profiles, SubscriptionSource { error("No network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = f.sources)
        restarted.restore()
        assertNotEquals("pending-revoked account must not be re-admitted", ManualSourceKind.LegacyRemote, restarted.sourceKind())
        assertNotEquals(LocalSubscriptionStatus.READY, restarted.snapshot().status)
        // Retry once storage recovers completes the logout.
        f.profiles.failClear = false
        f.repo.logoutClearingAccountSubscription(ownerCleanup = { ownerStops++ }, commitFence = { it() })
        assertNull(f.profiles.read()); assertNull(f.sessions.read())
        assertEquals(setOf("Local Alpha"), f.imported().flatMap { it.nodes }.map { it.name }.toSet())
    }
}
