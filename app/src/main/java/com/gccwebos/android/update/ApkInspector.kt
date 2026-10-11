package com.gccwebos.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What the installer would see: package, version and the SHA-256 of every current signing certificate. */
data class PackageFacts(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

enum class CandidateCheck { OK, UNREADABLE, WRONG_PACKAGE, NOT_NEWER, WRONG_VERSION, SIGNER_MISMATCH }

/**
 * Pure decision: the downloaded archive must be the same application, strictly newer than what is
 * installed, exactly the version the manifest announced, and signed by exactly the installed signers.
 */
fun checkCandidate(candidate: PackageFacts?, installed: PackageFacts, release: ReleaseManifest.Available,
    expectedPackage: String = OfficialReleases.RELEASE_APPLICATION_ID): CandidateCheck = when {
    candidate == null -> CandidateCheck.UNREADABLE
    candidate.packageName != expectedPackage || installed.packageName != expectedPackage -> CandidateCheck.WRONG_PACKAGE
    candidate.versionCode <= installed.versionCode -> CandidateCheck.NOT_NEWER
    candidate.versionCode != release.versionCode -> CandidateCheck.WRONG_VERSION
    candidate.signerSha256.isEmpty() || installed.signerSha256.isEmpty() || candidate.signerSha256 != installed.signerSha256 ->
        CandidateCheck.SIGNER_MISMATCH
    else -> CandidateCheck.OK
}

interface ApkInspector {
    /** Null when the archive cannot be parsed or has no verifiable signature. Blocking; call off the UI thread. */
    fun archive(file: File): PackageFacts?
    fun installed(): PackageFacts
}

class AndroidApkInspector(private val context: Context) : ApkInspector {
    @Suppress("DEPRECATION")
    private val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    override fun archive(file: File): PackageFacts? = try {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.let(::facts)
    } catch (_: Exception) { null }

    override fun installed(): PackageFacts =
        facts(context.packageManager.getPackageInfo(context.packageName, flags))

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): PackageFacts {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val digests = signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return PackageFacts(info.packageName, code, digests)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
