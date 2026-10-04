package com.gccwebos.android.vpn

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private same-UID Binder protocol. RUNNING is native readiness, never traffic proof.
 * COPY_START fields: owner Binder, generation long, resource expiry epoch millis (0 unknown),
 * mandatory admission deadline elapsed millis, byte[]. Deadline validation and worker take are
 * bounded admission checks, not a promise that JNI starts before the caller receives its reply.
 * Every response starts with writeNoException; COPY_START writes 1/0, STOP writes 1, STATE an int.
 */
object VpnControl {
    const val DESCRIPTOR = "com.gccwebos.android.vpn.ProductionVpnControl"
    const val ACTION_BIND = "com.gccwebos.android.vpn.PRODUCTION_BIND"
    const val ACTION_STOP = "com.gccwebos.android.vpn.PRODUCTION_STOP"
    const val COPY_START = 1
    const val STOP = 2
    const val STATE = 3
    const val STARTING = 1
    const val RUNNING = 2
    const val STOPPING = 3
    const val FAILED = 4
    const val MAX_CONFIG_BYTES = 64 * 1024
    const val MAX_ADMISSION_MILLIS = 1_500L

    private const val BINDER_FLAG_ONEWAY = 0x01 // Android IBinder.FLAG_ONEWAY protocol bit.
    internal fun isSynchronousCall(hasReply: Boolean, flags: Int): Boolean =
        hasReply && (flags and BINDER_FLAG_ONEWAY) == 0
}

/** One process, one admission; owned pending bytes can be revoked before any native work. */
internal class ProductionVpnAdmission {
    private var admitted: Ticket? = null
    private var material: ByteArray? = null
    private var stopped = false
    class Ticket internal constructor(val generation: Long, val expiryEpochMillis: Long,
        val expiryElapsedMillis: Long, val admissionDeadlineElapsedMillis: Long) {
        override fun toString() = "ProductionVpnTicket(<opaque>)"
    }
    @Synchronized fun copyAndArm(generation: Long, expiryEpochMillis: Long, input: ByteArray,
        nowEpochMillis: Long, nowElapsedMillis: Long,
        admissionDeadlineElapsedMillis: Long = defaultAdmissionDeadline(nowElapsedMillis)): Ticket? {
        if (stopped || admitted != null || generation <= 0 || expiryEpochMillis < 0 ||
            nowEpochMillis < 0 || nowElapsedMillis < 0 ||
            !validAdmissionDeadline(admissionDeadlineElapsedMillis, nowElapsedMillis) ||
            (expiryEpochMillis != 0L && expiryEpochMillis <= nowEpochMillis) ||
            input.size !in 1..VpnControl.MAX_CONFIG_BYTES || input.any { it == 0.toByte() }) return null
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input))
        } catch (_: Exception) { return null }
        val remaining = if (expiryEpochMillis == 0L) 0 else expiryEpochMillis - nowEpochMillis
        val deadline = if (remaining == 0L) 0 else nowElapsedMillis +
            minOf(remaining, Long.MAX_VALUE - nowElapsedMillis)
        val ticket = Ticket(generation, expiryEpochMillis, deadline, admissionDeadlineElapsedMillis)
        material = input.copyOf()
        admitted = ticket
        return ticket
    }
    @Synchronized fun take(ticket: Ticket, nowEpochMillis: Long, nowElapsedMillis: Long): ByteArray? {
        if (!active(ticket, nowEpochMillis, nowElapsedMillis)) return null
        val pending = material ?: return null // An already-taken running ticket is not renewed or stopped.
        if (nowElapsedMillis < 0 || nowElapsedMillis >= ticket.admissionDeadlineElapsedMillis) {
            stop() // Erase the owned pending copy before the JNI task can obtain authority.
            return null
        }
        material = null
        return pending
    }
    @Synchronized fun active(ticket: Ticket, nowEpochMillis: Long, nowElapsedMillis: Long): Boolean {
        if (stopped || admitted !== ticket) return false
        if (ticket.expiryEpochMillis != 0L && (nowEpochMillis >= ticket.expiryEpochMillis ||
                nowElapsedMillis >= ticket.expiryElapsedMillis)) {
            stop()
            return false
        }
        return true
    }
    @Synchronized fun stop() {
        stopped = true
        material?.fill(0)
        material = null
    }
    companion object {
        fun validAdmissionDeadline(deadlineElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
            nowElapsedMillis >= 0 && deadlineElapsedMillis > nowElapsedMillis &&
                deadlineElapsedMillis - nowElapsedMillis <= VpnControl.MAX_ADMISSION_MILLIS

        // Existing pure contracts may omit the field; the production Parcel always supplies it.
        private fun defaultAdmissionDeadline(nowElapsedMillis: Long): Long = if (nowElapsedMillis < 0) 0
            else nowElapsedMillis + minOf(VpnControl.MAX_ADMISSION_MILLIS, Long.MAX_VALUE - nowElapsedMillis)
    }
}
