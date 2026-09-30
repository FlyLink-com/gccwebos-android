package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource

/** Shell-owned state: leaving a destination, refreshing or recomposing must not repeat this prompt. */
internal data class ResourceGuidanceState(
    val accountKey: String? = null,
    val sessionGeneration: Long? = null,
    val shown: Boolean = false,
    val visible: Boolean = false,
) {
    fun reconcile(
        authenticated: Boolean,
        accountKey: String,
        sessionGeneration: Long,
        resource: Resource?,
        busy: Boolean,
        error: String?,
        onConnectionPage: Boolean,
    ): ResourceGuidanceState {
        if (!authenticated) return ResourceGuidanceState()
        val session = if (this.accountKey == accountKey && this.sessionGeneration == sessionGeneration) this
            else ResourceGuidanceState(accountKey = accountKey, sessionGeneration = sessionGeneration)
        val confirmedNone = resource == Resource.None && !busy && error == null && onConnectionPage
        if (!confirmedNone) return session.copy(visible = false)
        return if (session.shown) session else session.copy(shown = true, visible = true)
    }

    fun dismiss(): ResourceGuidanceState = copy(visible = false)
}
