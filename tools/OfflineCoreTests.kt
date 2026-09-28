import com.gccwebos.android.core.ConnectionCases
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main(args: Array<String>) {
    var failures = 0
    val entries = mutableListOf<String>()
    fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
    for (case in ConnectionCases.all) {
        val start = System.nanoTime()
        var failure: Throwable? = null
        try { runBlocking { withTimeout(5_000) { case.body(this) } } }
        catch (e: Throwable) { failure = e; failures++ }
        val elapsed = (System.nanoTime() - start) / 1e9
        println("${if (failure == null) "PASS" else "FAIL"} ${case.name} ($elapsed s)")
        if (failure != null) failure.printStackTrace(System.err)
        entries += "<testcase name=\"${xml(case.name)}\" time=\"$elapsed\">" +
            (failure?.let { "<failure message=\"${xml(it.toString())}\"/>" } ?: "") + "</testcase>"
    }
    println("TOTAL ${ConnectionCases.all.size}; PASS ${ConnectionCases.all.size - failures}; FAIL $failures")
    if (args.isNotEmpty()) File(args[0]).writeText(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<testsuite name=\"offline-core-diagnostics-not-android-gradle\" tests=\"${ConnectionCases.all.size}\" failures=\"$failures\">\n" +
        entries.joinToString("\n") + "\n</testsuite>\n"
    )
    check(failures == 0) { "$failures cases failed" }
}
