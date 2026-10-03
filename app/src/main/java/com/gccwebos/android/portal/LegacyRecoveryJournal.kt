package com.gccwebos.android.portal

enum class LegacyRecoveryPhase { ATTEMPT, REVEALED, ACKNOWLEDGED, PROFILE_COMMITTING }

/** Durable staging only; this record is never a local connection capability. */
class LegacyRecoveryJournal(val original: DeliveryRecord?, val candidate: DeliveryRecord,
    val phase: LegacyRecoveryPhase) {
    override fun toString() = "LegacyRecoveryJournal([REDACTED])"
}

internal fun sameDeliveryRecord(a: DeliveryRecord?, b: DeliveryRecord?): Boolean {
    if (a == null || b == null) return a == null && b == null
    return a.accountId == b.accountId && a.sessionBinding == b.sessionBinding &&
        a.resource == b.resource && a.operationKey == b.operationKey &&
        a.url?.value == b.url?.value && a.handoff?.value == b.handoff?.value &&
        a.pendingAck == b.pendingAck
}
