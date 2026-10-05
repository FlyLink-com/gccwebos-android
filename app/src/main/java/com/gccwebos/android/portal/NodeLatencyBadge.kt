package com.gccwebos.android.portal

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.Brand

/** Host Ping is diagnostic information, never node admission or proxy connectivity evidence. */
@Composable
internal fun NodeLatencyBadge(observation: NodeLatencyObservation?, modifier: Modifier = Modifier) {
    val label = when (observation) {
        is NodeLatencyObservation.Success -> "${observation.milliseconds} ms"
        NodeLatencyObservation.NoReply -> "— ms"
        NodeLatencyObservation.Unavailable -> "— ms"
        NodeLatencyObservation.NetworkChanged -> "— ms"
        null -> "— ms"
    }
    Text(label, fontSize = 11.sp,
        color = if (observation is NodeLatencyObservation.Success) Brand.Green else Brand.Muted,
        modifier = modifier.semantics {
            contentDescription = if (observation is NodeLatencyObservation.Success)
                "Ping 延迟 ${observation.milliseconds} 毫秒" else "Ping 延迟 $label"
        })
}
