package com.gccwebos.android.design.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GccSemantic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one transient-message entry point ("已复制", "已保存", read errors with a 重试 action). Create it once with
 * [rememberGccMessages], provide it through [LocalGccMessages] and draw it once with [GccMessageHost].
 * A new message replaces the one on screen; the whole sentence is shown (max two lines), never clipped at a comma.
 */
@Stable
internal class GccMessages internal constructor(internal val hostState: SnackbarHostState, private val scope: CoroutineScope?) {
    /** Queues [text]. [onAction] runs only when the user taps [actionLabel]. Safe to call from any composable event. */
    fun show(text: String, kind: GccMessageKind = GccMessageKind.Info, actionLabel: String? = null,
             onAction: (() -> Unit)? = null, testTag: String? = null) {
        val whole = GccMessageRules.displayText(text)
        if (whole.isEmpty() || scope == null) return
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val visuals = GccMessageVisuals(whole, kind, actionLabel, testTag)
            // SnackbarDuration.Indefinite + our own timeout: the stock Short/Long are fixed and ignore the action case.
            val result = withTimeoutOrNull(GccMessageRules.durationMillis(kind, visuals.actionLabel != null)) {
                hostState.showSnackbar(visuals)
            }
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    internal companion object {
        /** Used when no host is provided (previews, tests): messages are dropped instead of crashing. */
        val Noop = GccMessages(SnackbarHostState(), null)
    }
}

internal val LocalGccMessages = staticCompositionLocalOf { GccMessages.Noop }

@Composable
internal fun rememberGccMessages(): GccMessages {
    val scope = rememberCoroutineScope()
    return remember(scope) { GccMessages(SnackbarHostState(), scope) }
}

private class GccMessageVisuals(override val message: String, val kind: GccMessageKind,
                                override val actionLabel: String?, val testTag: String?) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}

/** Place once, at the bottom of the app shell above the tab bar. Draws nothing while idle. */
@Composable
internal fun GccMessageHost(messages: GccMessages, modifier: Modifier = Modifier) {
    SnackbarHost(messages.hostState, modifier.fillMaxWidth().navigationBarsPadding()) { data -> GccMessageBar(data) }
}

@Composable
private fun GccMessageBar(data: SnackbarData) {
    val visuals = data.visuals as? GccMessageVisuals
    val kind = visuals?.kind ?: GccMessageKind.Info
    val dot = when (kind) {
        GccMessageKind.Info -> Brand.Cyan
        GccMessageKind.Success -> Brand.Green
        GccMessageKind.Error -> GccSemantic.Danger
    }
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
        .semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            // Kind is also spoken, not only coloured.
            GccMessageRules.accessibilityPrefix(kind).takeIf { it.isNotEmpty() }?.let { contentDescription = it + data.visuals.message }
        }
        .then(if (visuals?.testTag != null) Modifier.testTag(visuals.testTag) else Modifier.testTag("gcc-message")),
        shape = RoundedCornerShape(14.dp), color = Brand.SurfaceRaised, contentColor = Brand.Text,
        border = BorderStroke(1.dp, Brand.GlassBorder.copy(alpha = .30f)), shadowElevation = 8.dp) {
        Row(Modifier.padding(start = 16.dp, end = if (data.visuals.actionLabel != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium,
                maxLines = GccMessageRules.MaxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            data.visuals.actionLabel?.let { label ->
                TextButton(data::performAction, modifier = Modifier.heightIn(min = 48.dp).testTag("gcc-message-action")) {
                    Text(label, color = Brand.Cyan)
                }
            }
        }
    }
}
