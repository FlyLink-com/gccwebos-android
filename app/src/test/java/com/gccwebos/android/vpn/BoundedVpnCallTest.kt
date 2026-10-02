package com.gccwebos.android.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BoundedVpnCallTest {
    @Test fun timeoutKeepsExecutingClaimAndMaterialUntilActualReturn() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val wiped = AtomicBoolean(false)
        val material = byteArrayOf(7, 9)
        val call = BoundedVpnCall {
            try { entered.countDown(); check(gate.await(2, TimeUnit.SECONDS)); true }
            finally { material.fill(0); wiped.set(true) }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try { call.await(20); fail("Must time out while actual transaction remains in stack") }
            catch (_: TimeoutException) { }
            assertFalse(call.returned)
            assertFalse(wiped.get())
            assertArrayEquals(byteArrayOf(7, 9), material)
        } finally { gate.countDown() }
        assertTrue(call.await(1_000))
        assertTrue(call.returned)
        assertTrue(wiped.get())
        assertArrayEquals(byteArrayOf(0, 0), material)
    }
    @Test fun exceptionalActualReturnReleasesClaim() {
        val call = BoundedVpnCall<Boolean> { throw IllegalStateException("synthetic") }
        try { call.await(1_000); fail("Expected actual failure") }
        catch (_: java.util.concurrent.ExecutionException) { }
        assertTrue(call.returned)
    }
}
