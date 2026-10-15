package com.gccwebos.android.design.dialog

/*
 * Pure rules for the shared dialog system. No Compose, no Android: everything here is unit-tested on the JVM.
 * The composables in GccDialog.kt / GccSheet.kt / GccMenu.kt / GccMessageHost.kt only apply these results.
 */

/** Visual role of a dialog. [Danger] turns the primary action red and tints the icon badge red. */
internal enum class GccDialogTone { Neutral, Danger }

/** Built-in badge glyphs, drawn by the component itself (no icon-font or icon-library dependency). */
internal enum class GccDialogIcon { Info, Warning, Success, Danger }

/** How the (up to three) actions are laid out. [Stacked] puts the primary action first, full width. */
internal enum class GccDialogActionLayout { SideBySide, Stacked }

internal object GccDialogRules {
    /** Widest dialog; narrower phones keep a 24 dp gutter on each side. */
    const val MaxWidthDp = 420
    const val ScrimAlpha = .55f
    const val EnterFadeMillis = 160
    const val EnterStartScale = .96f
    /** A label wider than this many "full-width" characters cannot share a row with another action. */
    const val MaxSideBySideLabelUnits = 6.0
    const val MaxHeightFraction = .8f
    const val SheetMaxHeightFraction = .85f

    /** CJK / full-width glyphs count 1, everything else .5, so "OK" and "确认购买" are measured fairly. */
    fun labelUnits(label: String): Double {
        var units = 0.0
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            units += if (cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF ||
                cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF) 1.0 else .5
            i += Character.charCount(cp)
        }
        return units
    }

    /**
     * One or two short actions sit side by side; three actions, or any long label, stack vertically
     * (primary first) so no label is ever squeezed or wrapped.
     */
    fun actionLayout(labels: List<String>): GccDialogActionLayout = when {
        labels.size >= 3 -> GccDialogActionLayout.Stacked
        labels.size == 2 && labels.any { labelUnits(it) > MaxSideBySideLabelUnits } -> GccDialogActionLayout.Stacked
        else -> GccDialogActionLayout.SideBySide
    }

    /**
     * Entrance: 160 ms fade plus a .96 -> 1 scale. With "remove animations" / reduce motion the dialog is simply
     * there at full opacity on the first frame (no scale, no delay), matching the rest of the app.
     */
    fun enter(motionEnabled: Boolean): GccDialogEnter =
        if (motionEnabled) GccDialogEnter(EnterFadeMillis, EnterStartScale) else GccDialogEnter(0, 1f)

    /** Dialogs and sheets that hold secrets must keep blocking screenshots / recents thumbnails. */
    fun usesSecureWindow(secure: Boolean): Boolean = secure

    /** The cap for scrolling content; the action bar is never part of what scrolls. */
    fun maxContentHeightDp(screenHeightDp: Int, fraction: Float = MaxHeightFraction): Int =
        (screenHeightDp * fraction).toInt().coerceAtLeast(240)
}

internal data class GccDialogEnter(val fadeMillis: Int, val startScale: Float) {
    val animated: Boolean get() = fadeMillis > 0
}

/** Messages must read in full: no clipping at the first comma (the three old snackbar hosts did that). */
internal object GccMessageRules {
    const val MaxLines = 2
    const val ShortMillis = 3_000L
    const val WithActionMillis = 6_000L
    const val ErrorMillis = 5_000L

    /** Whole text, whitespace tidied, never truncated at "，" or "；". */
    fun displayText(raw: String): String = raw.replace(Regex("\\s*\\n\\s*"), " ").trim()

    fun durationMillis(kind: GccMessageKind, hasAction: Boolean): Long = when {
        hasAction -> WithActionMillis
        kind == GccMessageKind.Error -> ErrorMillis
        else -> ShortMillis
    }

    /** Spoken prefix so the kind is not conveyed by the dot colour alone. */
    fun accessibilityPrefix(kind: GccMessageKind): String = when (kind) {
        GccMessageKind.Info -> ""
        GccMessageKind.Success -> "成功："
        GccMessageKind.Error -> "出错了："
    }
}

internal enum class GccMessageKind { Info, Success, Error }
