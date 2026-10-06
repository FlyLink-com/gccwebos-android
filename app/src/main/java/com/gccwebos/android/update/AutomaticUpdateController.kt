package com.gccwebos.android.update

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val AUTOMATIC_UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

/** Public update metadata only. Implementations must serialize read/modify/write across instances. */
internal interface AutomaticUpdateStore {
    suspend fun tryBeginCheck(now: Long, interval: Long): Boolean
    suspend fun wasPrompted(code: Long): Boolean
    suspend fun recordPrompt(code: Long)
}

/** A clock correction or damaged old timestamp permits one new attempt, which replaces it. */
internal fun automaticCheckDue(last: Long, now: Long, interval: Long): Boolean =
    now > 0 && (last <= 0 || last > now || now - last >= interval)

/** Independent, UI-thread-confined presentation; it never owns account or VPN operations. */
internal class AutomaticUpdateController(
    private val scope: CoroutineScope,
    private val repository: UpdateRepository,
    private val store: AutomaticUpdateStore,
    private val versionCode: Long,
    private val applicationId: String,
    private val debuggable: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutablePrompt = MutableStateFlow<UpdateResult.NewVersion?>(null)
    val prompt: StateFlow<UpdateResult.NewVersion?> = mutablePrompt.asStateFlow()
    private val enabled = !debuggable && applicationId == OfficialReleases.RELEASE_APPLICATION_ID
    private var foreground = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    private var offer: UpdateResult.NewVersion? = null
    private var manualReaders = 0
    private var shownThisSession = 0L

    fun onForeground() {
        if (closed || !enabled) return
        foreground = true
        publish()
        if (offer != null || job?.isActive == true) return
        val expected = ++generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(ioDispatcher) {
                    if (!store.tryBeginCheck(clock(), AUTOMATIC_UPDATE_INTERVAL_MILLIS)) return@withContext null
                    val checked = repository.check(versionCode, applicationId) as? UpdateResult.NewVersion
                    checked?.takeUnless { store.wasPrompted(it.release.versionCode) }
                }
                coroutineContext.ensureActive()
                if (!closed && foreground && generation == expected &&
                    result != null && result.release.versionCode > shownThisSession) {
                    offer = result
                    publish()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Optional update checks never expose a retry/error UI or interrupt other work.
            } finally {
                if (generation == expected) job = null
            }
        }.also { it.start() }
    }

    fun onBackground() {
        foreground = false
        generation++
        job?.cancel(); job = null
        // A displayed version stays dismissed across foreground visits, including Home/back.
        if (offer?.release?.versionCode?.let { it <= shownThisSession } == true) offer = null
        publish()
    }

    /** Called only after Compose has rendered this particular prompt, never on HTTP completion. */
    fun promptShown(code: Long) {
        if (mutablePrompt.value?.release?.versionCode == code) recordShown(code)
    }

    /** Manual checks remain explicit and may show the same version again. */
    fun manualPromptShown(code: Long) {
        if (!closed && enabled && foreground && manualReaders > 0) {
            if (offer?.release?.versionCode?.let { it <= code } == true) offer = null
            recordShown(code)
            publish()
        }
    }

    private fun recordShown(code: Long) {
        if (closed || !enabled || code <= versionCode || code <= shownThisSession) return
        shownThisSession = code
        scope.launch {
            try { withContext(ioDispatcher) { store.recordPrompt(code) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Best effort: never make dismissal depend on storage. */ }
        }
    }

    fun dismiss() {
        mutablePrompt.value?.release?.versionCode?.let(::recordShown)
        offer = null
        publish()
    }

    fun suppressForManualCheck(): Closeable {
        manualReaders++
        publish()
        var released = false
        return Closeable {
            if (!released) { released = true; manualReaders--; publish() }
        }
    }

    private fun publish() {
        mutablePrompt.value = offer.takeIf { enabled && !closed && foreground && manualReaders == 0 }
    }

    fun close() { closed = true; onBackground(); offer = null }
}
