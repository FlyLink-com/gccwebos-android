package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ConfigurationLibraryTest {
    private val until = Instant.parse("2099-01-01T00:00:00Z")
    private fun id() = UUID.randomUUID().toString()
    private fun local(revision: String = id(), marker: String = "synthetic-local") =
        ManualSourceRecord(revision, ManualSourceKind.ImportedLocal, marker.toByteArray())
    private fun entry(name: String = "配置", source: ManualSourceRecord = local(), id: String = id()): ConfigurationEntry =
        source.use { ConfigurationEntry(id, name, it) }
    private fun library(vararg entries: ConfigurationEntry, selected: ConfigurationEntry = entries.first()): ConfigurationLibrary =
        ConfigurationLibrary(entries = entries.toList(), selector = ConfigurationSelector.Entry,
            selectedEntryId = selected.id, selectorRevision = selected.sourceRevision)

    @Test fun entryLibraryAndMemoryStoreOwnIndependentCopies() {
        val raw = "synthetic-owned-material".toByteArray()
        val source = ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw)
        val item = ConfigurationEntry(id(), "名称", source)
        val input = mutableListOf(item)
        val owned = ConfigurationLibrary(entries = input, selector = ConfigurationSelector.Entry,
            selectedEntryId = item.id, selectorRevision = item.sourceRevision)
        input.clear(); source.close(); item.close(); raw.fill(0)
        val store = MemoryManualSourceStore(null)
        store.saveLibrary(owned); owned.close()
        repeat(2) {
            store.readLibrary()!!.use { saved ->
                assertEquals(1, saved.entries.size)
                saved.activeSource().use { value ->
                    val bytes = value.copyBytes()!!
                    try { assertEquals("synthetic-owned-material", bytes.toString(Charsets.UTF_8)) }
                    finally { bytes.fill(0) }
                }
                try { (saved.entries as MutableList).clear(); fail("Library list is mutable") }
                catch (_: UnsupportedOperationException) { }
            }
        }
    }

    @Test fun nameNormalizationIsBoundedAndDoesNotAcceptUrlsControlsOrMalformedUnicode() {
        assertEquals("我的配置", ConfigurationName.normalize("  我的配置  "))
        assertEquals("😀".repeat(48), ConfigurationName.normalize("😀".repeat(48)))
        for (name in listOf("", "  ", "a".repeat(49), "a\nb", "a\tb", "a\u0000b", "a\u202eb", "a\u2066b",
            "a\u200fb", "a\u061cb", "a\u2028b", "https://synthetic.example/path", "prefix vless://synthetic", "\ud800")) {
            rejected { ConfigurationName.normalize(name) }
        }
    }

    @Test fun legacyReadMappingPreservesEverySourceRevisionAndNeverClaimsAccountMaterial() {
        val sources = listOf(ManualSourceRecord(kind = ManualSourceKind.LegacyRemote),
            ManualSourceRecord(kind = ManualSourceKind.None), ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true),
            local(), ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = byteArrayOf(1),
                url = Secret("https://sub.example/sub/a/synthetic-only"), validUntil = until),
            ManualSourceRecord(kind = ManualSourceKind.ExternalRemote, bytes = byteArrayOf(2),
                url = Secret("https://subscriptions.example/config?token=synthetic-only")))
        try { sources.forEach { source ->
            val original = ManualSourceCodec.encode(source)
            val before = original.copyOf()
            try { repeat(2) {
                ConfigurationLibraryCodec.decode(original).use { normalized ->
                    assertEquals(source.revision, normalized.revision)
                    normalized.activeSource().use { assertTrue(source.sameEnvelope(it)) }
                    if (source.kind in ConfigurationEntry.MANUAL_KINDS) {
                        assertEquals(ConfigurationSelector.Entry, normalized.selector)
                        assertEquals(source.revision, normalized.entries.single().id)
                        assertEquals("配置 1", normalized.entries.single().name)
                    } else assertTrue(normalized.entries.isEmpty())
                    assertEquals(source.clearPending, normalized.clearPending)
                }
                assertArrayEquals(before, original)
            } } finally { original.fill(0); before.fill(0) }
        } } finally { sources.forEach { it.close() } }
    }

    @Test fun v3RoundTripRetainsSameNameEntriesAndEveryFieldOfAllThreeManualKinds() {
        val items = listOf(entry("同名"), entry("同名", ManualSourceRecord(kind = ManualSourceKind.ImportedRemote,
            bytes = byteArrayOf(2, 3), url = Secret("https://sub.example/sub/a/synthetic-only"), validUntil = until,
            observedStatus = LocalSubscriptionStatus.EXPIRED)), entry("同名", ManualSourceRecord(kind = ManualSourceKind.ExternalRemote,
            bytes = byteArrayOf(4, 5), url = Secret("https://subscriptions.example/config?token=synthetic-only"),
            observedStatus = LocalSubscriptionStatus.INVALID_LINK)))
        try { library(*items.toTypedArray(), selected = items.last()).use { original ->
            val encoded = ConfigurationLibraryCodec.encode(original)
            try {
                assertEquals(3, ByteBuffer.wrap(encoded).getInt(4))
                ConfigurationLibraryCodec.decode(encoded).use { restored ->
                    assertTrue(original.sameEnvelope(restored)); assertEquals(3, restored.entries.size)
                    restored.activeSource().use { assertEquals(ManualSourceKind.ExternalRemote, it.kind); assertNull(it.validUntil) }
                }
                rejected { ManualSourceCodec.decode(encoded) } // Old readers must not adopt v3 as legacy.
            } finally { encoded.fill(0) }
        } } finally { items.forEach { it.close() } }
    }

    @Test fun selectorMustResolveExactlyAndPendingClearCannotRetainEntries() {
        entry().use { first ->
            rejected { ConfigurationLibrary(entries = listOf(first), selector = ConfigurationSelector.Entry) }
            rejected { ConfigurationLibrary(entries = listOf(first), selector = ConfigurationSelector.Entry,
                selectedEntryId = id(), selectorRevision = first.sourceRevision) }
            rejected { ConfigurationLibrary(entries = listOf(first), selector = ConfigurationSelector.Entry,
                selectedEntryId = first.id, selectorRevision = id()) }
            rejected { ConfigurationLibrary(entries = listOf(first), selector = ConfigurationSelector.None, selectedEntryId = first.id) }
            rejected { ConfigurationLibrary(entries = listOf(first), selectorRevision = first.sourceRevision) }
            rejected { ConfigurationLibrary(entries = listOf(first), selector = ConfigurationSelector.None, clearPending = true) }
            rejected { ConfigurationLibrary(clearPending = true) }
            ConfigurationLibrary(selector = ConfigurationSelector.None, clearPending = true).use { pending ->
                val bytes = ConfigurationLibraryCodec.encode(pending)
                try { ConfigurationLibraryCodec.decode(bytes).use { restored ->
                    assertTrue(restored.clearPending); assertTrue(restored.entries.isEmpty())
                    restored.activeSource().use { assertTrue(it.clearPending); assertEquals(ManualSourceKind.None, it.kind) }
                } } finally { bytes.fill(0) }
            }
        }
    }

    @Test fun duplicateIdsRevisionsNonManualEntriesAndEntryCountOverflowAreRejected() {
        entry().use { first ->
            first.copySource().use { same ->
                ConfigurationEntry(id(), "第二个", same).use { repeatedRevision ->
                    rejected { library(first, repeatedRevision) }
                }
            }
            local().use { other -> ConfigurationEntry(first.id, "第二个", other).use { repeatedId ->
                rejected { library(first, repeatedId) }
            } }
        }
        for (kind in listOf(ManualSourceKind.None, ManualSourceKind.LegacyRemote)) {
            ManualSourceRecord(kind = kind).use { rejected { ConfigurationEntry(id(), "配置", it) } }
        }
        val entries = List(21) { entry() }
        try {
            library(*entries.take(20).toTypedArray()).close()
            rejected { library(*entries.toTypedArray()) }
        } finally { entries.forEach { it.close() } }
        rejected { ConfigurationLibrary(revision = "not-a-uuid") }
        local().use { rejected { ConfigurationEntry("not-a-uuid", "配置", it) } }
    }

    @Test fun cumulativeLimitIsEnforcedEvenWhenEveryIndividualRecordIsInBounds() {
        val raw = ByteArray(SafeProxyConfig.MAX_BYTES) { 7 }
        val prefix = "https://sub.example/sub/a/"
        val url = Secret(prefix + "s".repeat(SafeProxyConfig.MAX_BYTES - prefix.length))
        val entries = List(20) { entry(source = ManualSourceRecord(kind = ManualSourceKind.ImportedRemote,
            bytes = raw, url = url, validUntil = until)) }
        try {
            library(*entries.take(10).toTypedArray()).use { bounded ->
                val encoded = ConfigurationLibraryCodec.encode(bounded)
                try { assertTrue(encoded.size <= ConfigurationLibrary.MAX_ENCODED_BYTES) }
                finally { encoded.fill(0) }
            }
            rejected { library(*entries.toTypedArray()) }
            rejected { ConfigurationLibraryCodec.decode(ByteArray(ConfigurationLibrary.MAX_ENCODED_BYTES + 1)) }
        } finally { raw.fill(0); entries.forEach { it.close() } }
    }

    @Test fun compatibilityWritesPreserveInactiveEntriesAndNamesAfterExplicitV3Adoption() {
        entry("一个").use { first -> entry("两个").use { second -> library(first, second).use { initial ->
            val store = MemoryManualSourceStore(null)
            store.saveLibrary(initial)
            local(first.sourceRevision, "synthetic-refreshed").use { store.save(it) }
            store.readLibrary()!!.use { refreshed ->
                assertEquals(listOf(first.id, second.id), refreshed.entries.map { it.id })
                assertEquals("一个", refreshed.entries.first().name)
                assertEquals(first.id, refreshed.selectedEntryId)
            }
            local(marker = "synthetic-new").use { added ->
                store.save(added)
                store.readLibrary()!!.use { appended ->
                    assertEquals(3, appended.entries.size); assertEquals(added.revision, appended.selectedEntryId)
                    assertEquals("配置 3", appended.entries.last().name)
                }
            }
            for (kind in listOf(ManualSourceKind.None, ManualSourceKind.LegacyRemote)) {
                ManualSourceRecord(kind = kind).use { selector ->
                    store.save(selector)
                    store.read()!!.use { assertTrue(selector.sameEnvelope(it)) }
                    store.readLibrary()!!.use { assertEquals(3, it.entries.size) }
                }
            }
            ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { pending -> rejected { store.save(pending) } }
            store.readLibrary()!!.use { assertEquals(3, it.entries.size) }
            ConfigurationLibrary(selector = ConfigurationSelector.None, clearPending = true).use { store.saveLibrary(it) }
            store.readLibrary()!!.use { assertTrue(it.entries.isEmpty()); assertTrue(it.clearPending) }
        } } }
    }

    @Test fun oldMemoryStoreRemainsSingleSourceUntilExplicitLibraryWrite() {
        val store = MemoryManualSourceStore(null)
        assertNull(store.read()); assertNull(store.readLibrary())
        local().use { first -> store.save(first) }
        local().use { second ->
            store.save(second)
            store.readLibrary()!!.use { normalized ->
                assertEquals(1, normalized.entries.size); assertEquals(second.revision, normalized.selectedEntryId)
            }
        }
        ManualSourceRecord(kind = ManualSourceKind.None).use(store::save)
        store.readLibrary()!!.use { assertTrue(it.entries.isEmpty()) }
    }

    @Test fun codecRejectsEveryTruncationTrailingDataMalformedUtf8AndUnknownHeaderWithoutInputInErrors() {
        entry("test").use { item -> library(item).use { original ->
            val encoded = ConfigurationLibraryCodec.encode(original)
            try {
                for (end in encoded.indices) rejected { ConfigurationLibraryCodec.decode(encoded.copyOf(end)) }
                rejected { ConfigurationLibraryCodec.decode(encoded + byteArrayOf(0)) }
                for (version in listOf(0, 4, Int.MAX_VALUE)) changedInt(encoded, 4, version)
                for (selector in listOf(-1, 3, Int.MAX_VALUE)) changedInt(encoded, 48, selector)
                // Header with Entry selector: 8+40+4+40+40+1 = 133 byte entry-count offset.
                for (count in listOf(-1, 0, 21, Int.MAX_VALUE)) changedInt(encoded, 133, count)
                val malformed = encoded.copyOf().also { it[181] = 0xff.toByte() } // First name byte.
                try { rejected { ConfigurationLibraryCodec.decode(malformed) } } finally { malformed.fill(0) }
                val invalidFlag = encoded.copyOf().also { it[132] = 2 }
                try { rejected { ConfigurationLibraryCodec.decode(invalidFlag) } } finally { invalidFlag.fill(0) }
            } finally { encoded.fill(0) }
        } }
    }

    @Test fun wholeEnvelopeReadbackIncludesInactiveMaterialNamesAndSelectorRevision() {
        entry("甲").use { first -> entry("乙").use { second -> library(first, second).use { baseline ->
            second.copySource().use { source -> ConfigurationEntry(second.id, "丙", source).use { renamed ->
                ConfigurationLibrary(baseline.revision, listOf(first, renamed), baseline.selector,
                    baseline.selectedEntryId, baseline.selectorRevision).use { assertFalse(baseline.sameEnvelope(it)) }
            } }
            local(second.sourceRevision, "synthetic-altered-inactive").use { source ->
                ConfigurationEntry(second.id, second.name, source).use { changed ->
                    ConfigurationLibrary(baseline.revision, listOf(first, changed), baseline.selector,
                        baseline.selectedEntryId, baseline.selectorRevision).use { assertFalse(baseline.sameEnvelope(it)) }
                }
            }
            ConfigurationLibrary(baseline.revision, baseline.entries, ConfigurationSelector.Entry,
                second.id, second.sourceRevision).use { assertFalse(baseline.sameEnvelope(it)) }
            assertEquals("ConfigurationLibrary([REDACTED])", baseline.toString())
            assertEquals("ConfigurationEntry([REDACTED])", first.toString())
        } } }
    }

    private fun changedInt(bytes: ByteArray, offset: Int, value: Int) {
        val changed = bytes.copyOf()
        try { ByteBuffer.wrap(changed).putInt(offset, value); rejected { ConfigurationLibraryCodec.decode(changed) } }
        finally { changed.fill(0) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid synthetic library accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
