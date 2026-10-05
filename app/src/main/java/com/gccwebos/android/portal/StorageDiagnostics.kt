package com.gccwebos.android.portal

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.time.format.DateTimeParseException
import javax.crypto.AEADBadTagException

internal enum class StoreTarget { Session, Delivery, Profile }
internal enum class StoreOperation { Read, Save, Clear, ReadRecovery, SaveRecovery, ClearRecovery }
internal enum class FailureKind { CryptoAuthentication, CryptoOrKeyStore, Io, Validation, AlreadySanitized, Unknown }

/** Only fixed categories may cross the diagnostic boundary; no failure or storage material is retained. */
internal data class StoreDiagnostic(val target: StoreTarget, val operation: StoreOperation, val kind: FailureKind) {
    fun payload(): String = "${target.name}/${operation.name}/${kind.name}"
}

internal object StorageDiagnostics {
    fun evidence(target: StoreTarget, operation: StoreOperation, failure: Exception): StoreDiagnostic =
        StoreDiagnostic(target, operation, when (failure) {
            is AEADBadTagException -> FailureKind.CryptoAuthentication
            is GeneralSecurityException, is ProviderException -> FailureKind.CryptoOrKeyStore
            is IOException -> FailureKind.Io
            is IllegalArgumentException, is ClassCastException, is DateTimeParseException -> FailureKind.Validation
            else -> fixedClassKind(failure.javaClass.name)
        })

    // Exact names only, for platform classes unavailable to the pure JVM classifier.
    internal fun fixedClassKind(className: String): FailureKind = when (className) {
        "android.system.ErrnoException" -> FailureKind.Io
        "android.security.KeyStoreException" -> FailureKind.CryptoOrKeyStore
        "org.json.JSONException" -> FailureKind.Validation
        "com.gccwebos.android.portal.PortalFailure" -> FailureKind.AlreadySanitized
        else -> FailureKind.Unknown
    }
}
