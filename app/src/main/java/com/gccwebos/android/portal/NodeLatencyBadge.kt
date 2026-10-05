package com.gccwebos.android.portal

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.Brand

/** Node transport RTT is diagnostic information, never node admission or proxy request evidence. */
@Composable
internal fun NodeLatencyBadge(observation: NodeLatencyObservation?, modifier: Modifier = Modifier) {
    val label = when (observation) {
        is NodeLatencyObservation.Success -> "${observation.milliseconds} ms"
        NodeLatencyObservation.NoReply -> "超时"
        NodeLatencyObservation.Unavailable -> "不可测"
        NodeLatencyObservation.NetworkChanged -> "网络变化"
        null -> "— ms"
    }
    Text(label, fontSize = 11.sp,
        color = if (observation is NodeLatencyObservation.Success) Brand.Green else Brand.Muted,
        modifier = modifier.semantics {
            contentDescription = if (observation is NodeLatencyObservation.Success)
                "节点延迟 ${observation.milliseconds} 毫秒" else "节点延迟：${if (observation == null) "未测试" else label}"
        })
}
