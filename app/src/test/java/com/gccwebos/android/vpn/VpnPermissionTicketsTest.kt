package com.gccwebos.android.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPermissionTicketsTest {
    @Test fun noAttachmentCannotAllocate() {
        assertNull(VpnPermissionTickets().begin())
    }

    @Test fun onlyOneSystemResultSlotMayBeOutstanding() {
        val tickets = VpnPermissionTickets()
        val attachment = tickets.attach()
        val first = tickets.begin()!!
        assertTrue(first.generation > 0)
        assertNull(tickets.begin())
        assertSame(first, tickets.deliver(attachment))
    }

    @Test fun cancelledResultMustDrainBeforeAnotherTicketCanUseItsSlot() {
        val tickets = VpnPermissionTickets()
        val attachment = tickets.attach()
        val cancelled = tickets.begin()!!
        tickets.cancel(cancelled)
        assertFalse(tickets.isActive(cancelled))
        assertNull("A late old result must never complete a replacement request", tickets.begin())
        assertNull(tickets.deliver(attachment))
        val next = tickets.begin()!!
        assertTrue(next.generation > cancelled.generation)
        assertSame(next, tickets.deliver(attachment))
    }

    @Test fun wrongAttachmentCannotConsumeTheCurrentSlot() {
        val tickets = VpnPermissionTickets()
        val old = tickets.attach()
        tickets.begin()
        tickets.detach(old)
        val current = tickets.attach()
        val request = tickets.begin()!!
        assertNull(tickets.deliver(old))
        assertTrue(tickets.isActive(request))
        assertSame(request, tickets.deliver(current))
    }

    @Test fun detachInvalidatesTheTicketAndDoesNotReuseItsAttachment() {
        val tickets = VpnPermissionTickets()
        val old = tickets.attach()
        val request = tickets.begin()!!
        tickets.detach(old)
        assertFalse(tickets.isActive(request))
        assertNull(tickets.deliver(old))
        assertNull(tickets.begin())
        val current = tickets.attach()
        val next = tickets.begin()!!
        assertNotSame(old, current)
        assertTrue(next.generation > request.generation)
    }

    @Test fun aReturnedTicketCanOnlyCompleteOnce() {
        val tickets = VpnPermissionTickets()
        val attachment = tickets.attach()
        val first = tickets.begin()!!
        assertSame(first, tickets.deliver(attachment))
        assertNull(tickets.deliver(attachment))
        assertNotSame(first, tickets.begin())
    }

    @Test fun staleCancellationCannotInvalidateTheNewTicket() {
        val tickets = VpnPermissionTickets()
        val attachment = tickets.attach()
        val first = tickets.begin()!!
        tickets.deliver(attachment)
        val next = tickets.begin()!!
        tickets.cancel(first)
        assertTrue(tickets.isActive(next))
    }

    @Test fun replacingAnAttachmentInvalidatesOnlyTheOldSlot() {
        val tickets = VpnPermissionTickets()
        val old = tickets.attach()
        val first = tickets.begin()!!
        val current = tickets.attach()
        val next = tickets.begin()!!
        tickets.detach(old)
        assertFalse(tickets.isActive(first))
        assertNull(tickets.deliver(old))
        assertTrue(tickets.isActive(next))
        assertNotNull(tickets.deliver(current))
    }
}
