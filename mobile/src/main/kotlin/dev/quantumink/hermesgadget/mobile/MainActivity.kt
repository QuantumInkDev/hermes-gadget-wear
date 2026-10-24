package dev.quantumink.hermesgadget.mobile

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetLaunch
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetStore
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgets
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetsPage
import dev.quantumink.hermesgadget.mobile.widgets.LaunchDecision
import dev.quantumink.hermesgadget.mobile.widgets.WidgetLaunchNotice
import dev.quantumink.hermesgadget.mobile.widgets.WidgetLaunchRequest
import dev.quantumink.hermesgadget.mobile.widgets.decideLaunch
import dev.quantumink.hermesgadget.mobile.widgets.phoneSelectionLocked
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.relay.session.InboxInput
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRoute
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val inboxOwner get() = (application as CompanionApplication).inbox
    private var inboxHandle: String? = null
    private var pendingInbox by mutableStateOf<String?>(null)
    private var inboxRoute by mutableStateOf<InboxRoute?>(
        null
    )
    private var inboxInput by mutableStateOf<InboxInput?>(
        null
    )
    private var inboxNotice by mutableStateOf("")
    private var inboxActionVersion = 0L
    private val inboxNavigation: PhoneInboxNavigation<PhoneInboxOwner.Resolved> by lazy {
        PhoneInboxNavigation<PhoneInboxOwner.Resolved> { handle, done ->
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { inboxOwner.resolve(handle) }.getOrNull()
                    }
                done(result)
                pendingInbox = inboxNavigation.pending
            }
        }
    }
    private fun inboxAnswerNotice(accepted: Boolean) = if (accepted) {
        "Answer accepted. Refresh for current progress."
    } else {
        "Answer not confirmed. Fetch current input before any further action."
    }
    private fun inboxReady() =
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    private fun inboxLocked() =
        phoneSelectionLocked(conversation.state.value) || inboxInput != null ||
            conversation.state.value.relay.phase ==
            RelayPhase.ATTACHING
    private fun showInbox(result: PhoneInboxOwner.Resolved?) {
        inboxRoute = result?.route
        inboxNotice =
            if (result ==
                null
            ) {
                "Inbox item unavailable. Refresh or open its reviewed profile."
            } else {
                "Read-only retained detail"
            }
        inboxInput = null
        destination = "inbox"
        inboxOwner.load()
    }
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { inboxOwner.load() }
    private fun receiveInbox(intent: Intent): Boolean {
        if (intent.action != InboxNotifications.ACTION) return false
        inboxActionVersion++
        inboxNavigation.cancel()
        navigation.cancel()
        speechNavigation.cancel()
        incomingWidget = null
        pendingWidget = null
        incomingAction = null
        pendingAction = null
        inboxHandle = InboxNotifications.handle(intent)
        pendingInbox = null
        if (!inboxLocked()) {
            inboxRoute = null
            inboxInput = null
        }
        inboxNotice = if (inboxHandle == null) "Inbox item unavailable." else ""
        return true
    }
    private fun consumeInbox() {
        val handle = inboxHandle ?: return
        inboxHandle = null
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            pendingInbox = handle
            return
        }
        if (inboxLocked()) {
            pendingInbox = handle
            return
        }
        inboxNavigation.request(handle, ::inboxReady, ::inboxLocked, ::showInbox)
        pendingInbox = inboxNavigation.pending
    }

    private fun inboxAction(continueTarget: Boolean) {
        val route = inboxRoute ?: return
        val handle = route.copy?.handle ?: route.task?.handle ?: return
        if (phoneSelectionLocked(conversation.state.value)) {
            pendingInbox = handle
            return
        }
        val generation = ++inboxActionVersion
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { inboxOwner.resolve(handle) }.getOrNull()
                }
            if (generation != inboxActionVersion || result == null ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
                phoneSelectionLocked(conversation.state.value)
            ) {
                return@launch
            }
            val current = conversation.state.value
            if (current.profile != result.profile || current.page?.key != result.page.key ||
                !current.visible
            ) {
                conversation.open(result.profile, result.page)
            }
            conversation.inboxAction(handle, continueTarget) { input ->
                runOnUiThread {
                    if (generation == inboxActionVersion) {
                        inboxInput = input
                        inboxNotice =
                            if (input ==
                                null
                            ) {
                                "Current input unavailable. Continue the exact " +
                                    "conversation before fetching again."
                            } else {
                                "Fresh current input"
                            }
                    }
                }
            }
            if (conversation.state.value.connection.status !in
                setOf(
                    ConnectionStatus.PAIRED,
                    ConnectionStatus.CONNECTING
                )
            ) {
                connectPhone()
            }
            inboxNotice =
                if (continueTarget) {
                    "Connecting to the exact conversation. " +
                        "Fetch current input deliberately after it is attached."
                } else {
                    "Fetching current input"
                }
        }
    }

    private val catalogNetwork = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (RelaySettings.enabled(this@MainActivity)) {
                runCatching { CatalogRepository.get(this@MainActivity).refresh() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        getSystemService(android.net.ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(catalogNetwork)
    }

    private var destination by mutableStateOf("profiles")
    private var speechTarget by mutableStateOf<SpeechSettingsTarget?>(null)
    private var speechProfileKey by mutableStateOf<String?>(null)
    private val speechNavigation by lazy {
        PhoneProfileNavigation(lifecycleScope, { key, _ ->
            CatalogRepository.get(this).currentProfile(key)
        }, { page -> CatalogRepository.get(this).pages.value.any { it === page } })
    }
    private var incomingAction: String? = null
    private var pendingAction by mutableStateOf<String?>(null)
    private var quickActionId by mutableStateOf<String?>(null)
    private var quickActionLaunch by mutableStateOf(0L)
    private var quickActionNotice by mutableStateOf("")
    private val quickActionStore by lazy { QuickActionStore(this) }
    private var incomingWidget: WidgetLaunchRequest? = null
    private var pendingWidget by mutableStateOf<WidgetLaunchRequest?>(null)
    private var widgetUnavailable by mutableStateOf(false)
    private var widgetEditId by mutableStateOf<Int?>(null)
    private val navigation by lazy {
        PhoneProfileNavigation(lifecycleScope, { key, widget ->
            if (widget != null && !HomeWidgetLaunch.valid(this, widget)) {
                null
            } else {
                val repository = CatalogRepository.get(this)
                repository.savedPages()
                repository.currentProfile(key)?.takeIf {
                    it.first.status != CatalogStatus.WITHDRAWN
                }
            }
        }, { page -> CatalogRepository.get(this).pages.value.any { it === page } })
    }

    override fun onStop() {
        inboxActionVersion++
        inboxNavigation.suspend()
        pendingInbox = inboxNavigation.pending
        inboxInput = null
        navigation.cancel()
        speechNavigation.cancel()
        started = false
        conversation.hide()
        getSystemService(android.net.ConnectivityManager::class.java)
            .unregisterNetworkCallback(catalogNetwork)
        super.onStop()
    }
    private var enabled by mutableStateOf(false)
    private val conversation by lazy {
        androidx.lifecycle.ViewModelProvider(this)[PhoneConversation::class.java]
    }
    private var started by mutableStateOf(false)
    private var pendingPhoneUrl: String? = null
    private val phoneNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requested = pendingPhoneUrl
            pendingPhoneUrl = null
            if (conversation.state.value.visible &&
                conversation.state.value.profile?.endpoint?.url == requested
            ) {
                if (granted) {
                    conversation.connect()
                } else {
                    conversation.notice(
                        "Local network permission is needed for LAN/tailnet access."
                    )
                }
            }
        }
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            conversation.recordingUnavailable(
                if (granted) {
                    "Microphone ready. Hold to talk again."
                } else {
                    "Microphone permission was declined."
                }
            )
        }
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            enableRelay()
        }
    private val association =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            RelaySettings.report(
                getString(
                    if (it.resultCode ==
                        RESULT_OK
                    ) {
                        R.string.association_ready
                    } else {
                        R.string.association_cancelled
                    }
                )
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enabled = RelaySettings.enabled(this)
        destination = savedInstanceState?.getString("phone_destination")?.takeIf {
            it in
                setOf(
                    "profiles", "conversation", "watch", "settings", "tiles", "boxes",
                    "manual", "speech", "appearance", "home-widgets", "quick-actions",
                    "setup-transfer", "inbox"
                )
        } ?: "profiles"
        speechProfileKey = savedInstanceState?.getString("speech_profile_key")
        if (savedInstanceState == null) {
            if (!receiveInbox(intent) && !receiveAction(intent)) receiveWidget(intent)
        } else {
            inboxHandle =
                savedInstanceState.getString("inbox_handle")?.takeIf {
                    it.matches(Regex("[0-9a-f]{64}"))
                }
            pendingWidget = HomeWidgetLaunch.restore(savedInstanceState.getBundle("pending_widget"))
            incomingAction = savedInstanceState.getString("incoming_action")
            pendingAction = savedInstanceState.getString("pending_action")
            quickActionId = savedInstanceState.getString("quick_action_id")
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                consumeAction()
                consumeWidget()
                consumeInbox()
                if (destination == "speech" && speechProfileKey != null && speechTarget == null) {
                    withContext(Dispatchers.IO) {
                        CatalogRepository.get(this@MainActivity).savedPages()
                    }
                    requestSpeech(null, speechProfileKey)
                }
            }
        }
        setContent {
            val appearance = remember { AppearancePreferences(this@MainActivity) }
            val mode by appearance.mode.collectAsStateWithLifecycle()
            PhoneAppearance(mode) {
                val phone by conversation.state.collectAsStateWithLifecycle()
                val inboxSnapshot by inboxOwner.state.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(inboxSnapshot?.revision) {
                    val handle = inboxRoute?.copy?.handle ?: inboxRoute?.task?.handle
                    if (handle != null &&
                        inboxSnapshot?.let {
                            InboxRoute.resolve(
                                it,
                                handle
                            )
                        } == null
                    ) {
                        inboxInput = null
                        inboxRoute = null
                        inboxNotice =
                            "Inbox item unavailable."
                    }
                }
                val status by RelaySettings.status.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(started, destination) {
                    if (destination == "conversation" && started && !phone.visible) {
                        phone.profile?.let { conversation.open(it, phone.page) }
                            ?: run { destination = "profiles" }
                    }
                }
                val directoryState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val directoryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
                fun navigate(value: String) {
                    if (value in setOf("speech", "settings") &&
                        speechSettingsLocked(conversation.state.value)
                    ) {
                        return
                    }
                    inboxActionVersion++
                    inboxNavigation.cancel()
                    inboxInput = null
                    pendingInbox = null
                    inboxHandle = null
                    speechNavigation.cancel()
                    speechTarget = null
                    speechProfileKey = null
                    navigation.cancel()
                    pendingAction = null
                    incomingAction = null
                    quickActionId = null
                    quickActionNotice = ""
                    pendingWidget = null
                    widgetUnavailable = false
                    widgetEditId = null
                    if (destination in setOf("conversation", "inbox")) conversation.hide()
                    destination = value
                }
                androidx.activity.compose.BackHandler(
                    destination != "profiles"
                ) {
                    navigate(
                        when (destination) {
                            "conversation", "watch", "settings" -> "profiles"
                            "speech" -> if (speechProfileKey == null) "settings" else "profiles"
                            "tiles" -> "watch"
                            else -> "settings"
                        }
                    )
                }
                PhoneScaffold(
                    topBar = {
                        androidx.compose.foundation.layout.Box(Modifier.statusBarsPadding()) {
                            if (destination != "conversation") PhoneBrandHeader()
                        }
                    },
                    bottomBar = {
                        if (WindowInsets.ime.getBottom(LocalDensity.current) ==
                            0
                        ) {
                            PhoneNavigationBar(
                                if (destination ==
                                    "tiles"
                                ) {
                                    "watch"
                                } else {
                                    destination
                                },
                                ::navigate
                            )
                        }
                    }
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(
                            padding
                        ).consumeWindowInsets(padding).imePadding()
                    ) {
                        if (pendingAction != null) {
                            androidx.compose.material3.Text(
                                "An action is waiting. Finish the current turn or approval, then open its review.",
                                Modifier.padding(horizontal = 20.dp)
                            )
                            androidx.compose.material3.TextButton({
                                incomingAction = pendingAction
                                pendingAction = null
                                consumeAction()
                            }, enabled = !phoneSelectionLocked(phone)) {
                                androidx.compose.material3.Text("Open action review")
                            }
                            androidx.compose.material3.TextButton({ pendingAction = null }) {
                                androidx.compose.material3.Text("Dismiss action")
                            }
                        }
                        if (quickActionNotice.isNotBlank()) {
                            androidx.compose.material3.Text(
                                quickActionNotice,
                                Modifier.padding(20.dp)
                            )
                        }
                        WidgetLaunchNotice(
                            pendingWidget != null,
                            widgetUnavailable,
                            phoneSelectionLocked(phone),
                            widgetEditId != null,
                            { pendingWidget?.let { requestProfile(it.key, it) } },
                            {
                                widgetEditId?.let {
                                    startActivity(HomeWidgets.editIntent(this@MainActivity, it))
                                }
                            },
                            {
                                pendingWidget = null
                                widgetUnavailable = false
                                widgetEditId = null
                                navigation.cancel()
                            }
                        )
                        androidx.compose.runtime.LaunchedEffect(inboxLocked(), pendingInbox) {
                            if (!inboxLocked() && pendingInbox != null) {
                                inboxHandle = pendingInbox
                                pendingInbox = null
                                consumeInbox()
                            }
                        }
                        when (destination) {
                            "inbox" -> PhoneInboxPage(
                                inboxOwner, inboxRoute, phoneSelectionLocked(phone),
                                inboxInput, inboxNotice,
                                {
                                    inboxHandle = it
                                    consumeInbox()
                                }, { inboxAction(true) }, { inboxAction(false) },
                                { answer ->
                                    inboxInput?.let { token ->
                                        inboxInput = null
                                        conversation.inboxAnswer(token, answer) { accepted ->
                                            runOnUiThread {
                                                inboxNotice = inboxAnswerNotice(accepted)
                                            }
                                        }
                                    }
                                },
                                {
                                    if (Build.VERSION.SDK_INT >=
                                        33
                                    ) {
                                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                Modifier.weight(1f)
                            )
                            "profiles" -> directoryState.SaveableStateProvider("profiles") {
                                DirectoryPage(
                                    this@MainActivity,
                                    listState = directoryScroll,
                                    onTalk = { page -> requestProfile(page.key, null) },
                                    onBoxes = { navigate("boxes") },
                                    onManual = { navigate("manual") },
                                    modifier = Modifier.weight(1f),
                                    onSpeech = { requestSpeech(it) },
                                    speechLocked = speechSettingsLocked(phone)
                                )
                            }
                            "quick-actions" -> androidx.compose.runtime.key(quickActionLaunch) {
                                QuickActionsScreen(
                                    quickActionId,
                                    phoneSelectionLocked(phone),
                                    ::requestQuickAction,
                                    { navigate("settings") },
                                    Modifier.weight(1f)
                                )
                            }
                            "conversation" -> ConversationPage(
                                conversation,
                                phone.page,
                                ::connectPhone,
                                ::recordPhone,
                                { navigate("profiles") },
                                Modifier.weight(1f)
                            )
                            else -> Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                when (destination) {
                                    "boxes" -> CatalogPage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "manual" -> ProfilesPage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "speech" -> {
                                        val repository =
                                            remember { CatalogRepository.get(this@MainActivity) }
                                        val pages by repository.pages.collectAsStateWithLifecycle()
                                        val selected = speechTarget
                                        val available =
                                            speechProfileKey == null || selected != null &&
                                                pages.any { it === selected.page } &&
                                                selected.endpoint.isTls &&
                                                selected.page.status != CatalogStatus.WITHDRAWN
                                        SpeechSettingsPage(
                                            this@MainActivity,
                                            target = selected,
                                            available = available,
                                            permitted = {
                                                started &&
                                                    !speechSettingsLocked(
                                                        conversation.state.value
                                                    ) &&
                                                    (
                                                        speechProfileKey == null ||
                                                            selected != null &&
                                                            repository.pages.value.any {
                                                                it ===
                                                                    selected.page
                                                            }
                                                        )
                                            }
                                        ) {
                                            navigate(
                                                if (speechProfileKey ==
                                                    null
                                                ) {
                                                    "settings"
                                                } else {
                                                    "profiles"
                                                }
                                            )
                                        }
                                    }
                                    "appearance" -> AppearancePage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "tiles" -> ProfileTilesPage { navigate("watch") }
                                    "setup-transfer" -> SetupTransferPage(this@MainActivity, {
                                        navigate("settings")
                                    })
                                    "home-widgets" -> HomeWidgetsPage {
                                        navigate("settings")
                                    }
                                    "settings" -> {
                                        SettingsLink(
                                            "Quick actions",
                                            "Editable prompts and Android shortcuts"
                                        ) {
                                            navigate("quick-actions")
                                        }
                                        PhoneSettingsPage(this@MainActivity, ::navigate)
                                    }
                                    else -> WatchDashboard(
                                        enabled,
                                        status,
                                        ::requestRelay,
                                        {
                                            RelaySettings.setEnabled(this@MainActivity, false)
                                            enabled = false
                                        },
                                        ::associateWatch,
                                        { RelaySettings.stop() },
                                        { navigate("tiles") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!receiveInbox(intent) && !receiveAction(intent)) receiveWidget(intent)
        window.decorView.post {
            consumeAction()
            consumeWidget()
            consumeInbox()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            "inbox_handle",
            pendingInbox ?: inboxRoute?.copy?.handle ?: inboxRoute?.task?.handle
        )
        outState.putString("phone_destination", destination)
        outState.putString("speech_profile_key", speechProfileKey)
        outState.putString("incoming_action", incomingAction)
        outState.putString("pending_action", pendingAction)
        outState.putString("quick_action_id", quickActionId)
        pendingWidget?.let { outState.putBundle("pending_widget", HomeWidgetLaunch.bundle(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun receiveAction(value: Intent): Boolean {
        if (value.action != QuickActionShortcuts.ACTION_OPEN) return false
        inboxActionVersion++
        inboxNavigation.cancel()
        inboxHandle = null
        pendingInbox = null
        inboxInput =
            null
        navigation.cancel()
        incomingWidget = null
        pendingWidget = null
        widgetUnavailable = false
        widgetEditId = null
        incomingAction = QuickActionShortcuts.parse(value) ?: "missing"
        return true
    }

    private fun consumeAction() {
        if (!started ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            return
        }
        val id = incomingAction ?: return
        incomingAction = null
        if (phoneSelectionLocked(conversation.state.value)) {
            pendingAction = id
            return
        }
        navigation.cancel()
        conversation.hide()
        pendingAction = null
        quickActionNotice = ""
        quickActionId = id
        quickActionLaunch++
        destination = "quick-actions"
    }

    private fun requestQuickAction(
        reviewed: QuickAction,
        inputs: Map<String, String>,
        previewIsCurrent: () -> Boolean
    ) {
        if (!previewIsCurrent()) return
        val key = reviewed.profileKey ?: return
        if (phoneSelectionLocked(conversation.state.value)) {
            quickActionNotice = "Finish the current turn or approval before using an action."
            return
        }
        navigation.request(key, null, {
            previewIsCurrent() && started && destination == "quick-actions" &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }) { resolved ->
            if (!previewIsCurrent()) return@request
            val prompt = runCatching {
                QuickActionNavigation.prompt(
                    reviewed,
                    inputs,
                    quickActionStore.load(),
                    resolved,
                    conversation.state.value
                )
            }.getOrNull()
            if (prompt == null || resolved == null) {
                quickActionNotice =
                    "Action or profile changed, is unavailable, or the conversation is locked. Reopen and review the action. Nothing was sent."
            } else {
                conversation.open(resolved.second, resolved.first)
                conversation.draft(prompt)
                quickActionId = null
                quickActionNotice = ""
                destination = "conversation"
            }
        }
    }

    private fun receiveWidget(value: Intent) {
        if (value.action != HomeWidgets.ACTION_OPEN) return
        inboxActionVersion++
        inboxNavigation.cancel()
        inboxHandle = null
        pendingInbox = null
        inboxInput =
            null
        pendingAction = null
        incomingAction = null
        navigation.cancel()
        incomingWidget = HomeWidgetLaunch.parse(value)
        if (incomingWidget == null) {
            pendingWidget = null
            widgetUnavailable = true
            widgetEditId = null
        }
    }

    private fun consumeWidget() {
        if (!started ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            return
        }
        val request = incomingWidget ?: return
        incomingWidget = null
        requestProfile(request.key, request)
    }

    private fun requestSpeech(mounted: CatalogPage?, restoredKey: String? = null) {
        if (speechSettingsLocked(conversation.state.value)) return
        val key = mounted?.key ?: restoredKey ?: return
        navigation.cancel()
        val from = destination
        speechNavigation.request(key, null, {
            started && destination == from && !speechSettingsLocked(conversation.state.value) &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }) { resolved ->
            if (resolved != null && (mounted == null || mounted === resolved.first) &&
                resolved.first.status != CatalogStatus.WITHDRAWN && resolved.second.endpoint.isTls
            ) {
                navigation.cancel()
                if (destination in setOf("conversation", "inbox")) conversation.hide()
                speechProfileKey = key
                speechTarget = SpeechSettingsTarget(resolved.first, resolved.second.endpoint)
                destination = "speech"
            }
        }
    }

    private fun requestProfile(key: String, widget: WidgetLaunchRequest?) {
        speechNavigation.cancel()
        val requestedFrom = destination
        val widgetVersion = HomeWidgetStore.version()
        pendingWidget = null
        widgetUnavailable = false
        widgetEditId = null
        navigation.request(key, widget, {
            started && destination == requestedFrom &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }) { candidate ->
            val resolved = candidate.takeIf {
                widget == null || widgetVersion == HomeWidgetStore.version()
            }
            val state = conversation.state.value
            val decision = if (widget == null) {
                if (resolved == null) LaunchDecision.UNAVAILABLE else LaunchDecision.OPEN
            } else {
                decideLaunch(
                    key,
                    state.page?.key.takeIf {
                        state.visible && resolved != null &&
                            state.profile?.endpoint?.url == resolved.second.endpoint.url &&
                            state.profile.accessToken == resolved.second.accessToken
                    },
                    phoneSelectionLocked(state),
                    resolved != null
                )
            }
            when (decision) {
                LaunchDecision.IGNORE -> destination = "conversation"
                LaunchDecision.DEFER -> pendingWidget = widget
                LaunchDecision.UNAVAILABLE -> if (widget != null) {
                    widgetUnavailable = true
                    widgetEditId = widget.widgetId.takeIf { HomeWidgets.preset(this, it) != null }
                }
                LaunchDecision.OPEN -> resolved?.let {
                    conversation.open(it.second, it.first)
                    destination = "conversation"
                }
            }
        }
    }

    private fun connectPhone() {
        val profile = conversation.state.value.profile ?: return
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingPhoneUrl = profile.endpoint.url
            phoneNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            conversation.connect()
        }
    }

    private fun recordPhone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            conversation.recordingUnavailable("Allow microphone access, then hold to talk again.")
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            conversation.startRecording()
        }
    }

    private fun requestRelay() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        if (needed.isEmpty()) enableRelay() else permissions.launch(needed.toTypedArray())
    }

    private fun enableRelay() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            RelaySettings.report(getString(R.string.local_permission_needed))
            return
        }
        RelaySettings.setEnabled(this, true)
        enabled = true
        RelaySettings.report(getString(R.string.relay_idle))
    }

    @Suppress("DEPRECATION")
    private fun associateWatch() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            RelaySettings.report(getString(R.string.association_unavailable))
            return
        }
        val request = AssociationRequest.Builder().addDeviceFilter(
            BluetoothDeviceFilter.Builder().build()
        )
            .setSingleDevice(false).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                association.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Deprecated("Platform callback for Android 10–12")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                association.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }
            override fun onFailure(error: CharSequence?) {
                RelaySettings.report(getString(R.string.association_unavailable))
            }
        }
        runCatching {
            val manager = getSystemService(CompanionDeviceManager::class.java)
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, mainExecutor, callback)
            } else {
                manager.associate(request, callback, null)
            }
        }.onFailure { RelaySettings.report(getString(R.string.association_unavailable)) }
    }
}
