package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import java.time.Instant

/** Presentation boundary only. It never refreshes, samples usage or changes a resource. */
internal fun nextAccountUsageBoundary(resource: Resource?, now: Instant): Instant? {
    if (resource !is Resource.Available) return null
    return listOfNotNull(resource.validUntil, resource.usage.cycleStartedAt, resource.usage.cycleEndsAt)
        .filter { it > now }.minOrNull()
}
