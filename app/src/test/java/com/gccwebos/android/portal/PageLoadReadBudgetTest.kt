package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Which read policy each caller of a saved third-party subscription gets: a page load the user is watching (tapping the
 * source, refreshing the list) uses the import budget; the connect step keeps the 30 s fresh read. Synthetic only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageLoadReadBudgetTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private val yaml = ("proxies:\n  - name: A\n    type: ss\n    server: 192.0.2.10\n    port: 443\n" +
        "    cipher: aes-128-gcm\n    password: synthetic-only\n").toByteArray()

    private inner class Fixture {
        var connectReads = 0
        var importReads = 0
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead { connectReads++; return SubscriptionRead(yaml.copyOf(), null) }
            override suspend fun readForImport(url: Secret): SubscriptionRead { importReads++; return SubscriptionRead(yaml.copyOf(), null) }
        }
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("no delivery")
            override fun clear() = Unit
        }, MemoryLocalProfileStore(), SubscriptionSource { error("No official HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = MemoryManualSourceStore(), externalSource = source)
        suspend fun import() { val b = "https://subscription.example/page?token=synthetic-only".toByteArray(); manager.importManual(b) { it() } }
    }

    @Test fun importUsesTheImportBudget() = runTest {
        val f = Fixture(); f.import()
        assertEquals(1, f.importReads); assertEquals(0, f.connectReads)
    }

    @Test fun tappingASavedSourceReloadsItWithTheImportBudget() = runTest {
        val f = Fixture(); f.import()
        val second = "https://subscription.example/second?token=synthetic-only".toByteArray()
        f.manager.importManual(second) { it() }
        val other = f.manager.configurationLibrarySnapshot(false).items.first { !it.active }
        f.manager.activateConfiguration(other.handle) { it() }
        assertEquals("one import each plus the activation download", 3, f.importReads); assertEquals(0, f.connectReads)
    }

    @Test fun aPlainRefreshOfTheSelectedSourceIsAPageLoadToo() = runTest {
        val f = Fixture(); f.import()
        f.manager.refresh()
        assertEquals(2, f.importReads); assertEquals(0, f.connectReads)
    }

    @Test fun theConnectStepKeepsItsOwnFreshReadPolicy() = runTest {
        val f = Fixture(); f.import()
        val dir = java.nio.file.Files.createTempDirectory("page-load-budget").toFile()
        try {
            f.manager.prepare(null, LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic", 0) },
                AtomicPreparedConfigStore(dir))?.close()
        } finally { dir.deleteRecursively() }
        assertEquals("connect read", 1, f.connectReads); assertEquals("only the import itself used the import budget", 1, f.importReads)
    }
}
