package com.gccwebos.android.portal

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {
    private val lines = mutableListOf<Pair<String, String>>()
    private val originalWriter = DebugLog.writer
    private val originalEnabled = DebugLog.enabled

    private fun capture(enabled: Boolean = true) {
        DebugLog.enabled = enabled
        DebugLog.writer = { tag, line -> lines += tag to line }
    }

    @After fun restore() {
        DebugLog.writer = originalWriter
        DebugLog.enabled = originalEnabled
    }

    @Test fun everyLineIsRedactedBeforeTheWriter() {
        capture()
        DebugLog.i("GCCPay", "started url=https://pay.example.invalid/cashier?order=123&token=abc")
        DebugLog.i("GCCConnectionEngine", "phase=Bearer abcdefghijklmnop")
        assertEquals("GCCPay", lines[0].first)
        assertFalse(lines[0].second.contains("pay.example.invalid"))
        assertFalse(lines[0].second.contains("order=123"))
        assertFalse(lines[1].second.contains("abcdefghijklmnop"))
        assertTrue(lines.all { it.second.contains("[REDACTED]") })
    }

    @Test fun safePhaseMarksPassThroughUnchanged() {
        capture()
        DebugLog.i("GCCConnectionEngine", "phase=owner-working-PREPARE")
        assertEquals(listOf("GCCConnectionEngine" to "phase=owner-working-PREPARE"), lines)
    }

    @Test fun disabledBuildsNeverFormatOrWrite() {
        capture(enabled = false)
        var built = false
        DebugLog.lazy("GCCPay") { built = true; "x" }
        DebugLog.i("GCCPay", "y")
        assertFalse(built)
        assertTrue(lines.isEmpty())
    }

    @Test fun aWriterFailureNeverEscapes() {
        DebugLog.enabled = true
        DebugLog.writer = { _, _ -> throw IllegalStateException("logcat unavailable") }
        DebugLog.i("GCCPay", "phase=x") // must not throw
    }

    /** Only DebugLog may talk to android.util.Log, so no call site can skip redaction. */
    @Test fun noMainSourceCallsAndroidLogDirectly() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && it.name != "DebugLog.kt" }
            .filter { f -> f.readText().let { it.contains("android.util.Log") || Regex("""\bLog\.[a-z]+\(""").containsMatchIn(it) } }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
