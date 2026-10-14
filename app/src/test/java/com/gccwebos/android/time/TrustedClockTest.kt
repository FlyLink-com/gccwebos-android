package com.gccwebos.android.time

import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.connectionOverviewPresentation
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TrustedClockTest {
    private val server = Instant.parse("2026-10-14T12:00:00Z")
    private var wall = server
    private var elapsed = 1_000_000L
    private val store = MemoryTrustedTimeStore()
    private fun clock() = TrustedClock({ wall }, { elapsed }, store)

    @Test fun clockSetBackKeepsAnExpiredSubscriptionExpired() {
        val c = clock(); c.observeServerTime(server)
        val until = server.minusSeconds(3600)
        elapsed += 10_000; wall = server.minusSeconds(86_400L * 30)
        val now = c.now()
        assertTrue("expired must stay expired", now >= server)
        val snapshot = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true, hasCapability = true, validUntil = until)
        val control = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        assertEquals(LocalSubscriptionStatus.EXPIRED, connectionOverviewPresentation(snapshot, now, control).status)
        assertNotEquals("the raw phone clock would revive it", LocalSubscriptionStatus.EXPIRED,
            connectionOverviewPresentation(snapshot, wall, control).status)
    }

    @Test fun trustedTimeAdvancesWithTheMonotonicClock() {
        val c = clock(); c.observeServerTime(server)
        elapsed += 3_600_000; wall = server
        assertEquals(server.plusSeconds(3600), c.now())
    }

    @Test fun rebootThenClockSetBackStillUsesThePersistedFloor() {
        clock().apply { observeServerTime(server); elapsed += 600_000; trustedNow() }
        elapsed = 5_000; wall = server.minusSeconds(86_400L * 365)
        assertTrue(clock().now() >= server)
    }

    @Test fun withoutServerTimeThePhoneClockIsUsed() {
        wall = Instant.parse("2020-01-01T00:00:00Z")
        assertEquals(wall, TrustedClock({ wall }, { elapsed }, MemoryTrustedTimeStore()).now())
    }

    @Test fun aPhoneClockAheadOrWithinToleranceIsUsedAsIs() {
        val c = clock(); c.observeServerTime(server)
        wall = server.plusSeconds(86_400); assertEquals(wall, c.now())
        wall = server.minusSeconds(60); assertEquals(wall, c.now())
    }

    @Test fun floorIsMonotonicAndAnOlderServerTimeCannotLowerIt() {
        val c = clock(); c.observeServerTime(server)
        c.observeServerTime(server.minusSeconds(86_400))
        assertEquals(server.toEpochMilli(), store.read())
    }

    @Test fun storageFailuresFallBackGracefully() {
        val broken = object : TrustedTimeStore {
            override fun read(): Long? = throw java.io.IOException("x")
            override fun save(epochMillis: Long) = throw java.io.IOException("x")
        }
        val c = TrustedClock({ wall }, { elapsed }, broken); c.observeServerTime(server)
        wall = server.minusSeconds(86_400)
        assertEquals(server, c.now())
    }

    @Test fun parsesRfc1123DateAndRejectsGarbage() {
        assertEquals(Instant.parse("2026-10-14T12:00:00Z"), TrustedClock.parseHttpDate("Wed, 14 Oct 2026 12:00:00 GMT"))
        assertNull(TrustedClock.parseHttpDate("not a date")); assertNull(TrustedClock.parseHttpDate(null))
    }
}
