package com.gccwebos.android.portal

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionOwnerLeaseTest {
    @Test fun duplicatedCloseCannotReleaseLaterLeaseEvenWhenCallbackObjectIsReused() = runTest {
        val authority = LocalSubscriptionAuthority()
        val observer: () -> Unit = { }
        val old = authority.observeClear(observer)
        old.close()
        val live = authority.observeClear(observer)
        var acquisitions = 0
        val waiter = async { authority.awaitConnectionOwner { acquisitions++; authority.observeClear { } } }
        runCurrent()
        old.close(); runCurrent()
        assertEquals(0, acquisitions)
        assertFalse(waiter.isCompleted)
        assertThrows(IllegalStateException::class.java) { authority.observeClear { } }
        live.close(); runCurrent()
        assertEquals(1, acquisitions)
        waiter.await().close()
    }

    @Test fun cancelingNotifiedWaiterBeforeDispatchCannotCallFactoryOrCancelSignal() = runTest {
        val authority = LocalSubscriptionAuthority()
        val old = authority.observeClear { }
        var firstClaims = 0
        val canceled = async { authority.awaitConnectionOwner { firstClaims++; authority.observeClear { } } }
        val survivor = async { authority.awaitConnectionOwner { authority.observeClear { } } }
        runCurrent()
        old.close()
        canceled.cancel()
        runCurrent()
        assertEquals(0, firstClaims)
        assertTrue(survivor.isCompleted)
        survivor.await().close()
    }
}
