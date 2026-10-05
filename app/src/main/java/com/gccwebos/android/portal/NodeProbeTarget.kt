package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionSelection

/** Worker-only probe destination. Never UI metadata, persisted identity or tunnel authority. */
internal class NodeProbeTarget internal constructor(
    val selection: ConnectionSelection,
    val endpoint: String,
    val protocol: String,
) {
    override fun toString() = "NodeProbeTarget(<redacted>)"
}
