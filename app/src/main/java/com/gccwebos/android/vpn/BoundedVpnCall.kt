package com.gccwebos.android.vpn

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A logical timeout never cancels or releases the still executing Binder claim. */
internal class BoundedVpnCall<T>(action: () -> T) {
    private val task = FutureTask(action)
    init { Thread(task, "vpn-control-call").apply { isDaemon = true; start() } }
    fun await(millis: Long): T = task.get(millis, TimeUnit.MILLISECONDS)
    val returned: Boolean get() = task.isDone && !task.isCancelled
}
