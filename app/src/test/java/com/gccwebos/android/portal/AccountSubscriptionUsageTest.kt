package com.gccwebos.android.portal

import com.gccwebos.android.design.subscriptionUsagePresentation
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val GB = 1_073_741_824L

/** The account subscription (账户订阅 / LegacyRemote) keeps only its last download's own reported usage. */
class AccountSubscriptionUsageTest {
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val yaml = """proxies:
  - name: Synthetic account node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = DeliveryRecord("fixture", "old-session-binding", "resource", "operation",
            Secret("https://sub.invalid/sub/a/synthetic"), Secret("handoff"), false)
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }

    private inner class Fixture {
        var now = Instant.parse("2026-10-09T00:00:00Z")
        var usage: SubscriptionUsageReport? = SubscriptionUsageReport(GB, 2 * GB, 10 * GB)
        var downloads = 0
        val profiles = MemoryLocalProfileStore()
        val delivery = Delivery()
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                downloads++; return SubscriptionRead(yaml.copyOf(), Instant.parse("2099-01-01T00:00:00Z"), usage)
            }
        }
        fun manager() = LocalSubscriptionManager(delivery, profiles, source, policy, { now })
        val manager = manager()
    }

    @Test fun refreshStoresReportedUsageAndRestoresItAfterRestart() = runTest {
        val f = Fixture()
        val refreshed = f.manager.refresh()
        assertEquals(LocalSubscriptionStatus.READY, refreshed.status)
        val expected = SubscriptionUsage(GB, 2 * GB, 10 * GB, f.now)
        assertEquals(expected, refreshed.usage)
        f.profiles.read()!!.use { assertEquals(expected, it.usage) }
        // Signed out after a restart: restore does not download, yet still shows the stored report.
        val downloads = f.downloads
        assertEquals(expected, f.manager().restore().usage)
        assertEquals(downloads, f.downloads)
    }

    @Test fun aDownloadWithoutUsageReplacesTheOldValueWithUnknown() = runTest {
        val f = Fixture(); f.manager.refresh()
        f.now = f.now.plusSeconds(3600); f.usage = SubscriptionUsageReport(3 * GB, 3 * GB, 10 * GB)
        assertEquals(SubscriptionUsage(3 * GB, 3 * GB, 10 * GB, f.now), f.manager.refresh().usage)
        f.usage = null
        assertNull(f.manager.refresh().usage)
        f.profiles.read()!!.use { assertNull(it.usage) }
        assertNull(f.manager().restore().usage)
    }

    @Test fun aChangedSubscriptionUrlNeverInheritsUsage() = runTest {
        val f = Fixture(); assertNotNull(f.manager.refresh().usage)
        val rotated = Secret("https://sub.invalid/sub/a/rotated")
        // A remember() for another capability is refused outright, so it cannot pair old usage with a new URL.
        assertThrows(IllegalStateException::class.java) { f.manager.remember(yaml, rotated, null) }
        f.profiles.read()!!.use { assertNotNull(it.usage) }
        // The new capability's profile is built only from its own download; with no report there, usage is unknown.
        f.profiles.clear()
        f.delivery.value = DeliveryRecord("fixture", "new-session-binding", "resource", "operation", rotated, Secret("handoff"), false)
        f.usage = null
        assertNull(f.manager().restore().usage)
        f.profiles.read()!!.use { assertEquals(sessionBinding(rotated), it.binding); assertNull(it.usage) }
    }

    @Test fun rememberedDeliveryCarriesOnlyItsOwnUsage() = runTest {
        val f = Fixture(); val url = f.delivery.value!!.url!!
        val usage = SubscriptionUsage(1, 2, 10 * GB, f.now)
        assertEquals(usage, f.manager.remember(yaml, url, null, usage).usage)
        assertEquals(usage, f.manager().restore().usage)
        assertNull(f.manager.remember(yaml, url, null).usage)
        assertNull(f.manager().restore().usage)
    }

    @Test fun zeroReportsAreUnknownForTheAccountSource() = runTest {
        for (header in listOf("upload=0; download=0; total=0", "upload=5; download=5; total=0")) {
            val read = decodeSubscriptionResponse(200, yaml.copyOf(), null, listOf(header))
            try { assertNull(header, read.usage) } finally { read.bytes.fill(0) }
        }
        val f = Fixture(); f.usage = null
        val snapshot = f.manager.refresh()
        assertNull(snapshot.usage)
        // The card shows 暂未获取 for null presentation, never "0 GB".
        assertNull(subscriptionUsagePresentation(snapshot.usage, snapshot.validUntil, f.now))
    }

    @Test fun accountSnapshotPresentsAsSubscriptionProvided() = runTest {
        val f = Fixture(); f.now = Instant.parse("2026-10-09T08:30:00Z")
        val snapshot = f.manager.refresh()
        assertEquals(ManualSourceKind.LegacyRemote, snapshot.sourceKind)
        val shown = subscriptionUsagePresentation(snapshot.usage, snapshot.validUntil, f.now, ZoneId.of("UTC"))!!
        assertEquals("订阅提供 · 更新于 2026-10-09 08:30", shown.sourceText)
        assertEquals("7 GB", shown.remainingText); assertEquals("3 GB", shown.usedText); assertEquals("10 GB", shown.totalText)
        assertEquals("订阅提供", shown.asCardPresentation().title)
    }

    @Test fun cachedSubscriptionNeverPrintsUsage() {
        val cached = CachedSubscription("a".repeat(64), yaml.copyOf(), null, null, usage = SubscriptionUsage(123456789, 1, 987654321, Instant.EPOCH))
        cached.use { assertFalse(it.toString().contains("123456789")); assertFalse(it.toString().contains("987654321")) }
    }
}

class LocalProfileCodecTest {
    private val binding = "b".repeat(64)
    private val body = "proxies: []\n".toByteArray()

    private fun v1(): ByteArray = JSONObject().put("version", 1).put("binding", binding)
        .put("body", java.util.Base64.getEncoder().encodeToString(body)).put("validUntil", "2099-01-01T00:00:00Z")
        .put("url", "https://sub.invalid/sub/a/synthetic").put("observedStatus", JSONObject.NULL).toString().toByteArray()

    private fun v2(usage: Any?): ByteArray = JSONObject(String(v1())).put("version", 2).put("usage", usage ?: JSONObject.NULL).toString().toByteArray()

    @Test fun v1RecordsFromOlderBuildsDecodeWithUnknownUsage() {
        LocalProfileCodec.decode(v1()).use { decoded ->
            assertEquals(binding, decoded.binding); assertNull(decoded.usage)
            assertEquals(Instant.parse("2099-01-01T00:00:00Z"), decoded.validUntil)
            val bytes = decoded.copyBytes(); try { assertArrayEquals(body, bytes) } finally { bytes.fill(0) }
        }
    }

    @Test fun recordsWithoutUsageStillWriteTheExactV1Format() {
        CachedSubscription(binding, body.copyOf(), Instant.parse("2099-01-01T00:00:00Z"), Secret("https://sub.invalid/sub/a/synthetic")).use {
            val encoded = JSONObject(String(LocalProfileCodec.encode(it)))
            assertEquals(1, encoded.get("version"))
            assertEquals(setOf("version", "binding", "body", "validUntil", "url", "observedStatus"), encoded.keys().asSequence().toSet())
        }
    }

    @Test fun usageRoundTripsAsV2() {
        val usage = SubscriptionUsage(Long.MAX_VALUE - 1, 0, Long.MAX_VALUE, Instant.parse("2026-10-09T01:02:03.456Z"))
        CachedSubscription(binding, body.copyOf(), null, null, LocalSubscriptionStatus.EXPIRED, usage).use { saved ->
            val plain = LocalProfileCodec.encode(saved)
            val json = JSONObject(String(plain))
            assertEquals(2, json.get("version"))
            assertEquals(setOf("upload", "download", "total", "observedAt"), json.getJSONObject("usage").keys().asSequence().toSet())
            LocalProfileCodec.decode(plain).use { decoded ->
                assertEquals(usage, decoded.usage); assertEquals(LocalSubscriptionStatus.EXPIRED, decoded.observedStatus)
                assertNull(decoded.url); assertNull(decoded.validUntil)
            }
        }
    }

    @Test fun malformedOrMismatchedVersionsAreRejected() {
        fun usage() = JSONObject().put("upload", "1").put("download", "2").put("total", "10").put("observedAt", "2026-10-09T00:00:00Z")
        val bad = listOf(
            JSONObject(String(v1())).put("usage", usage()).toString().toByteArray(), // v1 must not carry usage
            JSONObject(String(v1())).put("version", 2).toString().toByteArray(),   // v2 must carry usage
            v2(null),
            v2(usage().put("total", "0")),                                         // zero total is unknown, never stored
            v2(usage().put("upload", "-1")),
            v2(usage().put("upload", "01")),
            v2(usage().put("total", "99999999999999999999")),                       // overflow
            v2(usage().put("total", 10)),                                           // numbers must be canonical strings
            v2(usage().put("extra", "1")),
            v2(usage().put("observedAt", "not-a-time")),
            JSONObject(String(v1())).put("version", 3).toString().toByteArray(),
        )
        for (plain in bad) assertThrows(String(plain), Exception::class.java) { LocalProfileCodec.decode(plain).close() }
        LocalProfileCodec.decode(v2(usage())).use { assertEquals(SubscriptionUsage(1, 2, 10, Instant.parse("2026-10-09T00:00:00Z")), it.usage) }
    }
}
