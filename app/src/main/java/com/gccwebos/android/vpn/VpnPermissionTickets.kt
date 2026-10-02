package com.gccwebos.android.vpn

/** Process-local consent identity. An Android result slot is never reassigned while pending. */
internal class VpnPermissionTickets {
    class Attachment internal constructor()
    class Ticket internal constructor(val generation: Long, val attachment: Attachment)

    private var generation = 0L
    private var attachment: Attachment? = null
    private var slot: Ticket? = null
    private var live = false

    fun attach(): Attachment {
        slot = null
        live = false
        return Attachment().also { attachment = it }
    }

    fun detach(expected: Attachment) {
        if (attachment !== expected) return
        attachment = null
        slot = null
        live = false
    }

    fun begin(): Ticket? {
        val current = attachment ?: return null
        if (slot != null) return null
        return Ticket(++generation, current).also { slot = it; live = true }
    }

    fun isActive(ticket: Ticket): Boolean = slot === ticket && live && attachment === ticket.attachment

    fun cancel(ticket: Ticket) {
        // Keep a tombstone until the old system result drains or its attachment is detached.
        if (slot === ticket) live = false
    }

    fun deliver(expected: Attachment): Ticket? {
        val ticket = slot ?: return null
        if (attachment !== expected || ticket.attachment !== expected) return null
        slot = null
        val accepted = live
        live = false
        return ticket.takeIf { accepted }
    }
}
