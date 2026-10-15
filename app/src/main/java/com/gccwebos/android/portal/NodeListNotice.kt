package com.gccwebos.android.portal

/** What the user can do from an empty / failed node list. */
internal enum class NodeListAction { None, Retry, Import, Account }

/** Display-only state of a node list that has no rows to show. Never a connection decision. */
internal data class NodeListNotice(
    val loading: Boolean,
    val message: String,
    val action: NodeListAction,
    val actionLabel: String? = null,
)

/**
 * Single mapping from the subscription status to the user-facing loading / empty / error copy and recovery action.
 * Returns null whenever rows exist: rows are never hidden behind a notice.
 */
internal fun nodeListNotice(
    status: LocalSubscriptionStatus,
    busy: Boolean,
    hasRows: Boolean,
    hasCapability: Boolean,
    authenticated: Boolean,
    canImport: Boolean,
    detail: String? = null,
): NodeListNotice? {
    if (hasRows) return null
    if (busy) return NodeListNotice(true, "正在加载节点…", NodeListAction.None)
    return when (status) {
        LocalSubscriptionStatus.READY ->
            if (canImport) NodeListNotice(false, "该配置没有可用节点", NodeListAction.Import, "导入配置")
            else NodeListNotice(false, "该配置没有可用节点", NodeListAction.Retry, "重新加载")
        LocalSubscriptionStatus.MISSING -> when {
            hasCapability -> NodeListNotice(false, "节点尚未加载", NodeListAction.Retry, "重新加载")
            !authenticated -> NodeListNotice(false, "登录后即可获取飞连VPN节点，或导入自己的配置",
                NodeListAction.Account, "登录")
            canImport -> NodeListNotice(false, "暂无可用配置", NodeListAction.Import, "导入配置")
            else -> NodeListNotice(false, "暂无可用配置，请先订阅套餐", NodeListAction.Account, "查看套餐")
        }
        LocalSubscriptionStatus.EXPIRED ->
            NodeListNotice(false, "订阅已过期，续费或更新订阅后即可继续使用", NodeListAction.Retry, "更新订阅")
        LocalSubscriptionStatus.RETRY_REQUIRED ->
            NodeListNotice(false, detail ?: "节点加载失败，请检查网络后重试", NodeListAction.Retry, "重试")
        LocalSubscriptionStatus.INVALID_LINK ->
            NodeListNotice(false, "订阅链接已失效，请重新获取订阅", NodeListAction.Account, "重新获取")
        LocalSubscriptionStatus.UNSUPPORTED -> (
            if (canImport) NodeListNotice(false, "配置格式不受支持，请更换或重新导入配置", NodeListAction.Import, "重新导入")
            else NodeListNotice(false, "配置格式不受支持", NodeListAction.None))
        LocalSubscriptionStatus.STORAGE_ERROR ->
            NodeListNotice(false, "读取本地配置失败，请重试", NodeListAction.Retry, "重试")
    }
}
