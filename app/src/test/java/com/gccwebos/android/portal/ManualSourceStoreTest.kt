package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ManualSourceStoreTest {
    private val until = Instant.parse("2099-01-01T00:00:00Z")
    private val url = Secret("https://sub.gccwebos.com/sub/a/synthetic-store-only.yaml")
    private val raw = "synthetic-configuration".toByteArray()
    private val externalUrl = Secret("https://subscriptions.example.net/client?token=synthetic%2Fstore&format=clash")
    private val fixtureRevision = "01234567-89ab-4cde-8f01-23456789abcd"

    // valueOf keeps these contracts executable against the v1-only baseline for the RED run.
    private val externalKind get() = ManualSourceKind.valueOf("ExternalRemote")

    @Test fun existingFourKindsKeepFrozenV1EncodingAndReadback() {
        val fixtures = listOf(
            ManualSourceKind.LegacyRemote to "R01TMQAAAAEAAAAkMDEyMzQ1NjctODlhYi00Y2RlLThmMDEtMjM0NTY3ODlhYmNkAAAAAP//////////AAAAAAD/////AA==",
            ManualSourceKind.ImportedRemote to "R01TMQAAAAEAAAAkMDEyMzQ1NjctODlhYi00Y2RlLThmMDEtMjM0NTY3ODlhYmNkAAAAAQAAABdzeW50aGV0aWMtY29uZmlndXJhdGlvbgAAADhodHRwczovL3N1Yi5nY2N3ZWJvcy5jb20vc3ViL2Evc3ludGhldGljLXN0b3JlLW9ubHkueWFtbAEAAAAA8qUjgAAAAAAAAAAAAAAAQGY3NDY1YjA1Y2RmYzEyMTllMTYxNGY2MjBlOWY1MWIwZDVkZWEzZjg2N2NkMGJjMjNjNTFiNzBiNzhmZGQwMDAA",
            ManualSourceKind.ImportedLocal to "R01TMQAAAAEAAAAkMDEyMzQ1NjctODlhYi00Y2RlLThmMDEtMjM0NTY3ODlhYmNkAAAAAgAAABdzeW50aGV0aWMtY29uZmlndXJhdGlvbv////8AAAAAAAAAAEBmNzQ2NWIwNWNkZmMxMjE5ZTE2MTRmNjIwZTlmNTFiMGQ1ZGVhM2Y4NjdjZDBiYzIzYzUxYjcwYjc4ZmRkMDAwAA==",
            ManualSourceKind.None to "R01TMQAAAAEAAAAkMDEyMzQ1NjctODlhYi00Y2RlLThmMDEtMjM0NTY3ODlhYmNkAAAAA///////////AAAAAAD/////AA=="
        )
        fixtures.forEach { (kind, fixture) ->
            val hasMaterial = kind in listOf(ManualSourceKind.ImportedRemote, ManualSourceKind.ImportedLocal)
            ManualSourceRecord(fixtureRevision, kind, if (hasMaterial) raw else null,
                if (kind == ManualSourceKind.ImportedRemote) url else null,
                if (kind == ManualSourceKind.ImportedRemote) until else null).use { record ->
                val expected = Base64.getDecoder().decode(fixture)
                val encoded = ManualSourceCodec.encode(record)
                try {
                    assertArrayEquals(expected, encoded)
                    ManualSourceCodec.decode(expected).use { assertTrue(record.sameEnvelope(it)) }
                } finally { expected.fill(0); encoded.fill(0) }
            }
        }
    }

    @Test fun externalRemoteOwnsExactUrlAndDistinguishesUnreportedFromKnownExpiry() {
        for (expiry in listOf(null, until, Instant.parse("2000-01-01T00:00:00Z"))) {
            for (status in listOf(null, LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) {
                ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl, expiry, status).use { record ->
                    val encoded = ManualSourceCodec.encode(record)
                    try {
                        assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
                        assertEquals(4, ByteBuffer.wrap(encoded).getInt(48))
                        assertEquals(if (expiry == null) 0 else 1, ByteBuffer.wrap(encoded).getInt(externalValidityOffset()))
                        ManualSourceCodec.decode(encoded).use { restored ->
                            assertTrue(record.sameEnvelope(restored))
                            assertEquals(externalUrl.value, restored.url!!.value)
                            assertEquals(expiry, restored.validUntil)
                            assertEquals(status, restored.observedStatus)
                        }
                    } finally { encoded.fill(0) }
                }
            }
        }
    }

    @Test fun externalRemoteRequiresUrlAndMaterialWithoutBorrowingLocalOrAccountSemantics() {
        val kind = externalKind
        rejected { ManualSourceRecord(kind = kind, bytes = raw) }
        rejected { ManualSourceRecord(kind = kind, url = externalUrl) }
        rejected { ManualSourceRecord(kind = kind, bytes = raw, url = externalUrl, clearPending = true) }
        for (status in LocalSubscriptionStatus.entries.filter { it !in listOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK) }) {
            rejected { ManualSourceRecord(kind = kind, bytes = raw, url = externalUrl, observedStatus = status) }
        }
        for (value in listOf("http://subscriptions.example.net/query", "https://subscriptions.example.net/?token=x#fragment",
            "https://user:pass@subscriptions.example.net/path", "https://subscriptions.example.net:444/path",
            "https://subscriptions.example.net/" + "a".repeat(8192))) {
            rejected { ManualSourceRecord(kind = kind, bytes = raw, url = Secret(value)) }
        }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = externalUrl, validUntil = until) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url) }
    }

    @Test fun codecRejectsCrossVersionKindsAndUnknownVersionTypeOrValidity() {
        ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                for (version in listOf(-1, 0, 1, 3, Int.MAX_VALUE)) rejectChangedInt(encoded, 4, version)
                for (kind in listOf(-1, 0, 1, 2, 3, 5, Int.MAX_VALUE)) rejectChangedInt(encoded, 48, kind)
                for (validity in listOf(-1, 1, 2, Int.MAX_VALUE)) rejectChangedInt(encoded, externalValidityOffset(), validity)
                val invalidPresence = encoded.copyOf().also { it[externalValidityOffset() + 4] = 2 }
                try { rejected { ManualSourceCodec.decode(invalidPresence) } } finally { invalidPresence.fill(0) }
            } finally { encoded.fill(0) }
        }
        ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl, until).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try { rejectChangedInt(encoded, externalValidityOffset(), 0) } finally { encoded.fill(0) }
        }
        ManualSourceRecord(fixtureRevision, ManualSourceKind.None).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                rejectChangedInt(encoded, 48, 4)
                rejectChangedInt(encoded, 4, 2)
            } finally { encoded.fill(0) }
        }
    }

    @Test fun v2TruncationTrailingDigestAndExpiryCorruptionFailWithoutMetadataInErrors() {
        ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl, until).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                for (length in 0 until encoded.size) {
                    val truncated = encoded.copyOf(length)
                    try { rejected { ManualSourceCodec.decode(truncated) } } finally { truncated.fill(0) }
                }
                rejected { ManualSourceCodec.decode(encoded + byteArrayOf(0)) }
                rejectChangedInt(encoded, externalValidityOffset() + 4 + 1 + 8, 1_000_000_000)
                val changedDigest = encoded.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }
                try {
                    try { ManualSourceCodec.decode(changedDigest); fail("Corrupt external envelope accepted") }
                    catch (failure: IllegalArgumentException) { assertNull(failure.message); assertNull(failure.cause) }
                } finally { changedDigest.fill(0) }
            } finally { encoded.fill(0) }
        }
    }

    @Test fun externalExpiryAndTerminalObservationRemainPartOfReadbackIdentity() {
        ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl).use { unreported ->
            val alternatives = listOf(
                ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl, until),
                ManualSourceRecord(fixtureRevision, externalKind, raw, externalUrl, observedStatus = LocalSubscriptionStatus.INVALID_LINK),
                ManualSourceRecord(fixtureRevision, externalKind, raw, Secret(externalUrl.value + "&other=1")),
                ManualSourceRecord(fixtureRevision, externalKind, raw + 1, externalUrl),
                ManualSourceRecord(kind = externalKind, bytes = raw, url = externalUrl)
            )
            alternatives.forEach { alternative -> alternative.use { assertFalse(unreported.sameEnvelope(it)) } }
            val store = MemoryManualSourceStore()
            store.save(unreported)
            store.read()!!.use { assertTrue(unreported.sameEnvelope(it)) }
            assertEquals("ManualSourceRecord([REDACTED])", unreported.toString())
        }
    }

    private fun externalValidityOffset() = 8 + 4 + 36 + 4 + 4 + raw.size + 4 + externalUrl.value.toByteArray(Charsets.UTF_8).size

    private fun rejectChangedInt(encoded: ByteArray, offset: Int, value: Int) {
        val changed = encoded.copyOf()
        try { ByteBuffer.wrap(changed).putInt(offset, value); rejected { ManualSourceCodec.decode(changed) } }
        finally { changed.fill(0) }
    }

    @Test fun memoryOwnsCopiesAndTombstoneHasNoImportedMaterial() {
        val input = raw.copyOf()
        val record = ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = input)
        val store = MemoryManualSourceStore()
        assertEquals(ManualSourceKind.LegacyRemote, store.read()!!.use { it.kind })
        store.save(record)
        input.fill(0); record.close()
        store.read()!!.use {
            val bytes = it.copyBytes()!!
            try { assertArrayEquals(raw, bytes); bytes.fill(0) } finally { bytes.fill(0) }
        }
        store.read()!!.use {
            val bytes = it.copyBytes()!!
            try { assertArrayEquals(raw, bytes) } finally { bytes.fill(0) }
        }
        ManualSourceRecord(kind = ManualSourceKind.None).use { store.save(it) }
        store.read()!!.use {
            assertEquals(ManualSourceKind.None, it.kind)
            assertNull(it.copyBytes()); assertNull(it.contentIdentity); assertNull(it.url)
            assertNull(it.validUntil); assertNull(it.observedStatus)
        }
    }

    @Test fun localCannotBorrowRemoteExpiryCapabilityOrStatus() {
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw, validUntil = until) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw, url = url) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw, observedStatus = LocalSubscriptionStatus.EXPIRED) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal) }
        for (kind in listOf(ManualSourceKind.LegacyRemote, ManualSourceKind.None)) {
            rejected { ManualSourceRecord(kind = kind, bytes = raw) }
            rejected { ManualSourceRecord(kind = kind, validUntil = until) }
            rejected { ManualSourceRecord(kind = kind, url = url) }
            rejected { ManualSourceRecord(kind = kind, observedStatus = LocalSubscriptionStatus.EXPIRED) }
        }
    }

    @Test fun remoteRequiresOwnMaterialCapabilityExpiryAndOnlyTerminalObservation() {
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, validUntil = until) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, url = url, validUntil = until) }
        for (status in LocalSubscriptionStatus.entries.filter { it !in listOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK) }) {
            rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until, observedStatus = status) }
        }
        for (status in listOf(null, LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) {
            ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until, observedStatus = status).use {
                assertEquals(status, it.observedStatus)
            }
        }
    }

    @Test fun shapeLimitsCanonicalRevisionAndHttpsCapabilityShapeAreEnforced() {
        for (revision in listOf("", "1-1-1-1-1", "ABCDEF01-2345-4678-9012-abcdefabcdef", "not-a-revision")) {
            rejected { ManualSourceRecord(revision = revision, kind = ManualSourceKind.None) }
        }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = byteArrayOf()) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = ByteArray(SafeProxyConfig.MAX_BYTES + 1)) }
        ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = ByteArray(SafeProxyConfig.MAX_BYTES)).close()
        for (value in listOf("https://sub.gccwebos.com/not-a-capability", "http://sub.gccwebos.com/sub/a/synthetic", "https://sub.gccwebos.com/sub/a/synthetic?q=1")) {
            rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = Secret(value), validUntil = until) }
        }
    }

    @Test fun closedMaterialCannotBeReadCopiedOrEncodedAndStringsAreRedacted() {
        val record = ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until)
        assertEquals("ManualSourceRecord([REDACTED])", record.toString())
        record.close(); record.close()
        rejectedState { record.copyBytes() }
        rejectedState { MemoryManualSourceStore().save(record) }
        rejectedState { ManualSourceCodec.encode(record) }
    }

    @Test fun exactWholeEnvelopeRoundTripsAllKinds() {
        val values = listOf(
            ManualSourceRecord(kind = ManualSourceKind.LegacyRemote),
            ManualSourceRecord(kind = ManualSourceKind.None),
            ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true),
            ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw),
            ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until, observedStatus = LocalSubscriptionStatus.INVALID_LINK)
        )
        values.forEach { value ->
            value.use {
                val encoded = ManualSourceCodec.encode(it)
                try { ManualSourceCodec.decode(encoded).use { decoded -> assertTrue(it.sameEnvelope(decoded)) } }
                finally { encoded.fill(0) }
            }
        }
    }

    @Test fun unknownVersionTruncationTrailingDataAndDigestMismatchFailClosed() {
        ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw).use { record ->
            val encoded = ManualSourceCodec.encode(record)
            try {
                val unknownVersion = encoded.copyOf().also { it[7] = 99 }
                rejected { ManualSourceCodec.decode(unknownVersion) }
                for (length in listOf(0, 3, 7, encoded.size - 1)) rejected { ManualSourceCodec.decode(encoded.copyOf(length)) }
                rejected { ManualSourceCodec.decode(encoded + byteArrayOf(0)) }
                val changedDigest = encoded.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }
                rejected { ManualSourceCodec.decode(changedDigest) }
                val unknownClearFlag = encoded.copyOf().also { it[it.lastIndex] = 2 }
                rejected { ManualSourceCodec.decode(unknownClearFlag) }
            } finally { encoded.fill(0) }
        }
    }

    @Test fun sourceRevisionAndEveryMetadataFieldAffectExactEnvelopeIdentity() {
        val revision = UUID.randomUUID().toString()
        ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw, url, until).use { expected ->
            val alternatives = listOf(
                ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until),
                ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw + 1, url, until),
                ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw, Secret("https://sub.gccwebos.com/sub/a/other-synthetic"), until),
                ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw, url, until.plusSeconds(1)),
                ManualSourceRecord(revision, ManualSourceKind.ImportedRemote, raw, url, until, LocalSubscriptionStatus.EXPIRED),
                ManualSourceRecord(revision, ManualSourceKind.ImportedLocal, raw)
            )
            alternatives.forEach { alternative -> alternative.use { assertFalse(expected.sameEnvelope(it)) } }
        }
    }

    @Test fun pendingClearIsAllowedOnlyOnAnEmptyTombstoneAndChangesEnvelopeIdentity() {
        rejected { ManualSourceRecord(kind = ManualSourceKind.LegacyRemote, clearPending = true) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = raw, clearPending = true) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.ImportedRemote, bytes = raw, url = url, validUntil = until, clearPending = true) }
        rejected { ManualSourceRecord(kind = ManualSourceKind.None, bytes = raw, clearPending = true) }
        ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { pending ->
            ManualSourceRecord(revision = pending.revision, kind = ManualSourceKind.None).use { complete ->
                assertFalse(pending.sameEnvelope(complete))
                assertTrue(pending.clearPending); assertFalse(complete.clearPending)
                assertNull(pending.copyBytes()); assertNull(pending.url); assertNull(pending.validUntil)
            }
        }
    }

    @Test fun memoryAndCodecPreservePendingClearUntilExplicitCompleteEnvelope() {
        val store = MemoryManualSourceStore()
        ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { pending ->
            store.save(pending)
            val encoded = ManualSourceCodec.encode(pending)
            try {
                ManualSourceCodec.decode(encoded).use { restored ->
                    assertTrue(restored.clearPending); assertTrue(pending.sameEnvelope(restored))
                    store.read()!!.use { assertTrue(restored.sameEnvelope(it)) }
                }
                // A pending clear bit on a LegacyRemote envelope is an unknown shape, not fallback.
                val changedKind = encoded.copyOf().also { it[51] = 0 }
                rejected { ManualSourceCodec.decode(changedKind) }
            } finally { encoded.fill(0) }
        }
        ManualSourceRecord(kind = ManualSourceKind.None).use { store.save(it) }
        store.read()!!.use { assertFalse(it.clearPending); assertEquals(ManualSourceKind.None, it.kind) }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid source envelope accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun rejectedState(block: () -> Unit) {
        try { block(); fail("Closed source material accepted") } catch (_: IllegalStateException) { }
    }
}
