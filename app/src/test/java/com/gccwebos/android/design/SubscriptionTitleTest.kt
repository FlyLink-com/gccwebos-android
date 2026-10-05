package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionTitleTest {
    private fun resource(plan: String) = Resource.Available(
        reference = "synthetic-resource",
        plan = plan,
        validUntil = Instant.parse("2099-10-01T00:00:00Z"),
        setupAvailable = false,
        usage = Usage(0, null, null, "pending", null),
    )

    @Test fun monthly50GbUsesGoTitle() {
        assertEquals("Go 订阅", subscriptionTitle(resource("monthly_30d_50gb")))
    }

    @Test fun monthly100GbUsesPlusTitle() {
        assertEquals("Plus 订阅", subscriptionTitle(resource("monthly_30d_100gb")))
    }

    @Test fun yearly200GbUsesAnnualPlusTitle() {
        assertEquals("年 Plus 订阅", subscriptionTitle(resource("yearly_365d_200gb")))
    }

    @Test fun unknownOrSimilarIdsDoNotInferABrandFromDimensionsOrOfferIds() {
        listOf("monthly", "unknown", "MONTHLY_30D_50GB", "monthly_30d_50gb ",
            "offer_plan_30d_50gb_monthly", "yearly_365d_200gb_v2").forEach { plan ->
            assertNull(plan, subscriptionTitle(resource(plan).copy(durationDays = 30, monthlyTrafficGb = 50.0)))
        }
    }

    @Test fun absentOrUnconfirmedResourcesHaveNoSubscriptionTitle() {
        assertNull(subscriptionTitle(null))
        assertNull(subscriptionTitle(Resource.None))
        assertNull(subscriptionTitle(Resource.Reconciliation))
    }

    @Test fun expiryDoesNotRenameTheHistoricalSubscription() {
        val expired = resource("monthly_30d_50gb").copy(validUntil = Instant.EPOCH)
        assertEquals("Go 订阅", subscriptionTitle(expired))
    }
}
