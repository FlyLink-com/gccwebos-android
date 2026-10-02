package com.gccwebos.android.portal

import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.LocalConfigGateway
import com.gccwebos.android.core.SelectionIntent
import java.io.Closeable

/** Local capability admission; single-choice convenience never authorizes cached material. */
class OneClickConfigGateway(private val manager: LocalSubscriptionManager,
    private val files: PreparedConfigStore) : LocalConfigGateway {
    override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
        if (intent is SelectionIntent.Selected) return manager.prepare(intent, files)
        val before = manager.nodeSelectionSnapshot()
        before.selected?.let { return manager.prepare(SelectionIntent.Selected(it), files) }
        val fresh = manager.prepare(SelectionIntent.Choose, files)
        if (fresh !is ConfigPreparation.SelectionRequired) return fresh
        val sole = fresh.choices.singleOrNull() ?: return fresh
        // A changed registry needs the user's selection, including a changed single-node artifact.
        if (before.choices.isNotEmpty() && before.choices.none { it.selection === sole.selection }) return fresh
        if (!manager.selectNode(sole.selection)) return manager.prepare(SelectionIntent.Choose, files)
        // Selection is UI intent only. The second fresh read still owns admission and can reject it.
        return manager.prepare(SelectionIntent.Selected(sole.selection), files)
    }
    override fun invalidatePending() = manager.invalidatePending()
    override fun clearPreparedMaterial() = files.clear()
    override fun observeClear(onClear: () -> Unit): Closeable = manager.observeClear(onClear)
}
