package com.gccwebos.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.ImportStatus
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import com.gccwebos.android.portal.NodeLatencyBadge
import com.gccwebos.android.portal.NodeLatencyObservation
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    /** Display-only: the node the running session was prepared for. Published only with Connected. */
    val activeNode: com.gccwebos.android.core.PreparedNodeLabel? = null,
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

/**
 * C1: a node list is loaded and ready, offers real choices, and the user has none selected, so the card
 * explains what 一键连接 will do. Pure presentation input: it never issues or blocks a connection command.
 */
internal fun connectionNeedsNodeChoice(action: ConnectionPowerAction, commandPending: Boolean, hasNode: Boolean,
    selectionBusy: Boolean, synchronized: Boolean, status: LocalSubscriptionStatus, hasRealChoices: Boolean): Boolean =
    action == ConnectionPowerAction.Connect && !commandPending && !hasNode && !selectionBusy && synchronized &&
        status == LocalSubscriptionStatus.READY && hasRealChoices

/** The idle owner may offer preparation before a local capability exists. That alone
 * must not hide account guidance; live/pending commands always retain their own UI. */
internal fun showsConnectionCommand(presentation: ConnectionOverviewPresentation, control: ConnectionControlState): Boolean =
    control.phase in setOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting) ||
        control.commandPending || presentation.action != ConnectionPowerAction.None

/** Account preparation is a separate explicit action, never a VPN command or local readiness. */
internal enum class ConnectionSetupAction { None, AccountChoice, Plans, SyncSubscription, ChooseNode }
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
    chooseNode: Boolean = false,
): ConnectionSetupGuidance {
    // Signed out with an imported/third-party source and no node chosen: the next step is the node list, not buying.
    if (!authenticated && chooseNode) return ConnectionSetupGuidance(ConnectionSetupAction.ChooseNode, "选择节点", operable && !busy)
    if (!authenticated) return ConnectionSetupGuidance(ConnectionSetupAction.AccountChoice, "开通飞连VPN", operable && !busy)
    if (busy) return ConnectionSetupGuidance(ConnectionSetupAction.None, "准备中", false)
    if (!verifiedUsage || refreshFailed) return ConnectionSetupGuidance(ConnectionSetupAction.None, "重试", false)
    if (resource == Resource.None) return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "开通飞连VPN", operable)
    if (resource !is Resource.Available) return ConnectionSetupGuidance(ConnectionSetupAction.None, "重试", false)
    if (resource.validUntil <= now) return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "查看套餐", operable, "订阅已到期")
    if (resource.usage.status in setOf("measured", "measured_zero") && resource.usage.remainingBytes == 0L)
        return ConnectionSetupGuidance(ConnectionSetupAction.Plans, "查看套餐", operable, "本期流量已用尽")
    if (snapshot.status == LocalSubscriptionStatus.MISSING)
        return ConnectionSetupGuidance(ConnectionSetupAction.SyncSubscription, "一键连接", operable, "本机尚无配置")
    return ConnectionSetupGuidance(ConnectionSetupAction.None, "一键连接", false)
}

/** A manual (imported/third-party) source is active, the user is signed out and no node is selected yet. */
internal fun connectionChoosesNodeFirst(authenticated: Boolean, sourceKind: com.gccwebos.android.portal.ManualSourceKind,
    hasSelectedNode: Boolean): Boolean = !authenticated && !hasSelectedNode && sourceKind in setOf(
    com.gccwebos.android.portal.ManualSourceKind.ImportedLocal, com.gccwebos.android.portal.ManualSourceKind.ImportedRemote,
    com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)

/** One explicit intent. Re-evaluate the current authenticated/busy context before calling this. */
internal fun dispatchConnectionSetup(
    guidance: ConnectionSetupGuidance,
    onAccountChoice: () -> Unit,
    onPlans: () -> Unit,
    onSync: () -> Unit,
    onChooseNode: () -> Unit = {},
): Boolean {
    if (!guidance.enabled) return false
    when (guidance.action) {
        ConnectionSetupAction.AccountChoice -> onAccountChoice()
        ConnectionSetupAction.Plans -> onPlans()
        ConnectionSetupAction.SyncSubscription -> onSync()
        ConnectionSetupAction.ChooseNode -> onChooseNode()
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

/** Home zones (Hero, plan, multi-platform link) are independent of local configuration and engine admission. */
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
    trafficSampler: () -> TrafficCounterSample = ::readAppTrafficCounters,
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
    // The owner reports the node the running session was prepared for. It survives a cleared UI
    // selection (process restart, refresh, one-click auto pick). Latency is only measured while that
    // node is still in the current validated registry; otherwise it stays unknown.
    val activeNode = control.activeNode?.takeIf { control.phase == ConnectionPhase.Connected }
    val activeInRegistry = activeNode != null && nodeSnapshot.status == LocalSubscriptionStatus.READY &&
        nodeSnapshot.choices.any { it.selection === activeNode.selection && !it.info }
    val latencySelection = if (activeNode != null) activeNode.selection.takeIf { activeInRegistry } else selectedNode?.selection
    LaunchedEffect(account, latencySelection, account.busy, account.restoring,
        account.foregroundCheckPending, account.nodeLatency.foreground) {
        if (latencySelection != null && account.nodeLatency.foreground && !account.busy && !account.restoring &&
            !account.foregroundCheckPending && !account.clearing) while (true) {
            account.nodeLatency.pruneExpired()
            account.nodeLatency.ensureMeasured(latencySelection)
            delay(1_000)
        }
    }
    val latestSampler by rememberUpdatedState(trafficSampler)
    val throughput by produceState(TunnelThroughput.Unknown, activeNode != null) {
        value = TunnelThroughput.Unknown
        if (activeNode == null) return@produceState
        val meter = TunnelThroughputMeter()
        while (true) {
            val sample = try { withContext(Dispatchers.IO) { latestSampler() } } catch (_: Exception) { null }
            value = if (sample == null) TunnelThroughput.Unknown.also { meter.reset() } else meter.update(sample)
            delay(TunnelThroughputMeter.SampleIntervalMillis)
        }
    }
    val connectedNode = connectedNodePresentation(control, throughput,
        latencySelection?.takeIf { activeNode != null }?.let { selection ->
            // Probes cannot run through the tunnel; fall back to the last real measurement for this node.
            account.nodeLatency.reading(selection).takeIf { it is NodeLatencyObservation.Success }
                ?: account.nodeLatency.lastKnownSuccess(selection) ?: account.nodeLatency.reading(selection)
        })
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
        operable, account.busy, refreshFailed = usage.error != null,
        chooseNode = connectionChoosesNodeFirst(account.authenticated, snapshot.sourceKind, selectedNode != null || displayedNode != null))
    var showGuestChoice by remember(account.authSessionGeneration) { mutableStateOf(false) }
    var acceptedCommand by remember(control, account) { mutableStateOf(false) }
    // Keep service control authoritative without interpreting an idle preparation capability as local readiness.
    val controlMode = showsConnectionCommand(local, control)
    val manualSource = snapshot.sourceKind in setOf(com.gccwebos.android.portal.ManualSourceKind.ImportedLocal,
        com.gccwebos.android.portal.ManualSourceKind.ImportedRemote, com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)
    // Display-only: the selected configuration's own reported usage when signed out or using an imported source.
    // A signed-in account source keeps Portal data. Never fed into account usage, setup guidance or admission.
    val subscriptionUsage = if (!account.authenticated || manualSource)
        subscriptionUsagePresentation(snapshot.usage, snapshot.validUntil, now) else null
    val usageRecovery = !manualSource && account.authenticated && (usage.error != null ||
        display.state in setOf(AccountUsageState.Unknown, AccountUsageState.Reconciliation))
    val recoverBeforeConnect = controlMode && local.action == ConnectionPowerAction.Connect && usageRecovery
    val localReadRecovery = account.authenticated && verifiedUsage && snapshot.hasCapability &&
        local.status in setOf(LocalSubscriptionStatus.RETRY_REQUIRED, LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR)
    val controlEnabled = onPowerAction != null && !acceptedCommand && local.action != ConnectionPowerAction.None &&
        (local.action != ConnectionPowerAction.Connect || (!account.clearing && !account.manualImport.busy &&
            !account.nodeSelection.busy && (if (manualSource) !account.busy && !account.restoring && !account.foregroundCheckPending
            else !account.authenticated || (operable && verifiedUsage && !usageRecovery))))
    val loading = account.busy || account.nodeSelection.busy || usage.busy || usage.verifying || account.restoring ||
        account.foregroundCheckPending
    val onSetupAction: () -> Unit = primary@{
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
                refreshFailed = currentUsage.error != null,
                chooseNode = connectionChoosesNodeFirst(account.authenticated, account.localSubscription.sourceKind,
                    account.nodeSelection.snapshot.selected != null)),
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
                }, openNodes)
    }
    val connected = control.phase == ConnectionPhase.Connected
    // C1: a loaded list with nothing selected (first run, or the remembered node is gone). "一键连接" keeps its
    // designed behaviour (use the remembered node, else pick one for the user); the card now says so instead of
    // showing a bare "未选择". Display only: the connect command and its admission are untouched.
    val needsNodeChoice = connectionNeedsNodeChoice(local.action, control.commandPending || acceptedCommand,
        selectedNode != null || displayedNode != null, account.nodeSelection.busy,
        synchronizedNodeGeneration == account.authSessionGeneration, nodeSnapshot.status,
        nodeSnapshot.choices.any { !it.info })
    val nodeChoiceHint = when {
        nodeSnapshot.selectionLost && needsNodeChoice -> "请重新选择节点，或直接一键连接自动选择"
        needsNodeChoice -> "未选节点，一键连接将自动选择"
        else -> null
    }
    // Zones: (1) the page's light brand header, (2) Hero: state + node + power, (3) plan/usage, (4) multi-platform link.
    // C11: with the usage card hidden the page is only the hero and the secondary banner, which hugged the top and left
    // the lower half bare. Give the page a floor height and centre the pair in it (no new content, no new numbers).
    val usageHidden = connectionUsageCardHidden(account.authenticated, manualSource, subscriptionUsage != null, snapshot.status)
    val pageFloor = if (usageHidden) (LocalConfiguration.current.screenHeightDp * ConnectionBalancedPageFraction).dp else 0.dp
    Column(Modifier.fillMaxWidth().heightIn(min = pageFloor).padding(bottom = homeBottomSpacer())
        .testTag("connection-overview"),
        verticalArrangement = if (usageHidden) Arrangement.spacedBy(HomeSpacing.CardGap, Alignment.CenterVertically)
            else Arrangement.spacedBy(HomeSpacing.CardGap)) {
        // Zone order (Ethan): the traffic/usage card comes first, the connect + node area sits below it.
        // C10: a local configuration that reports no verified usage (no Subscription-Userinfo) has nothing honest to put
        // on this card; "暂未获取" next to the node page's info-row text read as a contradiction. Info-row text is
        // third-party display text and is never promoted to a usage figure here.
        if (!usageHidden) {
        // Rim: calm cyan glow while connected (loops only while shown and resumed), static dim rim otherwise.
        UsageRimFrame(connected) {
        HomeCard(Modifier.testTag("connection-account-surface"),
            edge = Brand.Cyan.copy(alpha = UsageRimAlphaIdle)) {
            ConnectionUsageCard(display, authenticated = account.authenticated,
                subscriptionUsage = subscriptionUsage,
                // Display-only motion inputs: tunnel phase for the glow, the same clocks the card text uses.
                connected = connected,
                now = if (subscriptionUsage != null) now else usageNow,
                onRefresh = account::refreshAccountUsage,
                loading = loading,
                error = usage.error, refreshEnabled = verifiedUsage && operable,
                primaryLabel = setup.label,
                primaryEnabled = setup.enabled || (localReadRecovery && operable),
                primaryHint = null,
                // The setup action lives in the Hero card now.
                onPrimaryAction = onSetupAction, showPrimary = false,
                planText = display.planText.takeIf { account.authenticated },
                activePlan = verifiedUsage && connectionPlanIsActive(display),
                onRenew = openPlans.takeIf { subscriptionUsage == null &&
                    connectionRenewVisible(account.authenticated && verifiedUsage, manualSource, display.state) })
        }
        }
        }
        HomeCard(Modifier.testTag("connection-hero"),
            // Static rim tint while connected; the only breathing glow stays on the usage bar (#36 rules).
            edge = if (connected) Brand.Green.copy(alpha = .38f) else HomeCardEdge) {
            ConnectionStatusRow(local)
            ConnectionNodeRow(account, connectedNode, displayedNode, selectedNode, snapshot.sourceKind,
                connectionNodeEmphasized(control.phase), nodeChoiceHint, openNodes)
            if (!controlMode || recoverBeforeConnect) ConnectionSetupButton(display, account.authenticated,
                account::refreshAccountUsage, loading, usage.error, verifiedUsage && operable, setup.label,
                setup.enabled || (localReadRecovery && operable), onSetupAction)
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
                modifier = Modifier.testTag("vpn-connect").semantics {
                    contentDescription = when (local.action) {
                        ConnectionPowerAction.Connect -> "一键连接"
                        ConnectionPowerAction.Disconnect -> "断开"
                        else -> local.powerLabel
                    }
                    stateDescription = local.connectionText
                })
        }
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
        OfficialWebsiteCard()
    }
    if (showGuestChoice && !account.authenticated) AlertDialog(
        modifier = Modifier.testTag("usage-guest-guidance"), onDismissRequest = { showGuestChoice = false },
        title = { Text("开通飞连VPN") }, text = { Text("登录或注册后查看套餐") },
        confirmButton = { Column {
            TextButton({ showGuestChoice = false; openAccount() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-login")) { Text("登录") }
            TextButton({ showGuestChoice = false; openRegistration() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-register")) { Text("注册") }
        } }, dismissButton = { TextButton({ showGuestChoice = false }) { Text("取消") } })
}

@Composable
private fun SourceKindLabel(kind: com.gccwebos.android.portal.ManualSourceKind) {
    if (kind == com.gccwebos.android.portal.ManualSourceKind.ImportedLocal)
        Text("本地配置", color = Brand.Cyan, fontSize = 10.sp, modifier = Modifier.testTag("connection-local-source"))
    if (kind == com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)
        Text("第三方订阅", color = Brand.Cyan, fontSize = 10.sp, modifier = Modifier.testTag("connection-external-source"))
}

/** Connected layout: three short lines, no taller than the idle card. Static text, no looping motion. */
@Composable
private fun ConnectedNodeSummary(node: ConnectedNodePresentation, kind: com.gccwebos.android.portal.ManualSourceKind,
    modifier: Modifier) {
    Row(modifier.testTag("connection-active-node"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NodeRegionIcon(name = node.name, size = 39.dp, emphasized = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // The protocol chip moved up next to the label so the node name gets the full width (two lines).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前节点", color = Brand.Muted, fontSize = 10.sp, letterSpacing = .5.sp)
                SourceKindLabel(kind)
                Spacer(Modifier.weight(1f))
                if (node.protocol.isNotBlank()) Text(node.protocol, color = Brand.Cyan, fontSize = 9.sp, lineHeight = 12.sp,
                    maxLines = 1, modifier = Modifier.border(1.dp, Brand.Cyan.copy(alpha = .4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp).testTag("connection-node-protocol"))
            }
            // The region badge already shows the flag; the duplicate flag emoji in the name is dropped here.
            Text(nodeDisplayName(node.name), color = Brand.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("connection-node-name"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // C9: the latency (with its "ms" unit) must never be the part that gets clipped: the speeds shrink first.
                Text(if (node.trafficIdle) "等待流量" else "↓ ${node.downText}  ↑ ${node.upText}", color = Brand.Muted,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("connection-node-throughput")
                        .semantics { contentDescription = node.throughputDescription })
                Text(" · ", color = Brand.Muted, fontSize = 11.sp)
                Text(node.latencyText, color = if (node.latencyKnown) Brand.Green else Brand.Muted, fontSize = 11.sp,
                    maxLines = 1, softWrap = false, modifier = Modifier.testTag("connection-node-latency")
                        .semantics { contentDescription = node.latencyDescription })
            }
        }
    }
}

/** This app's UID byte counters from the OS. The VPN core's protected sockets carry the tunnel's
 * encrypted traffic, so the delta approximates tunnel throughput (including protocol overhead). */
internal fun readAppTrafficCounters(): TrafficCounterSample {
    val uid = android.os.Process.myUid()
    return TrafficCounterSample(android.os.SystemClock.elapsedRealtime(),
        android.net.TrafficStats.getUidRxBytes(uid), android.net.TrafficStats.getUidTxBytes(uid))
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

/** Hero header: the tunnel state, once. The plan badge moved to the plan card's header row. */
@Composable
private fun ConnectionStatusRow(local: ConnectionOverviewPresentation, modifier: Modifier = Modifier) {
    val tone = when (local.tone) {
        ConnectionVisualTone.Connected -> Brand.Green
        ConnectionVisualTone.Connecting -> Brand.Cyan
        ConnectionVisualTone.Error -> MaterialTheme.colorScheme.error
        ConnectionVisualTone.Neutral -> Brand.Muted
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Static halo while connected; no extra looping animation here.
        Box(Modifier.size(14.dp).background(tone.copy(alpha = if (local.tone == ConnectionVisualTone.Connected) .22f else 0f),
            CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).background(tone, CircleShape))
        }
        Text(local.connectionText, color = tone, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("connection-status"))
    }
}

/** Hero node row: the whole row opens node selection (64 dp min). Idle node info is muted; connected shows live data. */
@Composable
private fun ConnectionNodeRow(
    account: AccountController,
    connectedNode: ConnectedNodePresentation?,
    displayedNode: com.gccwebos.android.core.ConnectionChoice?,
    selectedNode: com.gccwebos.android.core.ConnectionChoice?,
    sourceKind: com.gccwebos.android.portal.ManualSourceKind,
    emphasized: Boolean,
    nodeChoiceHint: String?,
    openNodes: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    TextButton(onClick = openNodes, shape = shape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(Color.White.copy(alpha = if (emphasized) .05f else .03f), shape)
            .testTag("connection-nodes")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val summary: @Composable (Modifier) -> Unit = { modifier ->
                if (connectedNode != null) ConnectedNodeSummary(connectedNode, sourceKind, modifier)
                else Row(modifier, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NodeRegionIcon(name = displayedNode?.name, size = 36.dp, emphasized = emphasized)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("当前节点", color = Brand.Muted, fontSize = 10.sp, letterSpacing = .5.sp)
                            SourceKindLabel(sourceKind)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(displayedNode?.name?.let(::nodeDisplayName) ?: "未选择",
                                color = if (emphasized) Brand.Text else Brand.Muted, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).testTag("connection-node-name"))
                            displayedNode?.let { Text(it.protocol.uppercase(), color = Brand.Muted,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                modifier = Modifier.testTag("connection-node-protocol")) }
                        }
                        if (displayedNode != null) {
                            val reading = account.nodeLatency.reading(selectedNode?.selection)
                            // C12: an idle latency number needs a name; the badge keeps the threshold colour rule.
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(connectionNodeLatencyLabel(reading), color = Brand.Muted, fontSize = 11.sp, maxLines = 1,
                                    modifier = Modifier.testTag("connection-node-latency-label"))
                                NodeLatencyBadge(reading, Modifier.testTag("connection-node-latency"))
                            }
                        }
                        else nodeChoiceHint?.let { Text(it, color = Brand.Amber, fontSize = 11.sp, maxLines = 2,
                            modifier = Modifier.testTag("connection-node-hint")) }
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

/**
 * C10: hide the usage card when the page is driven by a local/imported configuration and that configuration reported
 * no verified usage. A signed-in account source, the empty first-run page and any state with usage keep the card.
 */
internal fun connectionUsageCardHidden(
    authenticated: Boolean,
    manualSource: Boolean,
    hasSubscriptionUsage: Boolean,
    status: LocalSubscriptionStatus,
): Boolean = (!authenticated || manualSource) && !hasSubscriptionUsage && status != LocalSubscriptionStatus.MISSING

/** C11: share of the screen height the connect page is padded out to while its usage card is hidden. */
internal const val ConnectionBalancedPageFraction = 0.66f

/** C12: "上次延迟测试" for a real reading, otherwise the neutral "延迟" ("延迟 —" / "延迟 超时"). */
internal fun connectionNodeLatencyLabel(observation: NodeLatencyObservation?): String =
    if (observation is NodeLatencyObservation.Success) "上次延迟测试" else "延迟"
