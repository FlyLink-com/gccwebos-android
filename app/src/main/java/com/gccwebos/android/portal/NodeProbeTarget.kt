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
