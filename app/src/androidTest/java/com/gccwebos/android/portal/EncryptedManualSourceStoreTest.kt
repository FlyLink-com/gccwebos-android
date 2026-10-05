package com.gccwebos.android.portal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Only unique owned synthetic directories are inspected; never the user's actual stores. */
class EncryptedManualSourceStoreTest {
    private val until = Instant.parse("2099-01-01T00:00:00Z")
    private val url = Secret("https://sub.invalid/sub/a/synthetic-encrypted-manual")
    private val externalUrl = Secret("https://subscriptions.example.net/client?token=synthetic-encrypted-external%2Fonly")

    @Test fun legacyLibraryReadsDoNotMigrateAndExplicitV3PreservesOriginalProfileBytes() = fixture { context ->
        val key = syntheticKey()
        val retained = "synthetic-account-profile-untouched".toByteArray()
        val profile = File(context.noBackupFilesDir, "portal-profile.bin")
        profile.writeBytes(retained)
        external(null).use { old ->
            testStore(context, key).save(old)
            val baseline = sourceFile(context).readBytes()
            try {
                repeat(2) { testStore(context, key).readLibrary()!!.use { normalized ->
                    assertEquals(old.revision, normalized.entries.single().id)
                    normalized.activeSource().use { assertTrue(old.sameEnvelope(it)) }
                    assertArrayEquals(baseline, sourceFile(context).readBytes())
                } }
                testStore(context, key).readLibrary()!!.use { testStore(context, key).saveLibrary(it) }
                assertEnvelopeVersion(context, key, 3)
                testStore(context, key).read()!!.use { assertTrue(old.sameEnvelope(it)) }
                assertArrayEquals(retained, profile.readBytes())
            } finally { baseline.fill(0) }
        }
        retained.fill(0)
    }

    @Test fun v3EncryptedRoundTripAndCompatibilitySaveRetainInactiveEntries() = fixture { context ->
        val key = syntheticKey()
        syntheticLibrary().use { initial ->
            testStore(context, key).saveLibrary(initial)
            assertEnvelopeVersion(context, key, 3)
            val disk = sourceFile(context).readBytes()
            try {
                val searchable = disk.toString(Charsets.ISO_8859_1)
                for (secret in listOf("synthetic-library-name", "synthetic-raw-material", initial.revision, externalUrl.value))
                    assertFalse(searchable.contains(secret))
            } finally { disk.fill(0) }
            testStore(context, key).readLibrary()!!.use { assertTrue(initial.sameEnvelope(it)) }
            val inactiveId = initial.entries.last().id
            initial.activeSource().use { source ->
                val raw = source.copyBytes()!!
                try { ManualSourceRecord(source.revision, source.kind, raw, source.url, until).use {
                    testStore(context, key).save(it)
                } } finally { raw.fill(0) }
            }
            testStore(context, key).readLibrary()!!.use { updated ->
                assertEquals(2, updated.entries.size); assertEquals(inactiveId, updated.entries.last().id)
                assertEquals(initial.entries.first().name, updated.entries.first().name)
                updated.activeSource().use { assertEquals(until, it.validUntil) }
            }
            ManualSourceRecord(kind = ManualSourceKind.None).use { testStore(context, key).save(it) }
            testStore(context, key).readLibrary()!!.use { deselected ->
                assertEquals(ConfigurationSelector.None, deselected.selector); assertEquals(2, deselected.entries.size)
            }
            assertEnvelopeVersion(context, key, 3)
        }
    }

    @Test fun v3AtomicFaultsNeverMixSelectorAndEntriesAcrossMigrationOrReplacement() = fixture { context ->
        val key = syntheticKey()
        external(null).use { legacy ->
            syntheticLibrary().use { replacement ->
                for (phase in ManualSourceWritePhase.entries) {
                    // An explicit synthetic legacy ciphertext fixture; no real app data is touched.
                    sourceFile(context).writeBytes(encrypt(ManualSourceCodec.encode(legacy), key))
                    val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-v3-migration-fault") }
                    storageFailure { failing.saveLibrary(replacement) }
                    val committed = phase !in listOf(ManualSourceWritePhase.BeforeSync, ManualSourceWritePhase.BeforeFinish)
                    testStore(context, key).readLibrary()!!.use { restored ->
                        if (committed) assertTrue(replacement.sameEnvelope(restored))
                        else ConfigurationLibrary.fromLegacy(legacy).use { assertTrue(it.sameEnvelope(restored)) }
                    }
                    assertEnvelopeVersion(context, key, if (committed) 3 else 2)
                }
                syntheticLibrary().use { old ->
                    for (phase in ManualSourceWritePhase.entries) {
                        testStore(context, key).saveLibrary(old)
                        val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-v3-replacement-fault") }
                        storageFailure { failing.saveLibrary(replacement) }
                        val expected = if (phase in listOf(ManualSourceWritePhase.BeforeSync, ManualSourceWritePhase.BeforeFinish)) old else replacement
                        testStore(context, key).readLibrary()!!.use { assertTrue(expected.sameEnvelope(it)) }
                    }
                }
            }
        }
    }

    @Test fun v3ReadbackRejectsChangedInactiveNameEvenWhenActiveSourceIsIdentical() = fixture { context ->
        val key = syntheticKey()
        syntheticLibrary().use { wanted ->
            val inactive = wanted.entries.last()
            inactive.copySource().use { source ->
                ConfigurationEntry(inactive.id, "synthetic-renamed-inactive", source).use { replacement ->
                    ConfigurationLibrary(wanted.revision, listOf(wanted.entries.first(), replacement), wanted.selector,
                        wanted.selectedEntryId, wanted.selectorRevision).use { changed ->
                        wanted.activeSource().use { expected -> changed.activeSource().use { assertTrue(expected.sameEnvelope(it)) } }
                        val substituted = encrypt(ConfigurationLibraryCodec.encode(changed), key)
                        try {
                            val failing = testStore(context, key) { phase ->
                                if (phase == ManualSourceWritePhase.BeforeReadback) sourceFile(context).writeBytes(substituted)
                            }
                            storageFailure { failing.saveLibrary(wanted) }
                            testStore(context, key).readLibrary()!!.use { assertTrue(changed.sameEnvelope(it)) }
                        } finally { substituted.fill(0) }
                    }
                }
            }
        }
    }

    @Test fun v3EmptyPendingAndCompletedClearStayAtomicAndNeverRestoreAnEntry() = fixture { context ->
        val key = syntheticKey()
        ConfigurationLibrary(selector = ConfigurationSelector.None, clearPending = true).use { pending ->
            ConfigurationLibrary(selector = ConfigurationSelector.None).use { completed ->
                for (phase in ManualSourceWritePhase.entries) {
                    testStore(context, key).saveLibrary(pending)
                    val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-v3-clear-fault") }
                    storageFailure { failing.saveLibrary(completed) }
                    val before = phase in listOf(ManualSourceWritePhase.BeforeSync, ManualSourceWritePhase.BeforeFinish)
                    testStore(context, key).readLibrary()!!.use {
                        assertTrue((if (before) pending else completed).sameEnvelope(it))
                        assertTrue(it.entries.isEmpty()); assertEquals(before, it.clearPending)
                        it.activeSource().use { source -> assertEquals(ManualSourceKind.None, source.kind); assertNull(source.copyBytes()) }
                    }
                    assertEnvelopeVersion(context, key, 3)
                }
            }
        }
    }

    private fun syntheticLibrary(): ConfigurationLibrary {
        external(null).use { active ->
            remote().use { inactive ->
                ConfigurationEntry(active.revision, "synthetic-library-name", active).use { one ->
                    ConfigurationEntry(inactive.revision, "synthetic-library-name", inactive).use { two ->
                        return ConfigurationLibrary(entries = listOf(one, two), selector = ConfigurationSelector.Entry,
                            selectedEntryId = one.id, selectorRevision = one.sourceRevision)
                    }
                }
            }
        }
    }

    @Test fun externalV2KnownAndUnreportedExpiryRestoreAsWholeEncryptedEnvelopes() = fixture { context ->
        val key = syntheticKey()
        for (expiry in listOf(null, until)) {
            for (status in listOf(null, LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) {
                external(expiry, status = status).use { record ->
                    testStore(context, key).save(record)
                    assertEnvelopeVersion(context, key, 2)
                    val disk = sourceFile(context).readBytes()
                    try {
                        val searchable = disk.toString(Charsets.ISO_8859_1)
                        assertFalse(searchable.contains("synthetic-encrypted-external"))
                        assertFalse(searchable.contains("synthetic-raw-material"))
                        assertFalse(searchable.contains(record.revision))
                    } finally { disk.fill(0) }
                    testStore(context, key).read()!!.use { restored ->
                        assertTrue(record.sameEnvelope(restored))
                        assertEquals(ManualSourceKind.ExternalRemote, restored.kind)
                        assertEquals(expiry, restored.validUntil)
                        assertEquals(status, restored.observedStatus)
                    }
                }
            }
        }
    }

    @Test fun readingExistingV1DoesNotRewriteItBeforeExplicitExternalAdoption() = fixture { context ->
        val key = syntheticKey()
        remote().use { old ->
            testStore(context, key).save(old)
            val baseline = sourceFile(context).readBytes()
            try {
                assertEnvelopeVersion(context, key, 1)
                repeat(2) {
                    testStore(context, key).read()!!.use { assertTrue(old.sameEnvelope(it)) }
                    assertArrayEquals(baseline, sourceFile(context).readBytes())
                }
                external(null).use { replacement ->
                    testStore(context, key).save(replacement)
                    assertEnvelopeVersion(context, key, 2)
                    testStore(context, key).read()!!.use { assertTrue(replacement.sameEnvelope(it)) }
                }
            } finally { baseline.fill(0) }
        }
    }

    @Test fun externalV2AtomicFaultsPreserveOldOrNewWholeEnvelopeIncludingValidity() = fixture { context ->
        val key = syntheticKey()
        val old = ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = "synthetic-v1-before-external".toByteArray())
        val unreported = external(null)
        val known = external(until, revision = unreported.revision)
        try {
            for ((before, replacement) in listOf(old to unreported, old to known, unreported to known)) {
                for (phase in ManualSourceWritePhase.entries) {
                    testStore(context, key).save(before)
                    val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-external-fault") }
                    storageFailure { failing.save(replacement) }
                    val committed = phase != ManualSourceWritePhase.BeforeSync && phase != ManualSourceWritePhase.BeforeFinish
                    val expected = if (committed) replacement else before
                    testStore(context, key).read()!!.use { restored ->
                        assertTrue(expected.sameEnvelope(restored))
                        assertFalse((if (committed) before else replacement).sameEnvelope(restored))
                    }
                    assertEnvelopeVersion(context, key, if (expected.kind == ManualSourceKind.ExternalRemote) 2 else 1)
                }
            }
        } finally { old.close(); unreported.close(); known.close() }
    }

    @Test fun externalV2ReadbackRejectsOnlyExpiryChangedWithSameIdentityAndMaterial() = fixture { context ->
        val key = syntheticKey()
        external(until).use { wanted ->
            external(null, revision = wanted.revision).use { substituteRecord ->
                assertEquals(wanted.contentIdentity, substituteRecord.contentIdentity)
                val substitute = encrypt(ManualSourceCodec.encode(substituteRecord), key)
                try {
                    val store = testStore(context, key) { phase ->
                        if (phase == ManualSourceWritePhase.BeforeReadback) sourceFile(context).writeBytes(substitute)
                    }
                    storageFailure { store.save(wanted) }
                    testStore(context, key).read()!!.use { assertTrue(substituteRecord.sameEnvelope(it)) }
                } finally { substitute.fill(0) }
            }
        }
    }

    @Test fun explicitExternalRemovalOrAccountChoiceCreatesV3WithoutImplicitAccountAccess() {
        for (target in listOf(ManualSourceKind.None, ManualSourceKind.LegacyRemote)) {
            // Both branches start with their own real v2 ciphertext, not the prior branch's v3.
            fixture { context ->
                val key = syntheticKey()
                var deliveryAccess = 0
                var profileAccess = 0
                var downloads = 0
                val delivery = object : DeliveryStore {
                    override fun read(): DeliveryRecord? { deliveryAccess++; error("No account delivery read") }
                    override fun save(record: DeliveryRecord) { deliveryAccess++; error("No account delivery write") }
                    override fun clear() { deliveryAccess++; error("No account delivery clear") }
                }
                val profiles = object : LocalProfileStore {
                    override fun read(): CachedSubscription? { profileAccess++; error("No account profile read") }
                    override fun save(value: CachedSubscription) { profileAccess++; error("No account profile write") }
                    override fun clear() { profileAccess++; error("No account profile clear") }
                }
                val noDownload = SubscriptionSource { downloads++; error("Selector conversion must not download") }
                external(null).use { original ->
                    testStore(context, key).save(original)
                    assertEnvelopeVersion(context, key, 2)
                    val manager = LocalSubscriptionManager(delivery, profiles, noDownload,
                        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")),
                        { Instant.parse("2026-10-05T00:00:00Z") }, LocalSubscriptionAuthority(),
                        manualSources = testStore(context, key), externalSource = noDownload)
                    runBlocking {
                        if (target == ManualSourceKind.None) manager.removeManualSource { it() }
                        else manager.useAccountSource { it() }
                    }
                    assertEnvelopeVersion(context, key, 3)
                    testStore(context, key).readLibrary()!!.use { library ->
                        assertNull(library.selectedEntryId)
                        assertFalse(library.clearPending)
                        if (target == ManualSourceKind.None) {
                            assertEquals(ConfigurationSelector.None, library.selector)
                            assertTrue("Explicit removal must delete the entry, not leave an inactive ghost", library.entries.isEmpty())
                        } else {
                            assertEquals(ConfigurationSelector.Account, library.selector)
                            val retained = library.entries.single()
                            assertEquals(original.revision, retained.id)
                            retained.copySource().use { assertTrue(original.sameEnvelope(it)) }
                        }
                        library.activeSource().use { converted ->
                            assertEquals(target, converted.kind)
                            assertEquals(library.selectorRevision, converted.revision)
                            assertNotEquals(original.revision, converted.revision)
                            assertFalse(converted.clearPending)
                            assertNull(converted.copyBytes()); assertNull(converted.url)
                            assertNull(converted.validUntil); assertNull(converted.contentIdentity)
                            testStore(context, key).read()!!.use { assertTrue(converted.sameEnvelope(it)) }
                        }
                    }
                    // Counters catch an inappropriate read even if a UI-summary helper swallowed its failure.
                    assertEquals(0, deliveryAccess); assertEquals(0, profileAccess); assertEquals(0, downloads)
                }
            }
        }
    }

    @Test fun encryptedWholeEnvelopeRestoresAndTombstoneErasesSelectedMaterial() = fixture { context ->
        val store = EncryptedManualSourceStore(context)
        assertNull(store.read())
        remote().use { source ->
            store.save(source)
            val disk = sourceFile(context).readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains("synthetic-encrypted-manual"))
            assertFalse(disk.contains("synthetic-raw-material"))
            assertFalse(disk.contains(source.revision))
            EncryptedManualSourceStore(context).read()!!.use { assertTrue(source.sameEnvelope(it)) }
        }
        ManualSourceRecord(kind = ManualSourceKind.None).use { tombstone ->
            store.save(tombstone)
            EncryptedManualSourceStore(context).read()!!.use {
                assertTrue(tombstone.sameEnvelope(it)); assertNull(it.copyBytes())
                assertNull(it.url); assertNull(it.validUntil); assertNull(it.contentIdentity)
            }
        }
    }

    @Test fun sidecarInitializationImportsAndClearNeverRewriteOriginalV1() = fixture { context ->
        val legacy = EncryptedLocalProfileStore(context)
        val oldUrl = Secret("https://sub.invalid/sub/a/synthetic-v1-retained")
        val original = CachedSubscription(sessionBinding(oldUrl), "synthetic-original-v1".toByteArray(), until, oldUrl)
        original.use { legacy.save(it) }
        val oldFile = File(context.noBackupFilesDir, "portal-profile.bin")
        val baseline = oldFile.readBytes()
        try {
            val store = EncryptedManualSourceStore(context)
            assertNull(store.read())
            val states = listOf(
                ManualSourceRecord(kind = ManualSourceKind.LegacyRemote),
                remote(),
                external(null),
                external(until),
                ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = "synthetic-local-material".toByteArray()),
                ManualSourceRecord(kind = ManualSourceKind.None)
            )
            states.forEach { state ->
                state.use { store.save(it) }
                assertArrayEquals(baseline, oldFile.readBytes())
                legacy.read()!!.use {
                    assertEquals(oldUrl.value, it.url!!.value)
                    val bytes = it.copyBytes()
                    try { assertArrayEquals("synthetic-original-v1".toByteArray(), bytes) } finally { bytes.fill(0) }
                }
            }
        } finally { baseline.fill(0) }
    }

    @Test fun interruptedInitialWriteCannotAppearAsSelectedImportedSource() = fixture { context ->
        val key = syntheticKey()
        for (phase in listOf(ManualSourceWritePhase.BeforeSync, ManualSourceWritePhase.BeforeFinish)) {
            val store = testStore(context, key) { if (it == phase) throw IOException("synthetic-fault") }
            remote().use { record -> storageFailure { store.save(record) } }
            assertNull(testStore(context, key).read())
        }
    }

    @Test fun interruptedReplacementObservesOnlyOldOrNewWholeEnvelope() = fixture { context ->
        val key = syntheticKey()
        ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = "synthetic-old".toByteArray()).use { old ->
            remote().use { replacement ->
                for (phase in ManualSourceWritePhase.entries) {
                    testStore(context, key).save(old)
                    val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-fault") }
                    storageFailure { failing.save(replacement) }
                    testStore(context, key).read()!!.use { restored ->
                        val expected = if (phase == ManualSourceWritePhase.BeforeSync || phase == ManualSourceWritePhase.BeforeFinish) old else replacement
                        assertTrue(expected.sameEnvelope(restored))
                        assertFalse(if (expected === old) replacement.sameEnvelope(restored) else old.sameEnvelope(restored))
                    }
                }
            }
        }
    }

    @Test fun completeButWrongEnvelopeDuringReadbackIsRejected() = fixture { context ->
        val key = syntheticKey()
        ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = "synthetic-other".toByteArray()).use { other ->
            val substitute = encrypt(ManualSourceCodec.encode(other), key)
            try {
                val store = testStore(context, key) { phase ->
                    if (phase == ManualSourceWritePhase.BeforeReadback) sourceFile(context).writeBytes(substitute)
                }
                remote().use { wanted -> storageFailure { store.save(wanted) } }
                testStore(context, key).read()!!.use { assertTrue(other.sameEnvelope(it)) }
            } finally { substitute.fill(0) }
        }
    }

    @Test fun unreadableCorruptUnknownAndWrongAadNeverBecomeAbsentOrFallback() = fixture { context ->
        val key = syntheticKey()
        val store = testStore(context, key)
        remote().use { record ->
            store.save(record)
            val validCiphertext = sourceFile(context).readBytes()
            val encoded = ManualSourceCodec.encode(record)
            try {
                val tampered = validCiphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                sourceFile(context).writeBytes(tampered)
                storageFailure { store.read() }
                sourceFile(context).writeBytes(validCiphertext.copyOf(10))
                storageFailure { store.read() }
                val unknownVersion = encoded.copyOf().also { it[7] = 99 }
                sourceFile(context).writeBytes(encrypt(unknownVersion, key))
                storageFailure { store.read() }
                sourceFile(context).writeBytes(encrypt(encoded.copyOf(), key, "gccwebos.profile.v1"))
                storageFailure { store.read() }
                sourceFile(context).writeBytes(validCiphertext)
                val absentKey = EncryptedManualSourceStore(context.noBackupFilesDir, { create ->
                    assertFalse("Read must never create a replacement key", create)
                    throw IllegalStateException("synthetic-key-loss")
                })
                storageFailure { absentKey.read() }
                assertTrue(sourceFile(context).isFile)
            } finally { validCiphertext.fill(0); encoded.fill(0) }
        }
    }

    @Test fun incompleteInitialAtomicArtifactIsStorageFailureWhileOrphanNextToBaseRecoversOldWhole() = fixture { context ->
        val key = syntheticKey()
        val store = testStore(context, key)
        val pending = File(context.noBackupFilesDir, "manual-source.bin.new")
        pending.writeBytes(byteArrayOf(1, 2, 3))
        storageFailure { store.read() }
        assertTrue(pending.delete())
        remote().use { old ->
            store.save(old)
            pending.writeBytes(byteArrayOf(3, 2, 1))
            store.read()!!.use { assertTrue(old.sameEnvelope(it)) }
            assertFalse(pending.exists())
        }
    }

    @Test fun maximumLocalPayloadRoundTripsWithoutUnboundedRead() = fixture { context ->
        val bytes = ByteArray(com.gccwebos.android.vpn.SafeProxyConfig.MAX_BYTES) { (it % 251).toByte() }
        try {
            ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = bytes).use { record ->
                val store = EncryptedManualSourceStore(context)
                store.save(record)
                store.read()!!.use { assertTrue(record.sameEnvelope(it)) }
                sourceFile(context).writeBytes(ByteArray(ManualSourceCodec.MAX_ENCODED_BYTES + 29))
                storageFailure { store.read() }
            }
        } finally { bytes.fill(0) }
    }

    @Test fun pendingClearSurvivesStoreRecreationWithoutReadingOrChangingLegacyV1() = fixture { context ->
        val v1 = File(context.noBackupFilesDir, "portal-profile.bin")
        val retained = "synthetic-v1-retained-during-incomplete-clear".toByteArray()
        try {
            v1.writeBytes(retained)
            ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { pending ->
                EncryptedManualSourceStore(context).save(pending)
                EncryptedManualSourceStore(context).read()!!.use {
                    assertTrue(it.clearPending); assertTrue(pending.sameEnvelope(it))
                    assertEquals(ManualSourceKind.None, it.kind); assertNull(it.copyBytes())
                }
                assertArrayEquals(retained, v1.readBytes())
            }
        } finally { retained.fill(0) }
    }

    @Test fun interruptedClearCompletionPreservesEitherPendingOrCompleteWholeTombstone() = fixture { context ->
        val key = syntheticKey()
        ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { pending ->
            ManualSourceRecord(kind = ManualSourceKind.None).use { complete ->
                for (phase in ManualSourceWritePhase.entries) {
                    testStore(context, key).save(pending)
                    val failing = testStore(context, key) { if (it == phase) throw IOException("synthetic-clear-fault") }
                    storageFailure { failing.save(complete) }
                    testStore(context, key).read()!!.use { reopened ->
                        val beforeCommit = phase == ManualSourceWritePhase.BeforeSync || phase == ManualSourceWritePhase.BeforeFinish
                        assertTrue((if (beforeCommit) pending else complete).sameEnvelope(reopened))
                        assertEquals(beforeCommit, reopened.clearPending)
                        assertNull(reopened.copyBytes()); assertNull(reopened.url)
                    }
                }
            }
        }
    }

    private fun remote() = ManualSourceRecord(kind = ManualSourceKind.ImportedRemote,
        bytes = "synthetic-raw-material".toByteArray(), url = url, validUntil = until,
        observedStatus = LocalSubscriptionStatus.EXPIRED)

    private fun external(expiry: Instant?, revision: String = UUID.randomUUID().toString(),
        status: LocalSubscriptionStatus? = null) = ManualSourceRecord(revision, ManualSourceKind.ExternalRemote,
        bytes = "synthetic-raw-material".toByteArray(), url = externalUrl, validUntil = expiry, observedStatus = status)

    private fun assertEnvelopeVersion(context: Context, key: SecretKey, expected: Int) {
        val encrypted = sourceFile(context).readBytes()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(EncryptedManualSourceStore.AAD.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            try { assertEquals(expected, ByteBuffer.wrap(plain).getInt(4)) } finally { plain.fill(0) }
        } finally { encrypted.fill(0) }
    }

    private fun syntheticKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun testStore(context: Context, key: SecretKey, observer: ((ManualSourceWritePhase) -> Unit)? = null) =
        EncryptedManualSourceStore(context.noBackupFilesDir, { key }, observer)

    private fun sourceFile(context: Context) = File(context.noBackupFilesDir, EncryptedManualSourceStore.FILE_NAME)

    private fun encrypt(plain: ByteArray, key: SecretKey, aad: String = EncryptedManualSourceStore.AAD): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
            return cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    private fun storageFailure(action: () -> Unit) {
        try { action(); fail("Invalid or interrupted store operation was accepted") }
        catch (failure: PortalFailure) {
            assertEquals(PortalFailure.Reason.STORAGE, failure.reason)
            assertNull(failure.cause)
            assertEquals("STORAGE", failure.message)
        }
    }

    private fun fixture(action: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.noBackupFilesDir, "manual-store-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try { action(context) }
        finally {
            val allowed = setOf("manual-source.bin", "manual-source.bin.new", "manual-source.bin.bak",
                "portal-profile.bin", "portal-profile.bin.new", "portal-profile.bin.bak")
            directory.listFiles()?.forEach { entry ->
                check(entry.isFile && entry.name in allowed)
                check(entry.delete())
            }
            check(directory.delete())
        }
    }
}
