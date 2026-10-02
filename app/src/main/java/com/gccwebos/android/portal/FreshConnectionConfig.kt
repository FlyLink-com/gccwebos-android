package com.gccwebos.android.portal

import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.ConfigGateway
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Requires an authoritative stable identity/selection contract, NOT an index-derived line ID.
 * There is deliberately no production resolver. Tests use synthetic explicit mappings only.
 */
data class ResolvedConnectionLine(val lineId: String, val index: Int) {
    init { require(lineId.isNotBlank() && index >= 0) }
}
fun interface ConnectionLineResolver {
    fun resolve(resource: Resource.Available, requestedLineId: String?): ResolvedConnectionLine?
}

/** Write-only storage. An old file is never admission authority and has no load/start API.
 * The connection owner supplies an app-private no-backup directory.
 * Replacement must be atomic; unsupported filesystems fail closed without a fallback copy.
 */
/** Borrowed bytes; production uses AtomicPreparedConfigStore, offline contracts inject a fake writer. */
fun interface PreparedConfigStore {
    fun replace(bytes: ByteArray)
    fun clear() = Unit
}
class AtomicPreparedConfigStore(private val directory: File) : PreparedConfigStore {
    /** Fixed write-only scratch target; clearing never traverses directories or loads credentials. */
    @Synchronized override fun clear() {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        Files.deleteIfExists(File(directory, "prepared-connection.json").toPath())
        // An interrupted process may never execute replace()'s finally. Only direct, ordinary
        // scratch files belonging to this writer are removable; never traverse a directory/link.
        Files.newDirectoryStream(directory.toPath(), ".prepared-*.tmp").use { files ->
            files.forEach { path ->
                require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
    }
    @Synchronized override fun replace(bytes: ByteArray) {
        require(bytes.size in 1..com.gccwebos.android.vpn.SafeProxyConfig.MAX_BYTES)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val target = File(directory, "prepared-connection.json").toPath()
        require(!Files.isSymbolicLink(target))
        val staged = Files.createTempFile(directory.toPath(), ".prepared-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileOutputStream(staged.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staged) }
    }
}

/** Each invocation performs authoritative refresh + independent latest download + validation.
 * This must never delegate to copyImportedConfig(). Production UI and VPN remain disconnected.
 */
class FreshConnectionConfigGateway(
    private val repository: PortalRepository,
    private val resolver: ConnectionLineResolver,
    private val files: AtomicPreparedConfigStore,
) : ConfigGateway {
    override suspend fun forLine(lineId: String?): AuthorizedConfig? =
        repository.prepareFreshConnection(lineId, resolver, files)
}
