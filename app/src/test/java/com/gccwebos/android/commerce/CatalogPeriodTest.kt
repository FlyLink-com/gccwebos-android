package com.gccwebos.android.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogPeriodTest {
    private val plan = Offer("server_plan", "server_version", LocalizedText("月卡", "Monthly"),
        LocalizedText("", ""), LocalizedNotices(emptyList(), emptyList()), 73, 7300,
        listOf("paid_coins"), 30, 17, 0)

    @Test fun nonstandardServerDurationsStayReachableWithoutBeingRenamedAsMonthOrYear() {
        val custom47 = plan.copy(offerRef = "server_47", durationDays = 47)
        val custom83 = plan.copy(offerRef = "server_83", durationDays = 83,
            title = LocalizedText("年卡", "Yearly"))
        val offers = listOf(plan, custom47, plan.copy(offerRef = "server_year", durationDays = 365), custom83)
        val grouped = offers.groupBy(::catalogPeriod)
        assertEquals(setOf(CatalogPeriod.Monthly, CatalogPeriod.Yearly, CatalogPeriod.Other), grouped.keys)
        assertEquals(listOf(custom47, custom83), grouped[CatalogPeriod.Other])
        assertSame(custom47, grouped.getValue(CatalogPeriod.Other)[0])
        assertSame(custom83, grouped.getValue(CatalogPeriod.Other)[1])
        assertEquals(offers.size, grouped.values.sumOf { it.size })
    }

    @Test fun initialVisiblePeriodUsesAvailablePlanDurationsRegardlessOfServerOrder() {
        val yearly = plan.copy(durationDays = 365)
        val custom = plan.copy(durationDays = 47)
        assertEquals(CatalogPeriod.Other, initialCatalogPeriod(listOf(custom)))
        assertEquals(CatalogPeriod.Yearly, initialCatalogPeriod(listOf(custom, yearly)))
        assertEquals(CatalogPeriod.Monthly, initialCatalogPeriod(listOf(custom, yearly, plan)))
        assertEquals(CatalogPeriod.Monthly, initialCatalogPeriod(listOf(plan, yearly, custom)))
    }

    @Test fun dataPacksDoNotClaimAPlanPeriodAndMissingDurationsAreNotInvented() {
        val pack = plan.copy(category = "data_pack", durationDays = null, monthlyTrafficGb = null, trafficGb = 83)
        val custom = plan.copy(durationDays = 83)
        assertEquals(CatalogPeriod.Other, initialCatalogPeriod(listOf(pack, custom)))
        assertEquals(CatalogPeriod.Other, catalogPeriod(plan.copy(durationDays = null)))
        assertEquals(CatalogPeriod.Monthly, initialCatalogPeriod(listOf(pack)))
        assertEquals(CatalogPeriod.Monthly, initialCatalogPeriod(emptyList()))
    }
}
