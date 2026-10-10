package com.gccwebos.android.portal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.Brand

/** Green tier, at or under [LatencyGoodUpToMs]. */
internal val LatencyGoodColor = Brand.Green
/** Blue tier, above 150 ms and below [LatencyModerateBelowMs]. */
internal val LatencyModerateColor = Brand.Blue
/** Orange tier, from 200 ms up to but not including [LatencySlowBelowMs]. Brand has no orange token. */
internal val LatencySlowColor = Color(0xFFFF9F43)
/** Red tier at or above 300 ms, and the timeout capsule. */
internal val LatencyPoor = Color(0xFFFF5D5D)
/** Unmeasured, unavailable, skipped, and network-change text. */
internal val LatencyNeutralColor = Brand.Muted.copy(alpha = .75f)

internal fun nodeLatencyToneColor(tone: NodeLatencyTone): Color = when (tone) {
    NodeLatencyTone.Good -> LatencyGoodColor
    NodeLatencyTone.Moderate -> LatencyModerateColor
    NodeLatencyTone.Slow -> LatencySlowColor
    NodeLatencyTone.Poor -> LatencyPoor
    NodeLatencyTone.Neutral -> LatencyNeutralColor
}

private val LatencyCapsuleShape = RoundedCornerShape(999.dp)

/** Node transport RTT is diagnostic information, never node admission or proxy request evidence. */
@Composable
internal fun NodeLatencyBadge(
    observation: NodeLatencyObservation?,
    modifier: Modifier = Modifier,
    measuring: Boolean = false,
    onRetest: (() -> Unit)? = null,
    tunnelActive: Boolean = false,
) {
    val presentation = nodeLatencyPresentation(observation, measuring, tunnelActive)
    val description = nodeLatencyContentDescription(presentation)
    val color = nodeLatencyToneColor(presentation.tone)
    val clickable = if (onRetest != null) {
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "重新测试延迟", onClick = onRetest)
    } else Modifier
    Box(
        modifier
            .then(clickable)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterEnd,
    ) {
        when {
            presentation.label == "超时" -> Surface(
                color = LatencyPoor.copy(alpha = .18f),
                contentColor = LatencyPoor,
                shape = LatencyCapsuleShape,
                modifier = Modifier.widthIn(min = 40.dp).heightIn(min = 22.dp),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Text("超时", fontSize = 10.sp, color = LatencyPoor, maxLines = 1)
                }
            }
            else -> Text(presentation.label, fontSize = 11.sp, color = color, maxLines = 1)
        }
    }
}
