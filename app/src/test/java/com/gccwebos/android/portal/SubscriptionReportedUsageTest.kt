package com.gccwebos.android.portal

import com.gccwebos.android.design.subscriptionUsagePresentation
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test

private const val GIB = 1_073_741_824L

class SubscriptionUserinfoParserTest {
    @Test fun parsesCountersAndExpiryFromOneHeader() {
        val parsed = SubscriptionUserinfoParser.parse("upload=${GIB}; download=${2 * GIB}; total=${10 * GIB}; expire=1798761600")
        assertEquals(SubscriptionUsageReport(GIB, 2 * GIB, 10 * GIB), parsed.usage)
        assertEquals(3 * GIB, parsed.usage!!.usedBytes)
        assertEquals(Instant.ofEpochSecond(1798761600), parsed.expiry)
        // Case-insensitive keys, extra unknown fields and whitespace keep their existing tolerance.
        assertEquals(SubscriptionUsageReport(1, 2, 3), SubscriptionUserinfoParser.parse(" Upload=1 ;DOWNLOAD=2; total=3; plan=basic").usage)
    }

    @Test fun zeroTotalAllZeroOrMissingCountersAreUnknownNeverZeroRemaining() {
        for (value in listOf("upload=0; download=0; total=0; expire=0", "upload=5; download=5; total=0",
            "upload=1; download=2", "download=2; total=3", "upload=1; total=3", "expire=1798761600")) {
            assertNull(value, SubscriptionUserinfoParser.parse(value).usage)
        }
        assertNull(SubscriptionUserinfoParser.parse("upload=0; download=0; total=0; expire=0").expiry)
    }

    @Test fun malformedValuesAreRejectedWithoutEchoingInput() {
        for (value in listOf("", "upload", "upload=-1; download=0; total=1", "total=1.5", "total=01", "total=+1",
            "total=99999999999999999999", "total=9223372036854775808", "upload=1; Upload=2", "total=1, total=2",
            "expire=253402300800", "a=1;".repeat(30), "x=" + "a".repeat(2048), "total=1\u0001")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { SubscriptionUserinfoParser.parse(value) }
            assertNull(failure.message); assertNull(failure.cause)
        }
    }

    @Test fun thirdPartyMetadataIsStrictAndCarriesUsage() {
        val metadata = manualSubscriptionMetadata(Headers.headersOf("Subscription-Userinfo", "upload=1; download=2; total=30; expire=1798761600"))
        assertEquals(SubscriptionUsageReport(1, 2, 30), metadata.usage)
        assertEquals(Instant.ofEpochSecond(1798761600), metadata.expiry)
        assertNull(manualSubscriptionMetadata(Headers.headersOf()).usage)
        assertThrows(ManualSubscriptionMetadataFailure::class.java) {
            manualSubscriptionMetadata(Headers.headersOf("Subscription-Userinfo", "total=x"))
        }
    }

    @Test fun officialDownloadReadsUsageButMalformedUsageIsOnlyUnknown() {
        val ok = decodeSubscriptionResponse(200, "proxies: []".toByteArray(), "2099-01-01T00:00:00Z",
            listOf("upload=1; download=2; total=30; expire=4070908800"))
        assertEquals(SubscriptionUsageReport(1, 2, 30), ok.usage)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), ok.validUntil)
        for (values in listOf(emptyList(), listOf("upload=0; download=0; total=0; expire=4070908800"),
            listOf("total=bad"), listOf("total=1", "total=1"))) {
            val read = decodeSubscriptionResponse(200, "proxies: []".toByteArray(), "2099-01-01T00:00:00Z", values)
            assertNull(read.usage); assertNotNull(read.validUntil)
        }
    }

    @Test fun usageValuesAreRedacted() {
        val usage = SubscriptionUsage(123456789, 987654321, 5 * GIB, Instant.EPOCH)
        assertEquals("SubscriptionUsage([REDACTED])", usage.toString())
        assertEquals("SubscriptionUsageReport([REDACTED])", SubscriptionUsageReport(1, 2, 3).toString())
        assertFalse(LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, usage = usage).toString().contains("123456789"))
        assertThrows(IllegalArgumentException::class.java) { SubscriptionUsage(0, 0, 0, Instant.EPOCH) }
    }
}

class SubscriptionUsageStoreTest {
    private val raw = "synthetic-configuration".toByteArray()
    private val revision = "01234567-89ab-4cde-8f01-23456789abcd"
    private val officialUrl = Secret("https://sub.gccwebos.com/sub/a/synthetic-store-only.yaml")
    private val externalUrl = Secret("https://subscriptions.example.net/client?token=synthetic%2Fstore&format=clash")
    private val until = Instant.parse("2099-01-01T00:00:00Z")
    private val usage = SubscriptionUsage(GIB, 2 * GIB, 10 * GIB, Instant.parse("2026-10-08T12:34:56.789Z"))

    @Test fun remoteRecordsWithUsageUseV4AndRoundTripExactly() {
        for ((kind, url, expiry) in listOf(Triple(ManualSourceKind.ImportedRemote, officialUrl, until),
            Triple(ManualSourceKind.ExternalRemote, externalUrl, null), Triple(ManualSourceKind.ExternalRemote, externalUrl, until))) {
            ManualSourceRecord(revision, kind, raw, url, expiry, usage = usage).use { record ->
                val encoded = ManualSourceCodec.encode(record)
                try {
                    assertEquals(4, ByteBuffer.wrap(encoded).getInt(4))
                    assertEquals(4, ConfigurationLibraryCodec.version(encoded))
                    ManualSourceCodec.decode(encoded).use { decoded ->
                        assertTrue(record.sameEnvelope(decoded)); assertEquals(usage, decoded.usage)
                    }
                    // A bare v4 envelope (pre-library store) still loads through the library reader.
                    ConfigurationLibraryCodec.decode(encoded).use { library ->
                        library.activeSource().use { assertEquals(usage, it.usage) }
                    }
                    assertThrows(IllegalArgumentException::class.java) { ManualSourceCodec.decode(encoded.copyOf(encoded.size - 1)) }
                    assertThrows(IllegalArgumentException::class.java) { ManualSourceCodec.decode(encoded + byteArrayOf(0)) }
                } finally { encoded.fill(0) }
            }
        }
    }

    @Test fun recordsWithoutUsageKeepV1V2BytesAndOldRecordsMigrateAsUnknown() {
        ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw, officialUrl, until).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                assertEquals(1, ByteBuffer.wrap(encoded).getInt(4))
                ManualSourceCodec.decode(encoded).use { assertNull(it.usage) }
            } finally { encoded.fill(0) }
        }
        ManualSourceRecord(revision, ManualSourceKind.ExternalRemote, raw, externalUrl).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
                ManualSourceCodec.decode(encoded).use { assertNull(it.usage) }
            } finally { encoded.fill(0) }
        }
    }

    @Test fun usageIsPartOfReadbackIdentityAndOnlyRemoteKindsCarryIt() {
        ManualSourceRecord(revision, ManualSourceKind.ExternalRemote, raw, externalUrl, usage = usage).use { a ->
            ManualSourceRecord(revision, ManualSourceKind.ExternalRemote, raw, externalUrl).use { b -> assertFalse(a.sameEnvelope(b)) }
            a.duplicate().use { assertTrue(a.sameEnvelope(it)); assertEquals(usage, it.usage) }
        }
        assertThrows(IllegalArgumentException::class.java) { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw, usage = usage) }
        assertThrows(IllegalArgumentException::class.java) { ManualSourceRecord(kind = ManualSourceKind.LegacyRemote, usage = usage) }
        assertThrows(IllegalArgumentException::class.java) { ManualSourceRecord(kind = ManualSourceKind.None, usage = usage) }
    }

    @Test fun v4KindAndNanosCorruptionIsRejected() {
        ManualSourceRecord(revision, ManualSourceKind.ExternalRemote, raw, externalUrl, usage = usage).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                val badNanos = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 4, 1_000_000_000) }
                assertThrows(IllegalArgumentException::class.java) { ManualSourceCodec.decode(badNanos) }
                val badTotal = encoded.copyOf().also { ByteBuffer.wrap(it).putLong(it.size - 4 - 8 - 8, 0) }
                assertThrows(IllegalArgumentException::class.java) { ManualSourceCodec.decode(badTotal) }
            } finally { encoded.fill(0) }
        }
        // A local configuration cannot be relabelled as v4.
        ManualSourceRecord(revision, ManualSourceKind.ImportedLocal, raw).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                val relabelled = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(4, 4) }
                assertThrows(IllegalArgumentException::class.java) { ManualSourceCodec.decode(relabelled) }
            } finally { encoded.fill(0) }
        }
    }

    @Test fun libraryEntriesPersistUsagePerConfiguration() {
        ManualSourceRecord(kind = ManualSourceKind.ExternalRemote, bytes = raw, url = externalUrl, usage = usage).use { withUsage ->
            ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw).use { local ->
                ConfigurationEntry(withUsage.revision, "配置 1", withUsage).use { first ->
                    ConfigurationEntry(local.revision, "配置 2", local).use { second ->
                        ConfigurationLibrary(entries = listOf(first, second), selector = ConfigurationSelector.Entry,
                            selectedEntryId = second.id, selectorRevision = local.revision).use { library ->
                            val encoded = ConfigurationLibraryCodec.encode(library)
                            try {
                                ConfigurationLibraryCodec.decode(encoded).use { decoded ->
                                    assertTrue(library.sameEnvelope(decoded))
                                    decoded.entries[0].copySource().use { assertEquals(usage, it.usage) }
                                    decoded.entries[1].copySource().use { assertNull(it.usage) }
                                }
                            } finally { encoded.fill(0) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionUsageLifecycleTest {
    private val externalUrl = "https://subscription.example/api/v1/client/subscribe?token=synthetic"
    private val otherUrl = "https://subscription.example/api/v1/client/subscribe?token=other"
    private val officialUrl = "https://sub.invalid/sub/a/synthetic"
    private val yaml = """proxies:
  - name: Synthetic external node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private inner class Fixture {
        var now = Instant.parse("2026-10-05T00:00:00Z")
        var usage: SubscriptionUsageReport? = SubscriptionUsageReport(GIB, 2 * GIB, 10 * GIB)
        var until: Instant? = Instant.parse("2099-01-01T00:00:00Z")
        val store = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("Manual import must not create delivery")
            override fun clear() = Unit
        }
        private val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret) = SubscriptionRead(yaml.copyOf(), until, usage)
        }
        fun manager() = LocalSubscriptionManager(delivery, profiles, source,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, externalSource = source)
        val manager = manager()
        suspend fun import(value: String) = manager.importManual(value.toByteArray()) { it() }
    }

    @Test fun importStoresUsagePerSourceAndRestoresAfterRestart() = runTest {
        for (link in listOf(externalUrl, officialUrl)) {
            val f = Fixture()
            val imported = f.import(link)
            assertEquals(LocalSubscriptionStatus.READY, imported.status)
            assertEquals(SubscriptionUsage(GIB, 2 * GIB, 10 * GIB, f.now), imported.usage)
            assertEquals(imported.usage, f.manager().restore().usage)
            f.store.read()!!.use { assertEquals(imported.usage, it.usage) }
        }
    }

    @Test fun explicitRefreshReplacesUsageAndAMissingHeaderBecomesUnknown() = runTest {
        val f = Fixture(); f.import(externalUrl)
        f.now = f.now.plusSeconds(3600); f.usage = SubscriptionUsageReport(3 * GIB, 4 * GIB, 10 * GIB)
        assertEquals(SubscriptionUsage(3 * GIB, 4 * GIB, 10 * GIB, f.now), f.manager.refresh().usage)
        f.usage = null
        assertNull(f.manager.refresh().usage)
        f.store.read()!!.use { assertNull(it.usage) }
    }

    @Test fun usageIsNeverCarriedToAnotherUrlOrLocalFileAndIsClearedOnRemoval() = runTest {
        val f = Fixture(); f.import(externalUrl)
        f.usage = null
        assertNull(f.import(otherUrl).usage)
        assertNull(f.import(String(yaml)).usage)
        f.usage = SubscriptionUsageReport(1, 1, 10)
        assertNotNull(f.import(externalUrl).usage)
        val removed = f.manager.removeManualSource { it() }
        assertNull(removed.usage)
    }

    @Test fun presentationLabelsSourceAndNeverInventsZeroRemaining() {
        val zone = ZoneId.of("UTC")
        val shown = subscriptionUsagePresentation(SubscriptionUsage(GIB, 2 * GIB, 10 * GIB, Instant.parse("2026-10-08T12:34:00Z")),
            Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"), zone)!!
        assertEquals("7 GB", shown.remainingText); assertEquals("3 GB", shown.usedText); assertEquals("10 GB", shown.totalText)
        assertEquals(0.3f, shown.progress!!, 0.0001f)
        assertEquals("订阅提供 · 更新于 2026-10-08 12:34", shown.sourceText)
        assertEquals("2026-11-01 00:00:00 Z", shown.expiresAtText)
        assertFalse(shown.serviceExpired)
        assertNull(subscriptionUsagePresentation(null, null, Instant.EPOCH, zone))
        // Over-quota is a reported exhaustion, displayed as 0, not negative.
        val over = subscriptionUsagePresentation(SubscriptionUsage(8 * GIB, 4 * GIB, 10 * GIB, Instant.EPOCH), null, Instant.EPOCH, zone)!!
        assertEquals("0 GB", over.remainingText); assertNull(over.expiresAtText); assertEquals(1f, over.progress!!, 0f)
    }
}
