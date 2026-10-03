package com.gccwebos.android.portal

interface DeliveryStore {
    fun read(): DeliveryRecord?
    /** Must finish durable atomic storage before returning. */
    fun save(record: DeliveryRecord)
    fun clear()
    fun readLegacyRecovery(): LegacyRecoveryJournal? = null
    /** A legacy recovery must never silently fall back to an in-memory journal. */
    fun saveLegacyRecovery(journal: LegacyRecoveryJournal) {
        throw PortalFailure(PortalFailure.Reason.STORAGE)
    }
    fun clearLegacyRecovery() = Unit
}

class MemoryDeliveryStore : DeliveryStore {
    private var record: DeliveryRecord? = null
    private var recovery: LegacyRecoveryJournal? = null
    override fun read(): DeliveryRecord? = record
    override fun save(record: DeliveryRecord) { this.record = record }
    override fun readLegacyRecovery(): LegacyRecoveryJournal? = recovery
    override fun saveLegacyRecovery(journal: LegacyRecoveryJournal) { recovery = journal }
    override fun clearLegacyRecovery() { recovery = null }
    override fun clear() { record = null; recovery = null }
}
