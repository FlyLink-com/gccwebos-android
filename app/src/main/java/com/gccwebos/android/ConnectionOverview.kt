package com.gccwebos.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.ImportStatus
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import com.gccwebos.android.portal.NodeLatencyBadge
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.delay

internal enum class ConnectionVisualTone { Neutral, Connecting, Connected, Error }
internal enum class ConnectionPhase { Unavailable, Disconnected, Connecting, Connected, Disconnecting, Failed }
internal enum class ConnectionFailure { NetworkUnavailable, ResourceUnavailable, ReadFailed, Other }
internal enum class ConnectionPowerAction { None, Connect, Cancel, Disconnect }

/** Read-only UI input from a future controller, never inferred from saved configuration.
 * The controller must publish a new snapshot/revision when it accepts or rejects a command.
 * Defaults deliberately expose no working engine, cancellation or disconnect action.
 */
internal data class ConnectionControlState(
    val phase: ConnectionPhase = ConnectionPhase.Unavailable,
    val failure: ConnectionFailure? = null,
    val canConnect: Boolean = false,
    val canCancel: Boolean = false,
    val canDisconnect: Boolean = false,
    val commandPending: Boolean = false,
    val revision: Long = 0,
)

internal data class ConnectionOverviewPresentation(
    val status: LocalSubscriptionStatus,
    val title: String,
    val connectionText: String,
    val tone: ConnectionVisualTone,
    val countdown: String?,
    val usageText: String = "暂不可用",
    val action: ConnectionPowerAction = ConnectionPowerAction.None,
    val powerLabel: String = "连接暂不可用",
)

/** Account usage is absent: it cannot prove usage belongs to this local capability.
 * READY is configuration evidence only; it can never produce Connecting or Connected.
 */
internal fun connectionOverviewPresentation(
    snapshot: LocalSubscriptionSnapshot,
    now: Instant,
    control: ConnectionControlState = ConnectionControlState(),
): ConnectionOverviewPresentation {
    val status = if (snapshot.status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        snapshot.validUntil?.let { it <= now } == true) LocalSubscriptionStatus.EXPIRED else snapshot.status
    val localValidity = snapshot.sourceKind == com.gccwebos.android.portal.ManualSourceKind.ImportedLocal &&
        snapshot.validity == com.gccwebos.android.portal.LocalSourceValidity.LocalNoServerExpiry && snapshot.validUntil == null
    val externalSource = snapshot.sourceKind == com.gccwebos.android.portal.ManualSourceKind.ExternalRemote
    val externalValidity = externalSource && when (val validity = snapshot.validity) {
        com.gccwebos.android.portal.LocalSourceValidity.ExternalUnreported -> snapshot.validUntil == null
        is com.gccwebos.android.portal.LocalSourceValidity.ExternalExpiry ->
            validity.until == snapshot.validUntil && validity.until > now
        else -> false
    }
    val confirmedReady = status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        (if (externalSource) externalValidity else localValidity || snapshot.validUntil?.let { it > now } == true)
    val localError = status in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.RETRY_REQUIRED,
        LocalSubscriptionStatus.INVALID_LINK, LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR)
    val connectionText = when (control.phase) {
        ConnectionPhase.Unavailable, ConnectionPhase.Disconnected, ConnectionPhase.Failed -> "未连接"
        ConnectionPhase.Connecting -> "连接中"
        ConnectionPhase.Connected -> "已连接"
        ConnectionPhase.Disconnecting -> "断开中"
    }
    val title = when {
        localError -> when (status) {
            LocalSubscriptionStatus.EXPIRED -> "订阅已过期"
            LocalSubscriptionStatus.RETRY_REQUIRED -> "读取失败"
            LocalSubscriptionStatus.INVALID_LINK -> "订阅资源不可用"
            LocalSubscriptionStatus.UNSUPPORTED -> "配置暂不支持"
            LocalSubscriptionStatus.STORAGE_ERROR -> "本地存储异常"
            else -> error("Local error requires an explicit local status")
        }
        control.phase == ConnectionPhase.Failed -> when (control.failure) {
            ConnectionFailure.NetworkUnavailable -> "无网络"
            ConnectionFailure.ResourceUnavailable -> "订阅资源不可用"
            ConnectionFailure.ReadFailed -> "读取失败"
            ConnectionFailure.Other, null -> "连接异常"
        }
        status == LocalSubscriptionStatus.MISSING -> "未配置"
        !confirmedReady -> "订阅待确认"
        control.phase == ConnectionPhase.Unavailable -> "连接暂不可用"
        localValidity -> "本地配置"
        externalSource -> "第三方订阅"
        else -> ""
    }
    val tone = when {
        localError || control.phase == ConnectionPhase.Failed -> ConnectionVisualTone.Error
        control.phase == ConnectionPhase.Connecting || control.phase == ConnectionPhase.Disconnecting -> ConnectionVisualTone.Connecting
        control.phase == ConnectionPhase.Connected -> ConnectionVisualTone.Connected
        else -> ConnectionVisualTone.Neutral
    }
    val countdown = when {
        status == LocalSubscriptionStatus.EXPIRED -> "已到期"
        confirmedReady && externalSource && snapshot.validUntil == null -> "未提供有效期"
        confirmedReady -> snapshot.validUntil?.let { remainingValidity(it, now) }
        else -> null
    }
    val action = if (control.commandPending) ConnectionPowerAction.None else when (control.phase) {
        ConnectionPhase.Disconnected, ConnectionPhase.Failed ->
            if (control.canConnect && confirmedReady) ConnectionPowerAction.Connect else ConnectionPowerAction.None
        ConnectionPhase.Connecting -> if (control.canCancel) ConnectionPowerAction.Cancel else ConnectionPowerAction.None
        ConnectionPhase.Connected -> if (control.canDisconnect) ConnectionPowerAction.Disconnect else ConnectionPowerAction.None
        ConnectionPhase.Unavailable, ConnectionPhase.Disconnecting -> ConnectionPowerAction.None
    }
    val powerLabel = when (action) {
        ConnectionPowerAction.Connect -> if (control.phase == ConnectionPhase.Failed) "重试连接" else "连接"
        ConnectionPowerAction.Cancel -> "取消连接"
        ConnectionPowerAction.Disconnect -> "断开连接"
        ConnectionPowerAction.None -> when (control.phase) {
            ConnectionPhase.Connecting -> "连接中"
            ConnectionPhase.Connected -> "已连接"
            ConnectionPhase.Disconnecting -> "断开中"
            else -> "连接暂不可用"
        }
    }
    return ConnectionOverviewPresentation(status, title, connectionText, tone, countdown,
        action = action, powerLabel = powerLabel)
}

/** The idle owner may offer preparation before a local capability exists. That alone
 * must not hide account guidance; live/pending commands always retain their own UI. */
internal fun showsConnectionCommand(presentation: ConnectionOverviewPresentation, control: ConnectionControlState): Boolean =
    control.phase in setOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting) ||
        control.commandPending || presentation.action != ConnectionPowerAction.None

/** Account preparation is a separate explicit action, never a VPN command or local readiness. */
internal enum class ConnectionSetupAction { None, AccountChoice, Plans, SyncSubscription }
internal data class ConnectionSetupGuidance(
    val action: ConnectionSetupAction,
    val label: String,
    val enabled: Boolean,
    val hint: String? = null,
)

internal fun connectionSetupGuidance(
    authenticated: Boolean,
    verifiedUsage: Boolean,
    resource: Resource?,
    snapshot: LocalSubscriptionSnapshot,
    now: Instant,
    operable: Boolean,
    busy: Boolean = false,
    refreshFailed: Boolean = false,
): ConnectionSetupGuidance {
    if (!authenticated) return ConnectionSetupGuidance(ConnectionSetupAction.AccountChoice, "开通飞连vpn", operable && !busy)
    if (busy) return ConnectionSetupGuidance(ConnectionSetupAction.None, "准备中", false)
    if (!verifiedUsage || refreshFailed) return ConnectionSetupGuidance(ConnectionSetupAction.None, "重试", false)
    if (resource == Resource.None) return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "开通飞连vpn", operable)
    if (resource !is Resource.Available) return ConnectionSetupGuidance(ConnectionSetupAction.None, "重试", false)
    if (resource.validUntil <= now) return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "查看套餐", operable, "订阅已到期")
    if (resource.usage.status in setOf("measured", "measured_zero") && resource.usage.remainingBytes == 0L)
        return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "查看套餐", operable, "本期流量已用尽")
    if (snapshot.status == LocalSubscriptionStatus.MISSING)
        return ConnectionSetupGuidance(ConnectionSetupAction.SyncSubscription, "一键连接", operable, "本机尚无配置")
    return ConnectionSetupGuidance(ConnectionSetupAction.None, "一键连接", false)
}

/** One explicit intent. Re-evaluate the current authenticated/busy context before calling this. */
internal fun dispatchConnectionSetup(
    guidance: ConnectionSetupGuidance,
    onAccountChoice: () -> Unit,
    onPlans: () -> Unit,
    onSync: () -> Unit,
): Boolean {
    if (!guidance.enabled) return false
    when (guidance.action) {
        ConnectionSetupAction.AccountChoice -> onAccountChoice()
        ConnectionSetupAction.Plans -> onPlans()
        ConnectionSetupAction.SyncSubscription -> onSync()
        ConnectionSetupAction.None -> return false
    }
    return true
}

internal fun connectionSetupMessage(status: ImportStatus?, error: String?, busy: Boolean): String? = when {
    busy -> "正在准备配置"
    error != null -> error
    status == ImportStatus.WAITING_DELIVERY -> "订阅交付暂不可领取，请稍后重试或联系支持"
    status == ImportStatus.RECONCILIATION -> "套餐正在核对"
    status == ImportStatus.UNSUPPORTED_CONFIGURATION -> "配置暂不支持"
    status == ImportStatus.EXPIRED -> "订阅已到期"
    status == ImportStatus.QUOTA_EXHAUSTED -> "本期流量已用尽"
    else -> null
}

/** A completion cannot cross page disposal or the account generation which requested it. */
internal class ConnectionSetupUiLease(private val authGeneration: Long) {
    private var mounted = true
    fun close() { mounted = false }
    fun isCurrent(generation: Long): Boolean = mounted && authGeneration == generation
}

/** Welcome and account overview are independent of local configuration and engine admission. */
@Composable
internal fun ConnectionOverview(
    account: AccountController,
    openAccount: () -> Unit,
    openPlans: () -> Unit,
    openNodes: () -> Unit,
    control: ConnectionControlState = ConnectionControlState(),
    onPowerAction: ((ConnectionPowerAction) -> Unit)? = null,
    openRegistration: () -> Unit = openAccount,
    nowSource: () -> Instant = Instant::now,
) {
    val snapshot = account.localSubscription
    val usage by account.accountUsage.state.collectAsState()
    val clock by rememberUpdatedState(nowSource)
    val latestControl by rememberUpdatedState(control)
    val latestPowerAction by rememberUpdatedState(onPowerAction)
    val preparationLease = remember(account, account.authSessionGeneration) { ConnectionSetupUiLease(account.authSessionGeneration) }
    var preparationRequest by remember(account, account.authSessionGeneration) { mutableStateOf<Long?>(null) }
    DisposableEffect(preparationLease) { onDispose {
        preparationLease.close()
        preparationRequest?.let(account::cancelConnectionPreparation)
    } }
    var preparationHint by remember(account, account.authSessionGeneration) { mutableStateOf<String?>(null) }
    val now by produceState(clock(), snapshot.validUntil) {
        value = clock()
        while (snapshot.validUntil != null) { delay(1_000); value = clock() }
    }
    // The account card wakes only at actual cycle/service boundaries, without another GET.
    val usageNow by produceState(clock(), usage.resource, account.authSessionGeneration, usage.verifying) {
        while (true) {
            value = clock()
            val boundary = nextAccountUsageBoundary(usage.resource, value) ?: break
            delay((Duration.between(value, boundary).toMillis() + 1).coerceAtLeast(1))
        }
    }
    var synchronizedNodeGeneration by remember(account) { mutableStateOf<Long?>(null) }
    LaunchedEffect(account, account.authSessionGeneration, snapshot, now) {
        account.nodeSelection.expireIfNeeded() // Memory display only; admission still validates the source.
        account.nodeLatency.prune()
        synchronizedNodeGeneration = account.authSessionGeneration
    }
    val nodeSnapshot = account.nodeSelection.snapshot
    val selectedNode = nodeSnapshot.choices.firstOrNull { it.selection === nodeSnapshot.selected }?.takeIf {
        synchronizedNodeGeneration == account.authSessionGeneration && !account.nodeSelection.busy &&
            nodeSnapshot.status == LocalSubscriptionStatus.READY && nodeSnapshot.validUntil?.let { until -> until > now } != false
    }
    val local = connectionOverviewPresentation(snapshot, now, control)
    LaunchedEffect(account, selectedNode?.selection, account.busy, account.restoring,
        account.foregroundCheckPending, account.nodeLatency.foreground) {
        if (selectedNode != null && account.nodeLatency.foreground && !account.busy && !account.restoring &&
            !account.foregroundCheckPending && !account.clearing) while (true) {
            account.nodeLatency.pruneExpired()
            account.nodeLatency.ensureMeasured(selectedNode.selection)
            delay(1_000)
        }
    }
    // A retained name is display-only while the selection controller reads. Its old opaque
    // identity never reaches Ping or connection admission through this fallback.
    val displayedNode = selectedNode ?: nodeSnapshot.choices.firstOrNull { it.selection === nodeSnapshot.selected }?.takeIf {
        account.nodeSelection.busy && synchronizedNodeGeneration == account.authSessionGeneration &&
            nodeSnapshot.status == LocalSubscriptionStatus.READY && nodeSnapshot.validUntil?.let { until -> until > now } != false
    }
    DisposableEffect(account) { onDispose { account.nodeLatency.cancel() } }
    val operable = !account.busy && !account.manualImport.busy && !account.nodeSelection.busy && !account.restoring &&
        !account.foregroundCheckPending && !account.clearing && !usage.busy
    val verifiedUsage = account.authenticated && usage.authenticated && !usage.verifying
    val display = accountUsagePresentation(if (verifiedUsage) usage.resource else null, usageNow, usage.notUpdated)
    val setup = connectionSetupGuidance(account.authenticated, verifiedUsage, usage.resource, snapshot, usageNow,
        operable, account.busy, refreshFailed = usage.error != null)
    var showGuestChoice by remember(account.authSessionGeneration) { mutableStateOf(false) }
    var acceptedCommand by remember(control, account) { mutableStateOf(false) }
    // Keep service control authoritative without interpreting an idle preparation capability as local readiness.
    val controlMode = showsConnectionCommand(local, control)
    val manualSource = snapshot.sourceKind in setOf(com.gccwebos.android.portal.ManualSourceKind.ImportedLocal,
        com.gccwebos.android.portal.ManualSourceKind.ImportedRemote, com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)
    val usageRecovery = !manualSource && account.authenticated && (usage.error != null ||
        display.state in setOf(AccountUsageState.Unknown, AccountUsageState.Reconciliation))
    val recoverBeforeConnect = controlMode && local.action == ConnectionPowerAction.Connect && usageRecovery
    val localReadRecovery = account.authenticated && verifiedUsage && snapshot.hasCapability &&
        local.status in setOf(LocalSubscriptionStatus.RETRY_REQUIRED, LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR)
    val controlEnabled = onPowerAction != null && !acceptedCommand && local.action != ConnectionPowerAction.None &&
        (local.action != ConnectionPowerAction.Connect || (!account.clearing && !account.manualImport.busy &&
            !account.nodeSelection.busy && (if (manualSource) !account.busy && !account.restoring && !account.foregroundCheckPending
            else !account.authenticated || (operable && verifiedUsage && !usageRecovery))))
    Column(Modifier.fillMaxWidth().testTag("connection-overview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandCard(Modifier.testTag("connection-account-surface"), contentPadding = PaddingValues(18.dp, 16.dp, 18.dp, 16.dp)) {
            // Each section already owns its spacing; avoid stacking the generic card's row gaps.
            Column(Modifier.fillMaxWidth()) {
                ConnectionWelcome()
                ConnectionStatusRow(local, display.planText.takeIf { account.authenticated },
                    activePlan = verifiedUsage && connectionPlanIsActive(display), modifier = Modifier.padding(top = 2.dp))
                ConnectionUsageCard(display, authenticated = account.authenticated,
                    modifier = Modifier.padding(top = 5.dp),
                    onRefresh = account::refreshAccountUsage,
                    loading = account.busy || account.nodeSelection.busy || usage.busy || usage.verifying || account.restoring || account.foregroundCheckPending,
                    error = usage.error, refreshEnabled = verifiedUsage && operable,
                    primaryLabel = setup.label,
                    primaryEnabled = setup.enabled || (localReadRecovery && operable),
                    primaryHint = setup.hint ?: if (control.phase != ConnectionPhase.Unavailable && account.authenticated &&
                        display.state == AccountUsageState.Available) local.title.ifBlank { local.powerLabel } else null,
                    onPrimaryAction = primary@{
                        val currentUsage = account.accountUsage.state.value
                        val currentVerified = account.authenticated && currentUsage.authenticated && !currentUsage.verifying
                        val currentOperable = !account.busy && !account.nodeSelection.busy && !account.restoring && !account.foregroundCheckPending &&
                            !account.clearing && !currentUsage.busy
                        val currentTime = clock()
                        val currentLocal = connectionOverviewPresentation(account.localSubscription, currentTime, latestControl)
                        if (!acceptedCommand && !showsConnectionCommand(currentLocal, latestControl) &&
                            currentVerified && currentOperable && account.localSubscription.hasCapability &&
                            currentLocal.status in setOf(LocalSubscriptionStatus.RETRY_REQUIRED,
                                LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR)) {
                            account.refreshSubscription()
                            return@primary // Read-only recovery never completes with an automatic power action.
                        }
                        if (!acceptedCommand && !showsConnectionCommand(currentLocal, latestControl))
                            dispatchConnectionSetup(connectionSetupGuidance(account.authenticated, currentVerified,
                                currentUsage.resource, account.localSubscription, currentTime, currentOperable, account.busy,
                                refreshFailed = currentUsage.error != null),
                                { showGuestChoice = true }, openPlans, {
                                    preparationHint = null
                                    preparationRequest = account.prepareForConnection {
                                        preparationRequest = null
                                        // This is the same explicit request's completion; account.busy is still owned by it.
                                        if (preparationLease.isCurrent(account.authSessionGeneration) && !account.restoring &&
                                            !account.foregroundCheckPending && !account.clearing && !acceptedCommand) {
                                            val completedUsage = account.accountUsage.state.value
                                            val completedResource = completedUsage.resource as? Resource.Available
                                            val completedTime = clock()
                                            val entitlementCurrent = account.authenticated && completedUsage.authenticated &&
                                                !completedUsage.verifying && completedUsage.error == null && completedResource != null &&
                                                completedResource.validUntil > completedTime &&
                                                !(completedResource.usage.status in setOf("measured", "measured_zero") &&
                                                    completedResource.usage.remainingBytes == 0L)
                                            val completedLocal = connectionOverviewPresentation(account.localSubscription, completedTime, latestControl)
                                            if (entitlementCurrent && completedLocal.action == ConnectionPowerAction.Connect &&
                                                latestPowerAction != null) {
                                                account.nodeSelection.sync()
                                                val choices = account.nodeSelection.snapshot
                                                when {
                                                    account.nodeSelection.busy || choices.status != LocalSubscriptionStatus.READY ||
                                                        choices.choices.isEmpty() -> preparationHint = "请重试读取配置"
                                                    // The owner/gateway chooses only from fresh legal nodes and preserves manual selection.
                                                    else -> { acceptedCommand = true; latestPowerAction?.invoke(ConnectionPowerAction.Connect) }
                                                }
                                            } else preparationHint = completedLocal.title.ifBlank { "连接暂不可用" }
                                        }
                                    }
                                })
                    },
                    showPrimary = !controlMode || recoverBeforeConnect)
                val setupMessage = if (account.authenticated && verifiedUsage && snapshot.status == LocalSubscriptionStatus.MISSING &&
                    !account.busy) connectionSetupMessage(account.importStatus, account.error, false) else null
                val notice = when {
                    account.busy || account.nodeSelection.busy || usage.busy -> null
                    preparationHint != null -> preparationHint!! to "connection-preparation-next"
                    control.phase == ConnectionPhase.Failed -> local.title to "connection-failure"
                    setupMessage != null -> setupMessage to "connection-setup-status"
                    localReadRecovery -> local.title to "connection-read-error"
                    usage.error != null -> "账户信息暂未更新" to "connection-read-error"
                    else -> null
                }
                ConnectionNotice(account, account.authSessionGeneration, notice)
                if (preparationRequest?.let(account::isConnectionPreparationPending) == true) TextButton({
                    preparationRequest?.let(account::cancelConnectionPreparation)
                    preparationRequest = null
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-prepare-cancel")) { Text("取消准备") }
                if (controlMode && !recoverBeforeConnect) ConnectionActionButton(
                    when (local.action) {
                        ConnectionPowerAction.Connect -> "一键连接"
                        ConnectionPowerAction.Cancel -> "取消连接"
                        ConnectionPowerAction.Disconnect -> "断开"
                        ConnectionPowerAction.None -> local.powerLabel
                    }, {
                        if (controlEnabled && !acceptedCommand) { acceptedCommand = true; onPowerAction?.invoke(local.action) }
                    }, enabled = controlEnabled, connected = connectionActionIsConnected(control.phase),
                    modifier = Modifier.padding(top = 5.dp).testTag("vpn-connect").semantics {
                        contentDescription = when (local.action) {
                            ConnectionPowerAction.Connect -> "一键连接"
                            ConnectionPowerAction.Disconnect -> "断开"
                            else -> local.powerLabel
                        }
                        stateDescription = local.connectionText
                    })
            }
        }
        GlassSurface(shape = RoundedCornerShape(23.dp)) {
            TextButton(onClick = openNodes, shape = RoundedCornerShape(23.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("connection-nodes")) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val summary: @Composable (Modifier) -> Unit = { modifier ->
                        Row(modifier, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NodeRegionIcon(name = displayedNode?.name, size = 39.dp, emphasized = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("当前节点", color = Brand.Muted, fontSize = 10.sp, letterSpacing = .5.sp)
                                if (snapshot.sourceKind == com.gccwebos.android.portal.ManualSourceKind.ImportedLocal)
                                    Text("本地配置", color = Brand.Cyan, fontSize = 10.sp, modifier = Modifier.testTag("connection-local-source"))
                                if (snapshot.sourceKind == com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)
                                    Text("第三方订阅", color = Brand.Cyan, fontSize = 10.sp, modifier = Modifier.testTag("connection-external-source"))
                                Text(displayedNode?.name ?: "未选择",
                                    color = Brand.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.testTag("connection-node-name"))
                                displayedNode?.let { Text(it.protocol.uppercase(), color = Brand.Muted,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connection-node-protocol")) }
                                NodeLatencyBadge(account.nodeLatency.reading(selectedNode?.selection),
                                    Modifier.testTag("connection-node-latency"))
                            }
                        }
                    }
                    if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary(Modifier.fillMaxWidth()); Text("选择节点 ›", fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        summary(Modifier.weight(1f)); Text("选择节点 ›", fontSize = 12.sp)
                    }
                }
            }
        }
        OfficialWebsiteCard()
        // Local configuration evidence has no account/resource binding and is never the account usage.
        if (snapshot.status != LocalSubscriptionStatus.MISSING) {
            val action = when {
                local.status == LocalSubscriptionStatus.EXPIRED -> Triple("查看套餐", "expired-subscription-renew", openPlans)
                local.status == LocalSubscriptionStatus.INVALID_LINK -> Triple("登录恢复订阅", "open-account", openAccount)
                else -> null
            }
            action?.let { TextButton(it.third, enabled = operable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(it.second)) { Text(it.first) } }
        }
    }
    if (showGuestChoice && !account.authenticated) AlertDialog(
        modifier = Modifier.testTag("usage-guest-guidance"), onDismissRequest = { showGuestChoice = false },
        title = { Text("开通飞连vpn") }, text = { Text("登录或注册后查看套餐") },
        confirmButton = { Column {
            TextButton({ showGuestChoice = false; openAccount() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-login")) { Text("登录") }
            TextButton({ showGuestChoice = false; openRegistration() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-register")) { Text("注册") }
        } }, dismissButton = { TextButton({ showGuestChoice = false }) { Text("取消") } })
}

/** One brief read/setup failure announcement. It cannot retry, prepare or start a tunnel. */
@Composable
private fun ConnectionNotice(account: AccountController, generation: Long, notice: Pair<String, String>?) {
    val host = remember(account, generation) { SnackbarHostState() }
    LaunchedEffect(host, notice) {
        host.currentSnackbarData?.dismiss()
        notice?.first?.takeIf { it.isNotBlank() }?.let {
            host.showSnackbar(it.substringBefore('，').substringBefore('；').removeSuffix("。"),
                duration = SnackbarDuration.Short)
        }
    }
    SnackbarHost(host, Modifier.fillMaxWidth()) { data ->
        Snackbar(containerColor = Brand.SurfaceRaised, contentColor = Brand.Text) {
            Text(data.visuals.message, modifier = Modifier.testTag(notice?.second ?: "connection-notice"))
        }
    }
}

@Composable
private fun ConnectionStatusRow(local: ConnectionOverviewPresentation, plan: String?, activePlan: Boolean,
    modifier: Modifier = Modifier) {
    val tone = when (local.tone) {
        ConnectionVisualTone.Connected -> Brand.Green
        ConnectionVisualTone.Connecting -> Brand.Cyan
        ConnectionVisualTone.Error -> MaterialTheme.colorScheme.error
        ConnectionVisualTone.Neutral -> Brand.Muted
    }
    val status: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(tone, CircleShape))
            Text(local.connectionText, color = tone, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.testTag("connection-status"))
        }
    }
    val badge: @Composable () -> Unit = {
        plan?.let {
            Text(it, color = if (activePlan) Brand.Green else Brand.Muted, fontSize = 10.sp, lineHeight = 14.sp,
                letterSpacing = .4.sp,
                modifier = Modifier.background(if (activePlan) Brand.Green.copy(alpha = .17f)
                    else Brand.SurfaceRaised.copy(alpha = .45f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (activePlan) Brand.Green.copy(alpha = .45f) else Brand.RowDivider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("account-usage-plan"))
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { status(); badge() }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) { status(); badge() }
    }
}

/** Exact V2 welcome asset; it already carries the current mobile brand. */
@Composable
private fun ConnectionWelcome() {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("connection-welcome")) {
        val compact = maxWidth < 300.dp
        val greeting: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("欢迎回来", fontSize = if (compact) 24.sp else 28.sp, lineHeight = if (compact) 32.sp else 37.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Text("飞连vpn · 与你同行", color = Brand.Muted, fontSize = if (compact) 11.sp else 12.sp,
                    letterSpacing = .45.sp)
            }
        }
        val image: @Composable (Modifier) -> Unit = { modifier ->
            Image(painterResource(R.drawable.brand_robot_welcome), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = modifier.size(if (compact) 76.dp else 84.dp))
        }
        if (maxWidth < 230.dp || LocalDensity.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            greeting(Modifier.fillMaxWidth()); image(Modifier.align(Alignment.End))
        } else Row(Modifier.fillMaxWidth().heightIn(min = 82.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) { greeting(Modifier.weight(1f)); image(Modifier) }
    }
}
