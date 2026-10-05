package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Modifier
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import java.time.format.DateTimeParseException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/** Every exception and privacy marker here is synthetic; no customer store is opened. */
class StorageDiagnosticsTest {
    private val marker = "synthetic-private-material-never-publish"
    private fun kind(failure: Exception) = StorageDiagnostics.evidence(StoreTarget.Session, StoreOperation.Read, failure).kind

    @Test fun onlyTheAuthenticationTypeReceivesCryptoAuthentication() {
        assertEquals(FailureKind.CryptoAuthentication, kind(AEADBadTagException(marker)))
        assertEquals(FailureKind.CryptoOrKeyStore, kind(BadPaddingException(marker)))
    }

    @Test fun explicitSecurityTypesRemainBroadCryptoOrKeyStore() {
        for (failure in listOf(GeneralSecurityException(marker), InvalidKeyException(marker),
            KeyStoreException(marker), NoSuchAlgorithmException(marker), ProviderException(marker))) {
            assertEquals(FailureKind.CryptoOrKeyStore, kind(failure))
        }
    }

    @Test fun ioTypesAndTheirSubtypesHaveOneFixedCategory() {
        for (failure in listOf(IOException(marker), FileNotFoundException(marker), EOFException(marker))) {
            assertEquals(FailureKind.Io, kind(failure))
        }
    }

    @Test fun explicitValidationTypesHaveOneFixedCategory() {
        for (failure in listOf(IllegalArgumentException(marker), ClassCastException(marker),
            DateTimeParseException(marker, marker, 0))) {
            assertEquals(FailureKind.Validation, kind(failure))
        }
    }

    @Test fun exactPlatformAndSanitizedClassNamesHaveOnlyFixedCategories() {
        val whitelist = mapOf(
            "android.system.ErrnoException" to FailureKind.Io,
            "android.security.KeyStoreException" to FailureKind.CryptoOrKeyStore,
            "org.json.JSONException" to FailureKind.Validation,
            "com.gccwebos.android.portal.PortalFailure" to FailureKind.AlreadySanitized)
        for ((name, expected) in whitelist) {
            assertEquals(expected, StorageDiagnostics.fixedClassKind(name))
            for (other in listOf(name.substringAfterLast('.'), "synthetic.$name", "$name$", name.lowercase())) {
                assertEquals(FailureKind.Unknown, StorageDiagnostics.fixedClassKind(other))
            }
        }
    }

    @Test fun namesInsideMessagesAndUnknownTypesCannotMasqueradeAsTypedFailures() {
        for (failure in listOf(Exception("android.system.ErrnoException $marker"),
            RuntimeException("javax.crypto.AEADBadTagException $marker"), IllegalStateException(marker),
            UnsupportedOperationException(marker), Synthetic.ErrnoException(marker))) {
            assertEquals(FailureKind.Unknown, kind(failure))
        }
    }

    @Test fun causalWrappersAreClassifiedByTheirOwnTypeWithoutUnwrapping() {
        val nested = AEADBadTagException(marker)
        assertEquals(FailureKind.Unknown, kind(RuntimeException(marker, nested)))
        assertEquals(FailureKind.Io, kind(IOException(marker, nested)))
    }

    @Test fun opaqueExceptionMessageCauseAndToStringAreNeverAccessed() {
        assertEquals(FailureKind.Unknown, kind(OpaqueException()))
        assertEquals(FailureKind.Io, kind(OpaqueIOException()))
    }

    @Test fun payloadContainsExactlyThreeFixedEnumsAcrossAllTargetsAndOperations() {
        for (target in StoreTarget.entries) for (operation in StoreOperation.entries) {
            val diagnostic = StorageDiagnostics.evidence(target, operation, IOException(marker))
            assertEquals(listOf(target.name, operation.name, FailureKind.Io.name), diagnostic.payload().split('/'))
            assertFalse(diagnostic.payload().contains(marker))
            assertFalse(diagnostic.toString().contains(marker))
        }
    }

    @Test fun evidenceRetainsOnlyThreeEnumFieldsAndNoExceptionReference() {
        val fields = StoreDiagnostic::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
        assertEquals(setOf(StoreTarget::class.java, StoreOperation::class.java, FailureKind::class.java), fields.map { it.type }.toSet())
        assertEquals(3, fields.size)
    }

    @Test fun theDiagnosticVocabularyIsExplicitAndClosed() {
        assertEquals(listOf("Session", "Delivery", "Profile"), StoreTarget.entries.map { it.name })
        assertEquals(listOf("Read", "Save", "Clear", "ReadRecovery", "SaveRecovery", "ClearRecovery"), StoreOperation.entries.map { it.name })
        assertEquals(listOf("CryptoAuthentication", "CryptoOrKeyStore", "Io", "Validation", "AlreadySanitized", "Unknown"), FailureKind.entries.map { it.name })
    }

    private object Synthetic { class ErrnoException(message: String) : Exception(message) }
    private class OpaqueException : Exception() {
        override val message: String get() = error("Diagnostic read an exception message")
        override val cause: Throwable get() = error("Diagnostic read an exception cause")
        override fun toString(): String = error("Diagnostic rendered an exception")
    }
    private class OpaqueIOException : IOException() {
        override val message: String get() = error("Diagnostic read an exception message")
        override val cause: Throwable get() = error("Diagnostic read an exception cause")
        override fun toString(): String = error("Diagnostic rendered an exception")
    }
}
