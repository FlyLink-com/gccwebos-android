package com.gccwebos.android.portal

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.BuildConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two explicit invocations on one disposable package; no account, portal, Activity or VPN work. */
class SavedSessionUpgradeInstrumentedTest {
    private data class OwnerMarker(val savedAt: Instant, val savedElapsed: Long, val processStartedElapsed: Long)

    @Test fun savedSessionSurvivesProcessAndSameSignatureUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireOwnedPackage(context) // Before construction, filesystem access or AndroidKeyStore use.
        val phase = InstrumentationRegistry.getArguments().getString("storagePhase")
        assertTrue("Requires exactly WRITE or VERIFY phase", phase == "WRITE" || phase == "VERIFY")
        try {
            when (phase) {
                "WRITE" -> write(context)
                "VERIFY" -> verify(context)
                else -> throw AssertionError("Requires exactly WRITE or VERIFY phase")
            }
        } catch (_: Exception) {
            // Platform/parser exceptions must never print record values or original exception messages.
            throw AssertionError("Synthetic saved-session storage phase failed")
        }
    }

    private fun write(context: Context) {
        assertAbsent(sessionFile(context))
        assertAbsent(markerFile(context))
        val marker = OwnerMarker(Instant.now(), SystemClock.elapsedRealtime(), Process.getStartElapsedRealtime())
        assertTrue("Synthetic monotonic clocks must be ordered", marker.processStartedElapsed in 0..marker.savedElapsed)
        val expected = fixture(marker)
        val store = EncryptedSessionStore(context)
        store.save(expected)
        assertTrue("Same-instance synthetic session readback must match", sameSession(expected, store.read()))
        assertTrue("Synthetic session must restore at WRITE", expected.restore(marker.savedAt, marker.savedElapsed) != null)
        writeMarker(context, marker) // Ownership is published only after durable store/readback success.
        assertTrue("Synthetic ownership marker must read back", readMarker(context) == marker)
    }

    private fun verify(context: Context) {
        val marker = readMarker(context) // Refuse all storage access without the owned WRITE marker.
        val now = Instant.now()
        val elapsed = SystemClock.elapsedRealtime()
        assertTrue("VERIFY requires a later process instance", Process.getStartElapsedRealtime() > marker.processStartedElapsed)
        assertTrue("WRITE wall time must have elapsed", now.isAfter(marker.savedAt))
        assertTrue("WRITE monotonic time must have elapsed", elapsed > marker.savedElapsed)
        val expected = fixture(marker)
        val store = EncryptedSessionStore(context)
        val received = store.read()
        assertTrue("New-instance synthetic persisted session must match", sameSession(expected, received))
        val restored = received?.restore(now, elapsed)
        assertTrue("Synthetic persisted session must still restore with reduced lifetime",
            restored != null && restored.token.value == FIXTURE_TOKEN && restored.accountId == FIXTURE_ACCOUNT &&
                restored.expiresAt == expected.expiresAt && restored.lifetimeMillis in 1 until LIFETIME_MILLIS &&
                restored.valid(now, elapsed))

        // Cleanup is successful-verification only, and cannot remove an unowned or changed record.
        requireOwnedPackage(context)
        assertTrue("Owned marker must still match before cleanup", readMarker(context) == marker)
        assertTrue("Owned session must still match before cleanup", sameSession(expected, store.read()))
        store.clear()
        assertAbsent(sessionFile(context))
        AtomicFile(markerFile(context)).delete()
        assertAbsent(markerFile(context))
    }

    private fun fixture(marker: OwnerMarker) = SavedSession(Secret(FIXTURE_TOKEN), FIXTURE_ACCOUNT,
        marker.savedAt.plusMillis(LIFETIME_MILLIS), LIFETIME_MILLIS, marker.savedAt, marker.savedElapsed)

    private fun sameSession(expected: SavedSession, actual: SavedSession?): Boolean = actual != null &&
        actual.token.value == expected.token.value && actual.accountId == expected.accountId &&
        actual.expiresAt == expected.expiresAt && actual.remainingMillis == expected.remainingMillis &&
        actual.savedAt == expected.savedAt && actual.savedElapsed == expected.savedElapsed

    private fun writeMarker(context: Context, marker: OwnerMarker) {
        val file = AtomicFile(markerFile(context))
        val output = file.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeUTF(OWNER_MAGIC)
            data.writeLong(marker.savedAt.epochSecond)
            data.writeInt(marker.savedAt.nano)
            data.writeLong(marker.savedElapsed)
            data.writeLong(marker.processStartedElapsed)
            data.flush()
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun readMarker(context: Context): OwnerMarker {
        val file = markerFile(context)
        assertTrue("Owned WRITE marker is required", file.isFile && file.length() in 1..256)
        return DataInputStream(file.inputStream()).use { data ->
            assertTrue("Owned WRITE marker must match", data.readUTF() == OWNER_MAGIC)
            val seconds = data.readLong()
            val nanos = data.readInt()
            val savedElapsed = data.readLong()
            val processStartedElapsed = data.readLong()
            assertTrue("Owned marker must have exact shape", data.read() == -1 && nanos in 0..999_999_999 &&
                savedElapsed >= 0 && processStartedElapsed in 0..savedElapsed)
            OwnerMarker(Instant.ofEpochSecond(seconds, nanos.toLong()), savedElapsed, processStartedElapsed)
        }
    }

    private fun assertAbsent(file: File) {
        for (suffix in listOf("", ".new", ".bak")) {
            assertFalse("Requires absent synthetic owned artifacts", File(file.path + suffix).exists())
        }
    }

    private fun sessionFile(context: Context) = File(context.noBackupFilesDir, "portal-session.bin")
    private fun markerFile(context: Context) = File(context.noBackupFilesDir, "synthetic-session-owner.marker")
    private fun requireOwnedPackage(context: Context) {
        assertTrue("Requires the uniquely owned storage acceptance package", BuildConfig.DEBUG &&
            BuildConfig.STORAGE_SYNTHETIC_ACCEPTANCE && BuildConfig.APPLICATION_ID == OWNED_PACKAGE &&
            context.packageName == OWNED_PACKAGE)
    }

    private companion object {
        const val OWNED_PACKAGE = "com.gccwebos.android.candidate.acceptance.session20261004"
        const val OWNER_MAGIC = "gccwebos.synthetic.saved-session.acceptance.v1"
        const val FIXTURE_TOKEN = "synthetic-saved-session-token-only"
        const val FIXTURE_ACCOUNT = "synthetic-session-fixture@example.invalid"
        const val LIFETIME_MILLIS = 1_800_000L
    }
}
