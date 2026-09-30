package com.gccwebos.android.portal

interface DeliveryStore {
    fun read(): DeliveryRecord?
    /** Must finish durable atomic storage before returning. */
    fun save(record: DeliveryRecord)
    fun clear()
}
