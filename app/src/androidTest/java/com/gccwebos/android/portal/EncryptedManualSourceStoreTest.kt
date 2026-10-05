package com.gccwebos.android.portal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

/** Only unique owned synthetic directories are inspected; never the user's actual stores. */
class EncryptedManualSourceStoreTest {
    private val until = Instant.parse("2099-01-01T00:00:00Z")
    private val url = Secret("https://sub.invalid/sub/a/synthetic-encrypted-manual")

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
