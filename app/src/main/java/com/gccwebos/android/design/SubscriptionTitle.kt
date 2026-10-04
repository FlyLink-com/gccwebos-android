package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource

/** Display names for exact public resource plan IDs; never entitlement or connection authority. */
internal fun subscriptionTitle(resource: Resource?): String? = when ((resource as? Resource.Available)?.plan) {
    "monthly_30d_50gb" -> "Go 订阅"
    "monthly_30d_100gb" -> "Plus 订阅"
    "yearly_365d_200gb" -> "年 Plus 订阅"
    else -> null
}
