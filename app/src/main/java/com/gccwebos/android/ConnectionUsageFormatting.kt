package com.gccwebos.android

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

private val fullAccountTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val compactAccountDate = DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.ROOT)

/** Shortens only the existing account presentation; the full timestamp remains available in details. */
internal fun connectionDateSummary(value: String): String = compactDate(value) ?: value

internal fun connectionCycleSummary(value: String): String {
    val boundaries = value.split(" — ")
    if (boundaries.size != 2) return value
    val start = compactDate(boundaries[0]) ?: return value
    val end = compactDate(boundaries[1]) ?: return value
    return "$start — $end"
}

private fun compactDate(value: String): String? = try {
    // Preserve the server snapshot's displayed zone and calendar day, without converting through UTC.
    OffsetDateTime.parse(value, fullAccountTime).format(compactAccountDate)
} catch (_: DateTimeParseException) {
    null
}
