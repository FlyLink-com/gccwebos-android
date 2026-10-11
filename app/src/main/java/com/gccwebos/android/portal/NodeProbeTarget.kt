package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionSelection

internal enum class NodeProbeTransport { Icmp, Tcp, Quic }

/** Worker-only probe destination. Never UI metadata, persisted identity or tunnel authority. */
internal class NodeProbeTarget internal constructor(
    val selection: ConnectionSelection,
    val endpoint: String,
    val protocol: String,
    val port: Int = 0,
    val transport: NodeProbeTransport = NodeProbeTransport.Icmp,
) {
    override fun toString() = "NodeProbeTarget(<redacted>)"
}

/**
 * Worker-only probe destination of a node in a saved source that is not the active one. It carries no
 * [com.gccwebos.android.core.ConnectionSelection]: it can never be selected, connected or used as authority.
 * [contentKey] ties a reading to the exact stored bytes it was taken from, so a replaced or refreshed
 * source can never show an older source's reading.
 */
internal class LibraryProbeTarget internal constructor(
    val source: String,
    val position: Int,
    val contentKey: String,
    val endpoint: String,
    val protocol: String,
    val port: Int,
    val transport: NodeProbeTransport,
) {
    val key: LibraryNodeKey get() = LibraryNodeKey(source, position)
    override fun toString() = "LibraryProbeTarget(<redacted>)"
}

/** Source id (the saved entry's id, or [AccountSourceId]) plus the node's position in that source's list. */
internal data class LibraryNodeKey(val source: String, val position: Int)

internal const val AccountSourceId = "account"
