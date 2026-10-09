package com.gccwebos.android.portal

import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.ConnectionChoice
import com.gccwebos.android.core.LocalConfigGateway
import com.gccwebos.android.core.SelectionIntent
import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** One explicit connection intent; random choice never authorizes cached material. */
class OneClickConfigGateway(private val manager: LocalSubscriptionManager,
    private val files: PreparedConfigStore,
    private val addressPreparer: ConfigAddressPreparer = LiteralConfigAddressPreparer,
    private val chooseUnselected: (List<ConnectionChoice>) -> ConnectionChoice? = { choices -> choices.filterNot { it.info }.randomOrNull() }) : LocalConfigGateway {
    override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
        if (intent is SelectionIntent.Selected) return manager.prepare(intent, files, addressPreparer)
        val before = manager.nodeSelectionAttempt()
        before.snapshot.selected?.let { return manager.prepareAtEpoch(SelectionIntent.Selected(it), files, addressPreparer, before.epoch) }
        val fresh = manager.prepareAtEpoch(SelectionIntent.Choose, files, addressPreparer, before.epoch)
        if (fresh !is ConfigPreparation.SelectionRequired) return fresh
        // A changed registry needs a new explicit intent; do not silently follow replacement material.
        if (before.snapshot.choices.isNotEmpty() && !sameChoices(before.snapshot.choices, fresh.choices)) return fresh
        val choice = chooseUnselected(fresh.choices)?.takeIf { !it.info } ?: return fresh
        val current = manager.nodeSelectionAttempt()
        // A successful discovery takes exactly one epoch in refreshLocked. Later user choices,
        // refreshes or cancellation must cancel this intent, not grant it a replacement epoch.
        if (current.epoch != before.epoch + 1) throw CancellationException("Subscription selection changed")
        if (current.snapshot.status != LocalSubscriptionStatus.READY || !sameChoices(current.snapshot.choices, fresh.choices)) return fresh
        val epoch = manager.selectNodeIfUnchanged(choice.selection, current)
            ?: throw CancellationException("Subscription selection changed")
        // Selection is UI intent only. The second fresh read still owns admission and can reject it.
        return manager.prepareAtEpoch(SelectionIntent.Selected(choice.selection), files, addressPreparer, epoch)
    }
    override fun invalidatePending() = manager.invalidatePending()
    override fun clearPreparedMaterial() = files.clear()
    override fun observeClear(onClear: () -> Unit): Closeable = manager.observeClear(onClear)

    /** Provider notices are rewritten on every download and are never selectable, so they do not
     * make a registry different; every selectable entry must keep its opaque identity. */
    private fun sameChoices(left: List<ConnectionChoice>, right: List<ConnectionChoice>): Boolean =
        left.size == right.size && left.indices.all {
            left[it].info == right[it].info && (left[it].info || left[it].selection === right[it].selection)
        }
}
