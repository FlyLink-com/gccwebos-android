package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionTransportLease
import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.Closeable

/** A single preparation result. Bytes are engine DTO bytes, never persisted subscription bytes. */
class PreparedAddressConfig(val bytes: ByteArray, val lease: ConnectionTransportLease? = null) : Closeable {
    override fun close() { bytes.fill(0); lease?.close() }
    override fun toString() = "PreparedAddressConfig(<redacted>)"
}

fun interface ConfigAddressPreparer {
    /** Called only after all nodes and the selected raw revision have been validated. */
    suspend fun prepare(raw: ByteArray, index: Int): PreparedAddressConfig
}

/** Retains the literal-only contract when address preparation is disabled. */
object LiteralConfigAddressPreparer : ConfigAddressPreparer {
    override suspend fun prepare(raw: ByteArray, index: Int) = PreparedAddressConfig(SafeProxyConfig.project(raw, index))
}
