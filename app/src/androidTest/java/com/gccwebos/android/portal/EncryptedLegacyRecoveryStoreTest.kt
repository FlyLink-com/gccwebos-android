package com.gccwebos.android.portal

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real AndroidKeyStore/AtomicFile, exclusively on a fresh owned fixture installation. */
class EncryptedLegacyRecoveryStoreTest {
    private lateinit var context: Context
    private lateinit var store: EncryptedDeliveryStore
    private var fixtureOwned = false
    private val receiptFile get() = File(context.noBackupFilesDir, "portal-delivery.bin")
    private val journalFile get() = File(context.noBackupFilesDir, "portal-legacy-recovery.bin")

    @Before fun requireFreshOwnedFixture() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        requireFixturePackage(target) // No filesystem or keystore access before the package guard.
        context = target
        for (name in listOf("portal-session.bin", "portal-profile.bin", "portal-delivery.bin", "portal-legacy-recovery.bin")) {
            for (suffix in listOf("", ".bak", ".new")) {
                assertFalse("Requires a fresh fixture with no private stores", File(context.noBackupFilesDir, name + suffix).exists())
            }
        }
        store = EncryptedDeliveryStore(context)
        fixtureOwned = true
        store.clear()
    }

    @After fun cleanupOwnedFixture() {
        // JUnit also calls @After when @Before fails. Never clear another package's data.
        requireFixturePackage(InstrumentationRegistry.getInstrumentation().targetContext)
        if (!fixtureOwned) return
        store.clear()
        assertAbsent(receiptFile)
        assertAbsent(journalFile)
    }

    @Test fun allFourPhasesRoundTripThroughNewEncryptedStoreInstances() {
        val original = originalReceipt()
        store.save(original)
        for (phase in LegacyRecoveryPhase.entries) {
            val expected = journal(phase, original)
            store.saveLegacyRecovery(expected)
            assertJournalEquals(expected, EncryptedDeliveryStore(context).readLegacyRecovery())
            assertTrue("Main receipt must remain unchanged", sameDeliveryRecord(original, EncryptedDeliveryStore(context).read()))
            val encrypted = journalFile.readBytes()
            try {
                assertFalse("Journal must not expose synthetic plaintext", encrypted.toString(Charsets.ISO_8859_1).contains(SYNTHETIC_MARKER))
            } finally { encrypted.fill(0) }
        }
    }

    @Test fun clearingJournalRemovesBaseBackupAndNewWithoutRemovingMainReceipt() {
        val original = originalReceipt()
        store.save(original)
        store.saveLegacyRecovery(journal(LegacyRecoveryPhase.PROFILE_COMMITTING, original))
        val mainBefore = receiptFile.readBytes()
        val encrypted = journalFile.readBytes()
        try {
            File(journalFile.path + ".bak").writeBytes(encrypted)
            File(journalFile.path + ".new").writeBytes(encrypted)
            store.clearLegacyRecovery()
            assertAbsent(journalFile)
            assertNull(EncryptedDeliveryStore(context).readLegacyRecovery())
            assertMainReceiptPreserved(original, mainBefore)
        } finally { encrypted.fill(0); mainBefore.fill(0) }
    }

    @Test fun corruptedAuthenticationTagRejectsJournalAndPreservesMainReceipt() {
        val original = originalReceipt()
        store.save(original)
        store.saveLegacyRecovery(journal(LegacyRecoveryPhase.REVEALED, original))
        val mainBefore = receiptFile.readBytes()
        val encrypted = journalFile.readBytes()
        try {
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            journalFile.writeBytes(encrypted)
            assertStorageRejected { EncryptedDeliveryStore(context).readLegacyRecovery() }
            assertMainReceiptPreserved(original, mainBefore)
        } finally { encrypted.fill(0); mainBefore.fill(0) }
    }

    @Test fun validJournalCiphertextWithWrongAadRejectsWithoutChangingMainReceipt() {
        val original = originalReceipt()
        store.save(original)
        store.saveLegacyRecovery(journal(LegacyRecoveryPhase.ACKNOWLEDGED, original))
        val mainBefore = receiptFile.readBytes()
        val encrypted = journalFile.readBytes()
        var plain: ByteArray? = null
        var wrongAad: ByteArray? = null
        try {
            // Reuse the exact authenticated schema and real key, changing only the AAD.
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey("gccwebos.portal.delivery.v1", null) as SecretKey
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            decrypt.updateAAD("gccwebos.portal.legacy-recovery.v1".toByteArray(Charsets.UTF_8))
            val decrypted = decrypt.doFinal(encrypted.copyOfRange(12, encrypted.size))
            plain = decrypted
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
            encrypt.init(Cipher.ENCRYPT_MODE, key)
            encrypt.updateAAD("gccwebos.portal.legacy-recovery.fixture-wrong-v1".toByteArray(Charsets.UTF_8))
            val altered = encrypt.iv + encrypt.doFinal(decrypted)
            wrongAad = altered
            journalFile.writeBytes(altered)
            assertStorageRejected { EncryptedDeliveryStore(context).readLegacyRecovery() }
            assertMainReceiptPreserved(original, mainBefore)
        } finally { encrypted.fill(0); plain?.fill(0); wrongAad?.fill(0); mainBefore.fill(0) }
    }

    @Test fun missingJournalBaseRestoresAtomicFileBackupAndKeepsMainReceipt() {
        val original = originalReceipt()
        val expected = journal(LegacyRecoveryPhase.PROFILE_COMMITTING, original)
        store.save(original)
        store.saveLegacyRecovery(expected)
        val mainBefore = receiptFile.readBytes()
        try {
            assertTrue("Fixture journal must move to its backup", journalFile.renameTo(File(journalFile.path + ".bak")))
            assertFalse(journalFile.exists())
            assertJournalEquals(expected, EncryptedDeliveryStore(context).readLegacyRecovery())
            assertTrue("AtomicFile must restore its journal base", journalFile.exists())
            assertMainReceiptPreserved(original, mainBefore)
        } finally { mainBefore.fill(0) }
    }

    private fun originalReceipt() = DeliveryRecord(
        "storage-fixture@example.invalid", "a".repeat(64), "resource_" + "b".repeat(64),
        SYNTHETIC_MARKER + "-original", syntheticUrl("original"), Secret("legacy_setup_" + "c".repeat(64)), false)

    private fun journal(phase: LegacyRecoveryPhase, original: DeliveryRecord): LegacyRecoveryJournal {
        val revealed = phase != LegacyRecoveryPhase.ATTEMPT
        val candidate = DeliveryRecord(original.accountId, "d".repeat(64), "resource_" + "e".repeat(64),
            SYNTHETIC_MARKER + "-candidate", if (revealed) syntheticUrl("candidate") else null,
            if (revealed) Secret("legacy_setup_" + "f".repeat(64)) else null,
            phase == LegacyRecoveryPhase.REVEALED)
        return LegacyRecoveryJournal(original, candidate, phase)
    }

    private fun syntheticUrl(kind: String) = Secret(URI("https", "storage-fixture.invalid",
        "/$SYNTHETIC_MARKER/$kind.yaml", null).toASCIIString())

    private fun assertJournalEquals(expected: LegacyRecoveryJournal, actual: LegacyRecoveryJournal?) {
        assertNotNull("Stored journal must reopen", actual)
        val restored = requireNotNull(actual)
        assertEquals(expected.phase, restored.phase)
        assertTrue("Original receipt must round-trip", sameDeliveryRecord(expected.original, restored.original))
        assertTrue("Candidate receipt must round-trip", sameDeliveryRecord(expected.candidate, restored.candidate))
    }

    private fun assertMainReceiptPreserved(expected: DeliveryRecord, encryptedBefore: ByteArray) {
        assertTrue("Main receipt must still exist", receiptFile.isFile)
        val after = receiptFile.readBytes()
        try { assertTrue("Main receipt ciphertext must remain unchanged", encryptedBefore.contentEquals(after)) }
        finally { after.fill(0) }
        assertTrue("Main receipt must remain readable", sameDeliveryRecord(expected, EncryptedDeliveryStore(context).read()))
    }

    private fun assertStorageRejected(read: () -> Any?) {
        try { read(); fail("Invalid journal must fail closed") }
        catch (failure: PortalFailure) {
            assertEquals(PortalFailure.Reason.STORAGE, failure.reason)
            assertNull(failure.cause) // No record, URL, exception text, or credential output.
        }
    }

    private fun assertAbsent(base: File) {
        for (suffix in listOf("", ".bak", ".new"))
            assertFalse("Owned store files must be removed", File(base.path + suffix).exists())
    }

    private fun requireFixturePackage(target: Context) {
        assertTrue("Requires an owned storage fixture package", target.packageName in setOf(
            "com.gccwebos.android.debug", "com.gccwebos.android.candidate.acceptance.storage20261004"))
    }

    private companion object {
        const val SYNTHETIC_MARKER = "synthetic-legacy-journal-20261004"
    }
}
