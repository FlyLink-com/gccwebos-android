package com.gccwebos.android.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Finished(val result: UpdateResult) : UpdateState
}

/** UI-thread confined. Cancel and close invalidate even a misbehaving source's late result. */
class UpdateController(
    private val scope: CoroutineScope,
    private val repository: UpdateRepository,
    private val versionCode: Long,
    private val applicationId: String,
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var closed = false

    fun check() {
        if (closed || mutableState.value == UpdateState.Checking) return
        val expected = ++generation
        mutableState.value = UpdateState.Checking
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = repository.check(versionCode, applicationId)
            if (!closed && generation == expected) mutableState.value = UpdateState.Finished(result)
        }.also { it.start() }
    }
    fun dismiss() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = UpdateState.Idle
    }
    fun close() { closed = true; dismiss() }
}
