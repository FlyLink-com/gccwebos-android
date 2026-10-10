package com.gccwebos.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.*
import com.gccwebos.android.commerce.CommerceMode
import com.gccwebos.android.commerce.CommercePanel
import com.gccwebos.android.commerce.CatalogDockState
import com.gccwebos.android.commerce.CatalogPurchaseDock
import com.gccwebos.android.commerce.LocalCatalogPurchaseDock
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.AccountPanel
import com.gccwebos.android.portal.RegistrationController
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.NodeSelectionPanel
import com.gccwebos.android.portal.ConfigurationLibraryPanel
import com.gccwebos.android.portal.ManualSourceKind
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.productionRepository
import com.gccwebos.android.vpn.ActivityVpnPermission
import com.gccwebos.android.vpn.ConnectionOwnerService
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private data class ConsentAttachment(
        val permission: ActivityVpnPermission,
        val attachment: ActivityVpnPermission.Attachment,
    )
    private var owner by mutableStateOf<ConnectionOwnerService.LocalBinder?>(null)
    private var bindingFailure by mutableStateOf(false)
    private var bound = false
    private var attachedPermission: ConsentAttachment? = null
    private var launchedPermission: ConsentAttachment? = null
    // Recreated activities never restore a consent result into a new request identity.
    private val vpnPermission = activityResultRegistry.register(
        "production-vpn-consent-${UUID.randomUUID()}", this, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val original = launchedPermission
        launchedPermission = null
        original?.permission?.deliver(original.attachment, result.resultCode)
    }
    private val ownerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val local = binder as? ConnectionOwnerService.LocalBinder ?: run { bindingUnavailable(); return }
            detachPermission()
            val permission = local.permission
            lateinit var attachment: ActivityVpnPermission.Attachment
            attachment = permission.attach { intent ->
                check(launchedPermission == null) { "A previous VPN consent result is still outstanding" }
                val original = ConsentAttachment(permission, attachment)
                launchedPermission = original
                try { vpnPermission.launch(intent) }
                catch (failure: Exception) {
                    if (launchedPermission === original) launchedPermission = null
                    throw failure
                }
            }
            attachedPermission = ConsentAttachment(permission, attachment)
            bindingFailure = false
            owner = local
        }
        override fun onServiceDisconnected(name: ComponentName) = bindingUnavailable()
        override fun onBindingDied(name: ComponentName) = bindingUnavailable()
        override fun onNullBinding(name: ComponentName) = bindingUnavailable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            com.gccwebos.android.update.AutomaticUpdateHost {
            val current = owner
            if (current == null) {
                GccTheme {
                    Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                        Column(Modifier.fillMaxSize().testTag(if (bindingFailure) "connection-owner-unavailable" else "connection-owner-initializing"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (!bindingFailure) Image(painterResource(R.drawable.brand_robot_avatar), null,
                                modifier = Modifier.size(52.dp))
                            Text(if (bindingFailure) "连接服务暂不可用" else "飞连vpn",
                                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                val control by current.control.collectAsState()
                val nodeProbe = remember(applicationContext) { com.gccwebos.android.vpn.AndroidNodeLatencyProbe(applicationContext) }
                AppShell(repositoryOverride = current.repository, connectionControlState = control,
                    nodeLatencyProbe = nodeProbe,
                    sourceCommitFence = current::commitSourceChange, logoutClearingAccount = current::logoutClearingAccountSubscription,
                    onConnectionPowerAction = { action -> current.power(action) })
            }
            }
        }
        bound = try { bindService(Intent(this, ConnectionOwnerService::class.java), ownerConnection, Context.BIND_AUTO_CREATE) }
            catch (_: Exception) { false }
        if (!bound) bindingFailure = true
    }

    private fun bindingUnavailable() {
        detachPermission()
        owner = null
        bindingFailure = true
    }

    private fun detachPermission() {
        attachedPermission?.let { it.permission.detach(it.attachment) }
        attachedPermission = null
    }

    override fun onDestroy() {
        detachPermission()
        if (bound) { unbindService(ownerConnection); bound = false }
        // onStop keeps the binding: Android's own consent screen may cover this activity.
        super.onDestroy()
    }
}

private enum class Destination(@StringRes val title: Int, @DrawableRes val icon: Int) {
    Connect(R.string.tab_connect, R.drawable.brand_plane),
    Lines(R.string.tab_lines, R.drawable.brand_globe),
    Plans(R.string.tab_plans, R.drawable.brand_receipt),
    Orders(R.string.tab_orders, R.drawable.brand_receipt),
    Profile(R.string.tab_profile, R.drawable.brand_user),
}

// Nodes are a primary destination; account-owned order and ledger history stays inside My.
private val primaryDestinations = listOf(Destination.Connect, Destination.Lines, Destination.Plans, Destination.Profile)

/** The same lifecycle handler is used by production and memory-backed UI fixtures. */
internal class AccountForegroundObserver(private val account: AccountController) : LifecycleEventObserver {
    private var leftForeground = false
    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP) {
            leftForeground = true
            account.nodeLatency.setForeground(false)
        }
        if (event == Lifecycle.Event.ON_RESUME) account.nodeLatency.setForeground(true)
        if (event == Lifecycle.Event.ON_RESUME && leftForeground) {
            leftForeground = false; account.onForeground()
        }
    }
}

/** Back cancels new sign-in work; saved-session verification and authenticated reads only navigate. */
internal fun shouldCancelLoginOnBack(account: AccountController): Boolean =
    !account.authenticated && !account.restoring && !account.foregroundCheckPending &&
        !account.restorationPending && (account.factorReference != null || account.busy)

/**
 * Signed out, 「我的」 shows the ordinary Mine layout. The login form appears only after an explicit
 * request (去登录 or another page's login entry) or while sign-in/restore work is already in flight.
 */
internal fun showsSignedOutMine(authenticated: Boolean, retainingCommerceSession: Boolean, loginRequested: Boolean,
    restoring: Boolean, foregroundCheckPending: Boolean, restorationPending: Boolean, factorPending: Boolean,
    busy: Boolean): Boolean =
    !authenticated && !retainingCommerceSession && !loginRequested && !restoring && !foregroundCheckPending &&
        !restorationPending && !factorPending && !busy

internal fun showsSignedOutMine(account: AccountController, loginRequested: Boolean): Boolean =
    showsSignedOutMine(account.authenticated, account.retainingCommerceSession, loginRequested, account.restoring,
        account.foregroundCheckPending, account.restorationPending, account.factorReference != null, account.busy)

@Composable
internal fun AppShell(accountOverride: AccountController? = null, autoLoadCommerce: Boolean = true,
    commerceOverride: com.gccwebos.android.commerce.CommerceController? = null,
    registrationOpener: ((String) -> Unit)? = null,
    registrationOverride: RegistrationController? = null,
    commerceRecoveryOverride: com.gccwebos.android.commerce.CommerceRecovery? = null,
    paymentOpenerOverride: ((String) -> Unit)? = null,
    connectionControlState: ConnectionControlState = ConnectionControlState(),
    onConnectionPowerAction: ((ConnectionPowerAction) -> Unit)? = null,
    repositoryOverride: PortalRepository? = null,
    glassMode: GlassMode? = null,
    nodeLatencyProbe: com.gccwebos.android.portal.NodeLatencyProbe? = null,
    autoRefreshPages: Boolean = autoLoadCommerce,
    sourceCommitFence: ((() -> Unit) -> Unit) = { error("Connection owner is unavailable") },
    logoutClearingAccount: (suspend () -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    // The controller remains outside destinations, preserving in-flight authentication on navigation.
    val account = key(accountOverride, repositoryOverride) {
        val scope = rememberCoroutineScope()
        remember(accountOverride, repositoryOverride, nodeLatencyProbe) {
            val repository = repositoryOverride ?: productionRepository(context)
            accountOverride ?: AccountController(repository, scope,
                nodeLatencyProbe = nodeLatencyProbe, sourceCommitFence = sourceCommitFence,
                logoutClearingAccount = logoutClearingAccount ?: { repository.logoutClearingAccountSubscription() })
        }
    }
    // Keep the local opaque selection synchronized outside keyed destinations; this never downloads.
    LaunchedEffect(account, account.authSessionGeneration, account.localSubscription, account.busy,
        account.restoring, account.foregroundCheckPending, account.clearing) {
        account.nodeSelection.expireIfNeeded()
        account.nodeLatency.sync()
    }
    LaunchedEffect(account, account.authSessionGeneration, account.localSubscription, account.busy,
        account.restoring, account.foregroundCheckPending, account.clearing,
        account.manualImport.busy, account.nodeSelection.busy) {
        account.configurationLibrary.refresh()
    }
    // Keep an accepted power command outside keyed destinations. Navigation cannot replay it
    // while the controller is still publishing acceptance/rejection. A new snapshot/revision is the ack.
    var acceptedConnectionCommand by remember(connectionControlState, account) { mutableStateOf(false) }
    val powerControl = connectionControlState.copy(commandPending = connectionControlState.commandPending || acceptedConnectionCommand)
    val powerAction: ((ConnectionPowerAction) -> Unit)? = onConnectionPowerAction?.let { handler ->
        { action ->
            if (!acceptedConnectionCommand && !connectionControlState.commandPending && action != ConnectionPowerAction.None) {
                acceptedConnectionCommand = true
                handler(action)
            }
        }
    }
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    // Restore starts before the login form may fetch its first question. A child
    // composition must not take the authentication busy slot ahead of restore.
    var loginStartupReady by remember(account) { mutableStateOf(accountOverride != null) }
    LaunchedEffect(account) {
        if (accountOverride == null) account.restore()
        loginStartupReady = true
    }
    DisposableEffect(account, lifecycle) {
        val observer = AccountForegroundObserver(account)
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); account.nodeLatency.cancel(); account.manualImport.cancel(); account.configurationLibrary.cancel() }
    }
    var selectedName by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    var startRegistration by rememberSaveable { mutableStateOf(false) }
    // Presentation only: whether the signed-out 我的 tab shows the login form instead of the Mine layout.
    var loginRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(account, account.authenticated) { if (account.authenticated) loginRequested = false }
    var plansVisit by remember { mutableStateOf(0) }
    var navigationRequest by remember { mutableStateOf(0) }
    // Direction of the last user tab tap (-1/0/+1), consumed by the next page; programmatic navigation
    // (openPlans, back, etc.) leaves 0 and gets a plain fade. Not observable state: it never recomposes.
    val navDirection = remember { intArrayOf(0) }
    val navMotion = rememberUiMotionEnabled()
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    val catalogDock = remember { CatalogDockState() }
    val catalogDisplayCache = remember(account, account.commerceSessionIdentity) {
        com.gccwebos.android.commerce.CatalogDisplayCache(account.commerceSessionIdentity)
    }
    BackHandler(enabled = selected != Destination.Connect) {
        if (selected == Destination.Profile && shouldCancelLoginOnBack(account)) account.cancelAuthentication()
        else if (selected == Destination.Profile && loginRequested && !account.authenticated) {
            loginRequested = false; startRegistration = false
        }
        else { selectedName = Destination.Connect.name; navigationRequest++ }
    }
    GccTheme(materialMode = glassMode) {
        Surface(Modifier.fillMaxSize(), color = Brand.Background) {
            // One continuous backdrop also remains visible behind the floating navigation bar.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val signedOutMine = selected == Destination.Profile && showsSignedOutMine(account, loginRequested || startRegistration)
                val loginPage = selected == Destination.Profile && !account.authenticated && !account.retainingCommerceSession && !signedOutMine
                val backdropModifier = if (loginPage) Modifier.fillMaxSize() else Modifier.widthIn(max = 680.dp).fillMaxSize()
                BrandBackdrop(backdropModifier, loginPage = loginPage, strongScrim = selected == Destination.Plans)
                CompositionLocalProvider(LocalCatalogPurchaseDock provides catalogDock) {
                Scaffold(containerColor = Color.Transparent, bottomBar = {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected == Destination.Plans) catalogDock.summary?.let { summary ->
                            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())) {
                                CatalogPurchaseDock(summary, catalogDock::continuePurchase)
                            }
                        }
                        GlassSurface(Modifier.widthIn(max = 760.dp).fillMaxWidth().testTag("glass-navigation"),
                            shape = RoundedCornerShape(20.dp), kind = GlassSurfaceKind.Navigation) {
                            // A minimum, not a fixed height: wrapped or enlarged labels grow this row.
                            val selectedNavIndex = primaryDestinations.indexOf(selected)
                            val navIndicator = rememberSlidingIndicator(selectedNavIndex, primaryDestinations.size)
                            val tapHaptic = rememberTapHaptic()
                            // One highlight block slides between tabs instead of each tab switching its own background.
                            Row(Modifier.fillMaxWidth().slidingIndicator(navIndicator, primaryDestinations.size,
                                    Brand.Cyan.copy(alpha = .15f), corner = 16.dp, horizontalInset = 4.dp, verticalInset = 3.dp)
                                .selectableGroup().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                primaryDestinations.forEachIndexed { index, destination ->
                                    val title = stringResource(destination.title)
                                    val chosen = destination == selected
                                    val press = rememberPressFeedback()
                                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                        .pressFeedback(press)
                                        .clip(RoundedCornerShape(16.dp))
                                        .testTag("nav-${destination.name}")
                                        .selectable(selected = chosen, interactionSource = press.interactionSource,
                                            indication = androidx.compose.material3.ripple(), onClick = {
                                            if (tabTapHaptic(selectedNavIndex, index, userInitiated = true)) tapHaptic(UiHaptic.Tab)
                                            if (selected != destination) navDirection[0] = tabSlideDirection(selectedNavIndex, index)
                                            if (destination == Destination.Profile) { startRegistration = false; loginRequested = false }
                                            if (destination == Destination.Plans) plansVisit++
                                            navigationRequest++
                                            selectedName = destination.name
                                        }, role = Role.Tab)
                                        .padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        val tint by animateColorAsState(if (chosen) Brand.Cyan else Brand.Muted,
                                            tween(uiMotionMillis(navMotion, UiMotionSpec.TabMillis)), label = "nav-tint")
                                        Icon(painterResource(destination.icon), contentDescription = null,
                                            tint = tint, modifier = Modifier.size(20.dp))
                                        Text(title, color = tint,
                                            style = MaterialTheme.typography.labelMedium, fontSize = 11.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }) { padding ->
                    // Scaffold measures the entire bar, its gap and system inset for content clearance.
                    // Only the arriving page is composed (no overlap of page effects); it fades/drifts in from the tab side.
                    key(selected) { val enter = rememberEnterMotion(remember { navDirection[0].also { navDirection[0] = 0 } })
                        Box(Modifier.fillMaxSize().enterMotion(enter)) { Page(selected, padding, account, autoLoadCommerce, commerceOverride,
                        commerceRecoveryOverride, paymentOpenerOverride, powerControl, powerAction,
                        openAccount = { startRegistration = false; loginRequested = true; navigationRequest++; selectedName = Destination.Profile.name },
                        openPlans = { plansVisit++; navigationRequest++; selectedName = Destination.Plans.name },
                        openConnect = { navigationRequest++; selectedName = Destination.Connect.name },
                        openNodes = { navigationRequest++; selectedName = Destination.Lines.name },
                        openRegistration = { startRegistration = true; loginRequested = true; navigationRequest++; selectedName = Destination.Profile.name },
                        showLogin = !signedOutMine, requestLogin = { loginRequested = true },
                        startRegistration = startRegistration, registrationOpener = registrationOpener,
                        loginStartupReady = loginStartupReady, registrationOverride = registrationOverride,
                        plansVisit = plansVisit, navigationRequest = navigationRequest, autoRefreshPages = autoRefreshPages,
                        catalogDisplayCache = catalogDisplayCache) } }
                }
                }

            }
        }
    }
}

@Composable
private fun LoginScreen(account: AccountController, registrationOpener: ((String) -> Unit)?,
                         loginStartupReady: Boolean, registrationOverride: RegistrationController?,
                         padding: PaddingValues, startRegistration: Boolean) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("login-screen"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrandHeader()
            if (account.restoring || account.foregroundCheckPending) Column(Modifier.testTag("session-checking"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.brand_robot_avatar), null, modifier = Modifier.size(52.dp))
            } else {
                AccountPanel(account, compactLogin = true, registrationOpener = registrationOpener,
                    loginStartupReady = loginStartupReady, registrationOverride = registrationOverride, startRegistration = startRegistration)
            }
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues, account: AccountController,
                  autoLoadCommerce: Boolean, commerceOverride: com.gccwebos.android.commerce.CommerceController?,
                  commerceRecoveryOverride: com.gccwebos.android.commerce.CommerceRecovery?, paymentOpenerOverride: ((String) -> Unit)?,
                  connectionControlState: ConnectionControlState, onConnectionPowerAction: ((ConnectionPowerAction) -> Unit)?,
                  openAccount: () -> Unit, openPlans: () -> Unit, openConnect: () -> Unit, openNodes: () -> Unit,
                  openRegistration: () -> Unit, showLogin: Boolean, requestLogin: () -> Unit, startRegistration: Boolean,
                  registrationOpener: ((String) -> Unit)?, loginStartupReady: Boolean,
                  registrationOverride: RegistrationController?, plansVisit: Int, navigationRequest: Int,
                  autoRefreshPages: Boolean,
                  catalogDisplayCache: com.gccwebos.android.commerce.CatalogDisplayCache) {
    val latestControl by rememberUpdatedState(connectionControlState)
    DisposableEffect(account, destination) {
        onDispose { if (destination == Destination.Lines) account.manualImport.cancel() }
    }
    // Verified authentication revokes old opaque node handles. A mounted Nodes page must
    // rebuild them with a fresh read; other pages do not gain another navigation refresh.
    val nodesOwnerGeneration = account.authSessionGeneration.takeIf { destination == Destination.Lines }
    // The signed-out Mine layout makes no account read; the saved-session check runs once the login form is shown.
    val profileLoginForm = destination == Destination.Profile && showLogin
    LaunchedEffect(account, destination, navigationRequest, autoRefreshPages, nodesOwnerGeneration, profileLoginForm) {
        if (!autoRefreshPages || (navigationRequest == 0 && destination != Destination.Lines)) return@LaunchedEffect
        snapshotFlow { !account.busy && !account.restoring && !account.foregroundCheckPending && !account.clearing &&
            !account.manualImport.busy && !account.configurationLibrary.busy }.first { it }
        if (destination == Destination.Profile && !account.authenticated && !startRegistration) {
            if (showLogin) account.restoreAccount()
        } else if (destination == Destination.Lines) {
            // An ordinary page visit cannot invalidate an accepted connection preparation.
            val connectionActive = latestControl.commandPending || latestControl.phase in
                setOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting)
            if (!account.nodeSelection.busy && !connectionActive) {
                // Navigation reads never claim or acknowledge a delivery. Those recovery
                // transactions stay behind the existing explicit connection/import actions.
                if (account.localSubscription.hasCapability) account.nodeSelection.refreshIfNeeded {
                    !latestControl.commandPending && latestControl.phase !in
                        setOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting)
                }
                else if (account.authenticated) account.refreshAccountUsage()
                // Do not force a latency sweep on entry. The nodes panel starts a non-forced
                // pass after the first frame. A connected visit stays on the local rebuild
                // below and must not gain a probe from this effect.
            } else if (!account.nodeSelection.busy && connectionActive &&
                account.localSubscription.hasCapability && account.nodeSelection.snapshot.choices.isEmpty()) {
                // Login/foreground revoked opaque handles while the tunnel stayed up. Rebuild from
                // local material only so Nodes is not stuck on read-only rows; never download a
                // subscription through the accepted connection.
                account.nodeSelection.rebuildLocallyIfNeeded()
                snapshotFlow { !account.nodeSelection.busy }.first { it }
            }
        } else if (destination == Destination.Connect || destination == Destination.Profile) {
            if (account.authenticated) account.refreshAccountUsage()
        }
    }
    if (destination == Destination.Profile) {
        if (!account.authenticated && !account.retainingCommerceSession && showLogin) {
            LoginScreen(account, registrationOpener, loginStartupReady, registrationOverride, padding, startRegistration)
            return
        }
    }
    val compact = destination == Destination.Connect || destination == Destination.Lines || destination == Destination.Plans || destination == Destination.Orders || destination == Destination.Profile
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        // Readable line lengths on tablets; no fixed-height account forms or data cards.
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 16.dp)
            .testTag("page-${destination.name}"),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
            // 我的 draws its own clean title bar (MineTopBar); a second wordmark there read as a ghosted logo.
            if (destination != Destination.Profile) BrandHeader()
            when (destination) {
                Destination.Connect -> {
                    ConnectionOverview(account, openAccount, openPlans, openNodes, connectionControlState,
                        onConnectionPowerAction, openRegistration)
                }
                Destination.Lines -> {
                    LaunchedEffect(account.authSessionGeneration, account.authenticated) { account.nodeSelection.expireIfNeeded() }
                    ConfigurationLibraryPanel(account.configurationLibrary, account.nodeSelection, account.nodes, account.resource,
                        account.localSubscription.hasCapability, account.busy || account.restoring || account.foregroundCheckPending || account.manualImport.busy,
                        authenticated = account.authenticated, connectionControl = connectionControlState,
                        onSelected = openConnect, openAccount = openAccount, latency = account.nodeLatency,
                        namingEntryId = account.manualImport.namingEntryId, onNamingDone = account.manualImport::namingHandled,
                        onImport = account.manualImport::open, onReplace = account.manualImport::openForReplacement)
                    val importer = account.manualImport
                    val importUsage by account.accountUsage.state.collectAsState()
                    com.gccwebos.android.manualimport.ManualImportHost(
                        visible = importer.visible,
                        busy = importer.busy || account.busy || account.restoring || account.foregroundCheckPending ||
                            account.clearing || account.nodeSelection.busy || importUsage.busy || importUsage.verifying,
                        error = importer.error,
                        canUseAccount = account.authenticated && account.localSubscription.sourceKind == ManualSourceKind.None,
                        canRemove = false,
                        onDismiss = importer::cancel, onBegin = importer::beginAsync, onSubmit = importer::submit,
                        onCancel = importer::cancel, onFailure = importer::failure,
                        onUseAccount = importer::useAccount, onRemove = importer::remove)
                }
                Destination.Plans -> {
                    if (!account.authenticated && !account.retainingCommerceSession) {
                        com.gccwebos.android.commerce.SignedOutPlansPanel(
                            loginEnabled = !account.busy && !account.restoring && !account.foregroundCheckPending,
                            onLogin = openAccount, onImport = openNodes)
                    } else {
                        CommercePanel(account, CommerceMode.Plans, stateOverride = commerceOverride, autoLoad = autoLoadCommerce,
                            onRecharge = openAccount, defaultSelectionRequest = plansVisit, refreshRequest = navigationRequest,
                            catalogDisplayCache = catalogDisplayCache)
                    }
                }
                Destination.Orders -> {
                    Text("我的订单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    CommercePanel(account, CommerceMode.Orders, stateOverride = commerceOverride, autoLoad = autoLoadCommerce)
                }
                Destination.Profile -> {
                    com.gccwebos.android.commerce.MinePanel(account, autoLoadCommerce, commerceOverride, onReturnToPlans = openPlans,
                        recoveryOverride = commerceRecoveryOverride, paymentOpener = paymentOpenerOverride,
                        refreshRequest = navigationRequest, onLogin = requestLogin)
                }
            }
            if (!compact) Text(stringResource(R.string.build_stage), color = Brand.Muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_robot_avatar), contentDescription = null,
            modifier = Modifier.size(34.dp), contentScale = ContentScale.Fit)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("飞连vpn", style = MaterialTheme.typography.titleLarge, fontSize = 21.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("brand-name"))
            Box(Modifier.width(94.dp).height(2.dp).background(Brush.horizontalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFFFF27DF), Brand.Blue, Brand.Cyan))))
        }
    }
}

@Composable
private fun CompactNodeList(nodes: List<com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary>) {
    Surface(Modifier.fillMaxWidth(), shape = Brand.Shape, color = Brand.Surface.copy(alpha = .72f),
        border = BorderStroke(1.dp, Brand.Border)) {
        Column {
            nodes.forEachIndexed { position, node ->
                if (position > 0) HorizontalDivider(color = Brand.Border.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp).testTag("subscription-node-${node.index}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.brand_globe), null, tint = Brand.Cyan, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("subscription-node-name-${node.index}"))
                        Text("${node.protocol.uppercase()} · ${if (node.supported) "可解析" else "暂不兼容"}",
                            color = if (node.supported) Brand.Muted else Brand.Amber, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("subscription-node-details-${node.index}"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResourceCard(state: AccountController, showOperationState: Boolean = true, showReadProgress: Boolean = true) {
    val usage by state.accountUsage.state.collectAsState()
    val verified = state.authenticated && !state.restoring && !state.foregroundCheckPending && !state.clearing &&
        !usage.verifying
    val resource = if (showReadProgress) state.resource else if (verified)
        state.resource ?: usage.resource.takeIf { usage.authenticated } else null
    val countdownEnabled = verified && !state.busy && state.error == null && resource is Resource.Available
    val now by produceState(initialValue = Instant.now(), countdownEnabled, resource) {
        value = Instant.now()
        while (countdownEnabled) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val display = resourcePresentation(state.authenticated, resource, state.busy && showReadProgress, state.error, now)
    BrandCard(Modifier.testTag("resource-card")) {
        Eyebrow("当前资源")
        Text(display.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = if (showOperationState) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        SupportingText(display.detail)
        display.countdown?.let { remaining ->
            SupportingText("剩余有效时间")
            // Readable when focused, without announcing a ticking live region every second.
            Text(remaining, style = MaterialTheme.typography.titleMedium, color = Brand.Cyan,
                modifier = Modifier.testTag("resource-countdown"))
        }
        display.facts.forEach { (label, value) ->
            HorizontalDivider(color = Brand.Border)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SupportingText(label)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (showOperationState) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("resource-error").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun TelegramPanel() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val url = "https://t.me/flylinevpn"
    BrandCard(Modifier.testTag("official-telegram")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.brand_telegram), null, modifier = Modifier.size(32.dp))
            Text("官方 Telegram 频道", style = MaterialTheme.typography.titleLarge)
        }
        SupportingText("@flylinevpn · 公告与动态")
        SupportingText("频道不是一对一客服入口。请勿公开账户、订单、付款资料或订阅链接。")
        BrandButton("前往 Telegram", {
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (_: Exception) { unavailable = true }
        }, modifier = Modifier.testTag("telegram-open"))
        TextButton(onClick = {
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("官方频道", url))
            copied = true
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (copied) "频道链接已复制" else "复制频道链接") }
        if (unavailable) SupportingText("无法打开时，可复制链接后在 Telegram 或支持访问的浏览器中打开。")
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    BrandCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SupportingText(detail)
    }
}
