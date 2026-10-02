package com.gccwebos.android.vpn

import android.content.Context
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.productionRepository

internal fun connectionRepository(context: Context): PortalRepository = productionRepository(context)
internal fun connectionNetworkProbe(): VpnNetworkProbe = HttpsVpnNetworkProbe()
