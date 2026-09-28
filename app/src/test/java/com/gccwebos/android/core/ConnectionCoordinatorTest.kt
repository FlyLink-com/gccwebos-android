package com.gccwebos.android.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConnectionCoordinatorTest(private val name: String, private val case: ConnectionCases.Case) {
    @Test fun runCase() = runBlocking { withTimeout(5_000) { case.body(this) } }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = ConnectionCases.all.map { arrayOf(it.name, it) }
    }
}
