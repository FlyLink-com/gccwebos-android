package com.gccwebos.android.vpn

import android.content.Context
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.productionRepository

/** Same-process instrumentation injection only; no Intent, file or exported entry point. */
internal object ConnectionRuntimeOverrides {
    var repositoryFactory: ((Context) -> PortalRepository)? = null
    var networkProbe: VpnNetworkProbe? = null
}
internal fun connectionRepository(context: Context): PortalRepository =
    ConnectionRuntimeOverrides.repositoryFactory?.invoke(context) ?: productionRepository(context)
internal fun connectionNetworkProbe(): VpnNetworkProbe =
    ConnectionRuntimeOverrides.networkProbe ?: HttpsVpnNetworkProbe()
