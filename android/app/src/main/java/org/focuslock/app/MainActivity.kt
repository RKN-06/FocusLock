package org.focuslock.app

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.app.Activity
import android.view.WindowManager
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: FocusViewModel by viewModels()
    private var callbackUri by mutableStateOf<Uri?>(null)
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val backgroundReminder = Runnable {
        BackgroundNotice.showIfAppropriate(this)?.let(model::recordBackgroundInterruption)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep session content out of screenshots, screen recording, casting, and recents previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        QuietMode.reconcile(this)
        callbackUri = intent?.data
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF17584B), background = Color(0xFFF5F6F1))) {
                Surface(Modifier.fillMaxSize().background(glassBackdrop), color = Color.Transparent) { FocusLock(callbackUri = callbackUri, clearCallback = { callbackUri = null }, model = model) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        callbackUri = intent.data
    }
    override fun onResume() {
        super.onResume()
        backgroundHandler.removeCallbacks(backgroundReminder)
        BackgroundNotice.clear(this)
        QuietMode.reconcile(this)
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) backgroundHandler.postDelayed(backgroundReminder, 800L)
    }
    override fun onDestroy() {
        backgroundHandler.removeCallbacks(backgroundReminder)
        super.onDestroy()
    }
}

// Frosted-glass surface: translucent white fill with a bright edge, drawn over the gradient backdrop.
internal fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp)): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.40f))))
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f))), shape)

internal val glassBackdrop = Brush.linearGradient(listOf(Color(0xFFCFEFE4), Color(0xFFD9E6F7), Color(0xFFE8DCF5)))

private fun time(value: String): String = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))

@Composable
fun FocusLock(callbackUri: Uri? = null, clearCallback: () -> Unit = {}, model: FocusViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(state.student == null, state.authConfig == null) { if (state.student == null && state.authConfig == null) model.loadAuthConfig() }
    LaunchedEffect(callbackUri) { callbackUri?.let { model.completeCollegeSignIn(it); clearCallback() } }
    LaunchedEffect(state.student?.id) {
        nav.navigate(if (state.student == null) "sign-in" else if (state.student?.role == "student") "today" else "enroll") {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    LaunchedEffect(state.autoNavigateFocusId, state.student?.id) {
        val id = state.autoNavigateFocusId
        if (id != null && state.student != null) {
            nav.navigate("focus/$id") { launchSingleTop = true }
            model.consumeAutoNavigation()
        }
    }
    LaunchedEffect(state.faceQr) {
        state.faceQr?.let { waiting -> nav.navigate("face-check/${waiting.sessionId}") { launchSingleTop = true } }
    }
    // Leaving the app during focus locks it until the teacher approves; the request is sent and polled from here.
    val resumeLocked = state.resumeLock != "none" && state.focusSessionId != null && state.student != null
    LaunchedEffect(state.resumeLock, state.focusSessionId, state.student?.id) {
        val id = state.focusSessionId
        while (id != null && state.student != null && state.resumeLock == "required") { model.requestResumeApproval(id); delay(5_000) }
        while (id != null && state.student != null && state.resumeLock == "pending") { delay(3_000); model.pollResume(id) }
    }
    if (resumeLocked) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().background(glassBackdrop), color = Color.Transparent) {
            ResumeLockScreen(state.resumeLock) { state.focusSessionId?.let(model::requestResumeApproval) }
        }
    }
    // The teacher ending a session turns focus off on every phone, whichever screen is open.
    LaunchedEffect(state.focusSessionId, state.student?.id) {
        val id = state.focusSessionId
        while (id != null && state.student != null) { delay(5_000); model.refreshSessionStatus(id) }
    }
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        val focusLocked = entry?.destination?.route == "focus/{id}" && state.focusState in setOf("active", "paused")
        if (state.student != null && state.student?.role == "student" && !focusLocked && !resumeLocked) NavigationBar(containerColor = Color.White.copy(alpha = 0.55f)) {
            NavigationBarItem(selected = entry?.destination?.route == "today", onClick = { nav.navigate("today") { popUpTo("today"); launchSingleTop = true } }, icon = { Text("◷") }, label = { Text("Today") })
            NavigationBarItem(selected = entry?.destination?.route == "history", onClick = { nav.navigate("history") { popUpTo("today"); launchSingleTop = true } }, icon = { Text("≡") }, label = { Text("History") })
        }
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FocusLock", style = MaterialTheme.typography.headlineSmall)
                if (state.student != null && state.focusState !in setOf("active", "paused")) TextButton(onClick = model::signOut) { Text("Sign out") }
            }
            if (state.authConfig?.developmentLogin == true) Text("LOCAL DEMO · Sample data only", style = MaterialTheme.typography.labelSmall)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
            NavHost(navController = nav, startDestination = "sign-in", modifier = Modifier.weight(1f)) {
                composable("sign-in") { SignIn(state.loading, state.authConfig, model::signIn, model::beginCollegeSignIn,
                    state.focusSessionId?.let { focusId -> { nav.navigate("focus/$focusId") } }) }
                composable("today") {
                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var quietDisclosure by remember { mutableStateOf(false) }
                    var quietMessage by remember { mutableStateOf("") }
                    var quietOpted by remember { mutableStateOf(QuietMode.optedIn(context)) }
                    val quietSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        if (QuietMode.access(context)) { model.enableQuietForFutureSessions(); quietOpted = true }
                        else quietMessage = "DND access was not granted. Automatic focus remains available without it."
                    }
                    val quietNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) { BackgroundNotice.suppressForExpectedExternalActivity(); quietSettings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        else quietMessage = "A visible Stop action needs notification permission. DND remains off."
                    }
                    LaunchedEffect(state.student?.id, lifecycleOwner) {
                        if (state.student != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            while (true) { model.loadToday(); delay(10_000) }
                        }
                    }
                    if (quietDisclosure) AlertDialog(onDismissRequest = { quietDisclosure = false },
                        title = { Text("Automatic Do Not Disturb") },
                        text = { Text("When you open an active session, or FocusLock detects one while open, its own DND rule can start automatically. Android controls call and message exceptions; review them before enabling. Stop DND any time in the app or its notification. Attendance is unaffected.") },
                        confirmButton = { TextButton(onClick = {
                            quietDisclosure = false
                            if (!QuietMode.supported()) quietMessage = "Automatic DND needs Android 10 or newer."
                            else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                quietNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if (!QuietMode.access(context)) { BackgroundNotice.suppressForExpectedExternalActivity(); quietSettings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                            else { model.enableQuietForFutureSessions(); quietOpted = true }
                        }) { Text("Allow automatic DND") } },
                        dismissButton = { TextButton(onClick = { quietDisclosure = false }) { Text("Not now") } })
                    val today = state.sessions.filter { Instant.parse(it.startsAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                        item { Text("Today's classes", style = MaterialTheme.typography.headlineMedium) }
                        item { Text("Hello, ${state.student?.name.orEmpty()}. Your phone stays under your control.") }
                        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Automatic class focus", style = MaterialTheme.typography.titleMedium)
                            Text("A focus disclosure appears immediately after each QR scan. No setup is required here. If you accept, that class can start voluntary focus when it becomes active. Pause or Exit remains available at any time.")
                            Text(if (quietOpted) "Automatic DND enabled for active classes." else "Automatic DND is off until Android grants access.")
                            if (quietOpted) OutlinedButton(onClick = { model.stopQuiet(); quietOpted = false }) { Text("Turn off automatic DND") }
                            else OutlinedButton(onClick = { quietDisclosure = true }) { Text("Set up automatic DND") }
                            if (quietMessage.isNotBlank()) Text(quietMessage)
                        } } }
                        state.focusSessionId?.let { focusId -> item { Button(onClick = { nav.navigate("focus/$focusId") }) { Text("Return to focus · Pause or Exit") } } }
                        item { TextButton(onClick = model::loadToday, enabled = !state.loading) { Text("Refresh") } }
                        if (!state.loading && today.isEmpty()) item { Text("No classes scheduled today. Attendance from earlier sessions is in History.") }
                        items(today, key = { it.id }) { session -> SessionCard(session) { nav.navigate("session/${session.id}") } }
                        item { Text("Camera scanning is optional. Ask your teacher to confirm attendance if you cannot or choose not to scan.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    val context = LocalContext.current
                    var quietDisclosure by remember { mutableStateOf(false) }
                    var quietMessage by remember { mutableStateOf("") }
                    val quietSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        if (QuietMode.access(context)) model.enableQuiet(id)
                        else quietMessage = "DND access was not granted. You can keep using attendance and course resources."
                    }
                    val quietNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) { BackgroundNotice.suppressForExpectedExternalActivity(); quietSettings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        else quietMessage = "Notifications are needed for the visible Stop quiet mode action."
                    }
                    LaunchedEffect(id, state.student?.id) { if (state.student != null) model.loadDetail(id) }
                    LaunchedEffect(id) {
                        while (true) { delay(1000); model.refreshQuietStatus() }
                    }
                    if (quietDisclosure) AlertDialog(onDismissRequest = { quietDisclosure = false },
                        title = { Text("Automatic quiet mode for class") },
                        text = { Text("After you allow Do Not Disturb access once, FocusLock will turn on its own DND rule when you enter an active class session. It quiets notifications until the signed session timer expires, the session ends, or you tap Stop. Android may silence calls or messages depending on your DND exceptions; check those settings before enabling. You can always use your phone, dialer and messages, and stop quiet mode in the app or notification. Attendance and voluntary focus are unchanged if you decline.") },
                        confirmButton = { TextButton(onClick = {
                            quietDisclosure = false
                            if (!QuietMode.supported()) quietMessage = "Automatic quiet mode needs Android 10 or newer."
                            else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                quietNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if (!QuietMode.access(context)) { BackgroundNotice.suppressForExpectedExternalActivity(); quietSettings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                            else model.enableQuiet(id)
                        }) { Text("Allow automatic quiet mode") } },
                        dismissButton = { TextButton(onClick = { quietDisclosure = false }) { Text("Not now") } })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                        item { TextButton(onClick = { nav.popBackStack() }) { Text("Back") } }
                        state.detail?.let { session ->
                            item { Text(session.course, style = MaterialTheme.typography.labelLarge) }
                            item { Text(session.title, style = MaterialTheme.typography.headlineMedium) }
                            item { Text("${time(session.startsAt)}\nUntil ${time(session.endsAt)}") }
                            item { Text("Session: ${session.state.replace('_', ' ')}") }
                            if (session.state == "active") item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Quiet notifications", style = MaterialTheme.typography.titleLarge)
                                    Text(if (state.quietRequested) "FocusLock quiet mode is requested for this session. Android DND exceptions control which calls and messages alert you." else "Optional: automatically turn on Do Not Disturb when you enter an active session.")
                                    if (state.quietRequested) OutlinedButton(onClick = model::stopQuiet) { Text("Stop quiet mode now") }
                                    else Button(onClick = { quietDisclosure = true }) { Text("Set up automatic quiet mode") }
                                    if (quietMessage.isNotBlank()) Text(quietMessage)
                                }
                            }
                            item { Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Attendance: ${session.status ?: "Not recorded"}", style = MaterialTheme.typography.titleMedium)
                                Text("QR check-ins and teacher confirmations appear here. If a record looks wrong, ask your teacher to correct it.")
                            } } }
                            if (session.pendingReview || session.id in state.pendingLocal) item { Text("Pending teacher review. This is not recorded attendance yet.", color = MaterialTheme.colorScheme.tertiary) }
                            if (session.state in setOf("checkin_pending", "active") && (session.status == null || state.focusState !in setOf("active", "paused"))) item { Button(onClick = { model.prepareScan(); nav.navigate("scan/${session.id}") }) { Text(if (session.status == null) "Scan classroom QR" else "Scan QR to rejoin focus") } }
                            if (session.status == null && session.state != "ended") item { TextButton(onClick = { model.prepareScan(); nav.navigate("scan/${session.id}") }) { Text("Camera unavailable? Request teacher review") } }
                            item { Text("Focus is your choice", style = MaterialTheme.typography.titleLarge) }
                            item { Text("Scanning is optional. A QR check-in is evidence of participation, not proof of physical presence. This app does not lock your phone.") }
                            if (session.state == "active") item { Button(onClick = { nav.navigate("focus/${session.id}") }) { Text("Open voluntary focus") } }
                            item { Text("Course resources", style = MaterialTheme.typography.titleLarge) }
                            if (state.receiverState == "reduced_evidence") item { Text("Classroom receiver unavailable or not configured. Bluetooth evidence may be missing; ask your teacher for review. Attendance is unchanged.", color = MaterialTheme.colorScheme.tertiary) }
                            if (state.resources.isEmpty()) item { Text("No course documents attached yet.") }
                            items(state.resources, key = { it.id }) { resource ->
                                OutlinedCard(onClick = { nav.navigate("resource/${resource.id}") }) { Column(Modifier.padding(16.dp)) {
                                    Text(resource.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${resource.kind.uppercase()} · ${if (resource.downloadable) "Offline copy allowed" else "View only"}")
                                } }
                            }
                        }
                        item { OutlinedButton(onClick = { model.loadDetail(id) }, enabled = !state.loading) { Text("Refresh attendance") } }
                    }
                }
                composable("scan/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    ScanScreen(id, state.loading, id in state.pendingLocal || state.detail?.pendingReview == true, state.error, state.checkInResult,
                        onQr = { model.acceptScannedCheckIn(id, it) }, onDecline = { model.declineScannedCheckIn(id) }, onReview = { model.requestReview(id, it) }, onBack = { nav.popBackStack() })
                }
                composable("face-check/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    FaceVerifyScreen(template = model.faceTemplate(),
                        onMatched = { nav.popBackStack(); model.faceMatched(id) },
                        onGiveUp = { reason -> nav.popBackStack(); model.faceGaveUp(id, reason) },
                        onCancel = { nav.popBackStack(); model.faceCancelled() })
                }
                composable("enroll") {
                    FaceEnrollScreen(state.faceRoster, state.loading, state.faceMessage, state.error,
                        onRefresh = model::loadFaceRoster, onEnroll = model::enrollFace, onDelete = model::deleteFace)
                }
                composable("history") {
                    var privacyKind by remember { mutableStateOf<String?>(null) }
                    var privacySession by remember { mutableStateOf<Int?>(null) }
                    var privacyReason by remember { mutableStateOf("") }
                    LaunchedEffect(state.student?.id) { if (state.student != null) model.loadHistory() }
                    if (privacyKind != null) AlertDialog(onDismissRequest = { privacyKind = null },
                        title = { Text(if (privacyKind == "correction") "Request attendance correction" else "Request data deletion") },
                        text = { OutlinedTextField(value = privacyReason, onValueChange = { privacyReason = it },
                            label = { Text("Reason (at least 10 characters)") }, minLines = 3) },
                        confirmButton = { TextButton(onClick = {
                            model.submitPrivacyRequest(privacyKind!!, privacySession, privacyReason.trim())
                            privacyKind = null; privacyReason = ""
                        }, enabled = privacyReason.trim().length >= 10) { Text("Send request") } },
                        dismissButton = { TextButton(onClick = { privacyKind = null }) { Text("Cancel") } })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                        item { Text("Attendance history", style = MaterialTheme.typography.headlineMedium) }
                        item { Text("Your records only · QR check-ins and teacher confirmations") }
                        item { TextButton(onClick = model::loadHistory, enabled = !state.loading) { Text("Refresh") } }
                        if (!state.loading && state.history.isEmpty()) item { Text("No attendance records yet.") }
                        items(state.history, key = { it.session.id }) { record ->
                            SessionCard(record.session.copy(status = record.status)) { nav.navigate("session/${record.session.id}") }
                            TextButton(onClick = { privacyKind = "correction"; privacySession = record.session.id }) { Text("Request correction") }
                        }
                        item { OutlinedButton(onClick = { privacyKind = "deletion"; privacySession = null }) { Text("Request data deletion") } }
                        items(state.privacyRequests, key = { "request-${it.id}" }) { request ->
                            Text("${request.kind.replaceFirstChar { it.uppercase() }} request #${request.id}: ${request.status}${request.resolution?.let { " · $it" } ?: ""}")
                        }
                    }
                }
                composable("focus/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    val context = LocalContext.current
                    var disclosure by remember { mutableStateOf(false) }
                    var bleDisclosure by remember { mutableStateOf(false) }
                    var vpnMessage by remember { mutableStateOf("") }
                    var phoneMessage by remember { mutableStateOf("") }
                    var leaveWarning by remember { mutableStateOf(false) }
                    var exitDialog by remember { mutableStateOf(false) }
                    var scanningExit by remember { mutableStateOf(false) }
                    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
                    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
                    var vpnRunning by remember { mutableStateOf(false) }
                    var bleStatus by remember { mutableStateOf(BleFeature.status) }
                    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        if (result.resultCode == Activity.RESULT_OK && state.focusState == "active") VpnFeature.start(context, id)
                        else { model.stopVpnConsent(); vpnMessage = "Local VPN was not enabled. Focus and attendance continue without it." }
                    }
                    fun prepareVpn() {
                        val intent = VpnFeature.consentIntent(context)
                        if (intent == null) VpnFeature.start(context, id) else { BackgroundNotice.suppressForExpectedExternalActivity(); consent.launch(intent) }
                    }
                    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) model.enableVpnConsent { prepareVpn() } else vpnMessage = "Notification permission is needed for the visible VPN status. Continue without VPN."
                    }
                    val focusReminderPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        model.startFocus(id)
                    }
                    val blePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                        if (grants.values.all { it }) model.startPresence(id)
                        else bleStatus = "permission_declined"
                    }
                    LaunchedEffect(id, state.focusState) {
                        while (state.focusState in setOf("active", "paused")) { delay(1000); model.tickFocus(id) }
                    }
                    LaunchedEffect(id, state.focusState, state.exitRequest) {
                        while (state.focusState in setOf("active", "paused") && state.exitRequest == "pending") { delay(3_000); model.pollFocusExit(id) }
                    }
                    BackHandler(enabled = state.focusState in setOf("active", "paused")) { leaveWarning = true }
                    if (leaveWarning) AlertDialog(onDismissRequest = { leaveWarning = false },
                        title = { Text("Leave focus?") },
                        text = { Text("Focus is still running. If you leave this screen or the app, your teacher may see an interruption flag, and focus does not stop. To end focus early you need your teacher's QR code or approval.") },
                        confirmButton = { TextButton(onClick = { leaveWarning = false }) { Text("Stay focused") } },
                        dismissButton = { TextButton(onClick = { leaveWarning = false; nav.popBackStack() }) { Text("Leave screen anyway") } })
                    if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false },
                        title = { Text("End focus early?") },
                        text = { Text("Ending focus before the teacher ends the session needs the teacher's current QR code or the teacher's approval. Attendance is unchanged.") },
                        confirmButton = { TextButton(onClick = { exitDialog = false; scanningExit = true }) { Text("Scan teacher's QR") } },
                        dismissButton = { TextButton(onClick = { exitDialog = false; model.requestFocusExitApproval(id) }) { Text("Ask teacher to approve") } })
                    if (scanningExit) AlertDialog(onDismissRequest = { scanningExit = false },
                        title = { Text("Scan the teacher's QR") },
                        text = {
                            if (!cameraGranted) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Camera access is needed to scan the teacher's code. You can also ask the teacher to approve instead.")
                                Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera for this scan") }
                            }
                            else QrScanner(onQr = { value -> scanningExit = false; model.exitFocusWithQr(id, value) }, onError = { scanningExit = false; phoneMessage = it }, modifier = Modifier.fillMaxWidth().height(260.dp))
                        },
                        confirmButton = { TextButton(onClick = { scanningExit = false }) { Text("Cancel") } })
                    LaunchedEffect(id, state.focusState) {
                        while (state.focusState in setOf("active", "paused")) {
                            vpnRunning = VpnFeature.reconcile(context)
                            bleStatus = BleFeature.status
                            if (state.focusState != "active" && vpnRunning) VpnFeature.stop(context)
                            delay(1000)
                        }
                        if (VpnFeature.reconcile(context)) VpnFeature.stop(context)
                        vpnRunning = false
                    }
                    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
                        title = { Text("Optional local VPN focus") },
                        text = { Text("If you choose this, Android will ask for VPN consent. FocusLock routes other apps' IPv4 and IPv6 traffic into a local interface and discards it. FocusLock remains online for course content and session APIs. No packet contents, destinations or browsing history are inspected, saved or sent. This replaces an existing VPN while active. It does not block offline apps, other profiles or tethered devices. Calls, SMS, Wi-Fi calling, RCS and MMS may behave differently across carriers. You can stop immediately in the app or notification. Attendance is unchanged if you decline.") },
                        confirmButton = { TextButton(onClick = {
                            disclosure = false
                            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else model.enableVpnConsent { prepareVpn() }
                        }) { Text("Continue to Android VPN consent") } },
                        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Continue without VPN") } })
                    if (bleDisclosure) AlertDialog(onDismissRequest = { bleDisclosure = false },
                        title = { Text("Optional classroom Bluetooth evidence") },
                        text = { Text("With your choice, this phone broadcasts a rotating random-looking token for a short time. An authorized receiver in the classroom can associate a sighting with this session. That association is location-related information. It is approximate evidence that a device was nearby, not proof of your presence; relay attacks and outages remain possible. No name or roll number is broadcast. You may decline, Pause or Exit at any time. Attendance stays available without Bluetooth.") },
                        confirmButton = { TextButton(onClick = {
                            bleDisclosure = false
                            if (Build.VERSION.SDK_INT >= 31) blePermission.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
                            else model.startPresence(id)
                        }) { Text("Enable Bluetooth evidence") } },
                        dismissButton = { TextButton(onClick = { bleDisclosure = false }) { Text("Continue without Bluetooth") } })
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize().padding(vertical = 8.dp).glass().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text("Voluntary focus", style = MaterialTheme.typography.headlineMedium)
                        Text("Status: ${state.focusState}")
                        Text("Your phone remains yours. Focus participation and attendance are separate. An interruption does not tell us which app was opened or why.")
                        val classState = state.detail?.takeIf { it.id == id }?.state
                        if (state.focusState == "ready" || state.focusState == "ended") {
                            if (classState == "ended") Text("This session has ended. Focus cannot be started or rejoined.", color = MaterialTheme.colorScheme.tertiary)
                            else Text("Focus starts by itself after you scan the teacher's QR and pass the face check, once the class is active. There is no way to start it without both.", color = MaterialTheme.colorScheme.tertiary)
                            if (state.student != null && classState != "ended") Button(onClick = { model.prepareScan(); nav.navigate("scan/$id") }) { Text(if (state.focusState == "ended") "Scan QR to rejoin focus" else "Scan QR to join focus") }
                        }
                        if (state.focusState == "active") Button(onClick = { model.pauseFocus(id) }) { Text("Pause now") }
                        if (state.focusState == "paused") Button(onClick = { model.resumeFocus(id) }) { Text("Resume") }
                        if (state.focusState in setOf("active", "paused")) OutlinedButton(onClick = { exitDialog = true }, enabled = state.exitRequest != "pending") { Text("Exit focus now") }
                        if (state.exitRequest == "pending") Text("Waiting for your teacher to approve. Focus continues until they respond.", color = MaterialTheme.colorScheme.tertiary)
                        if (state.exitRequest == "denied") Text("Your teacher did not approve ending focus. You can scan their QR instead.", color = MaterialTheme.colorScheme.error)
                        if (state.focusState in setOf("active", "paused")) {
                            OutlinedButton(onClick = {
                                BackgroundNotice.suppressForExpectedExternalActivity()
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
                                    .onFailure { phoneMessage = "No phone dialer is available on this device." }
                            }) { Text("Open phone") }
                        }
                        if (phoneMessage.isNotBlank()) Text(phoneMessage, color = MaterialTheme.colorScheme.error)
                        if (BuildConfig.ENABLE_LOCAL_VPN && state.focusState == "active") {
                            if (vpnRunning) OutlinedButton(onClick = { VpnFeature.stop(context); model.stopVpnConsent(); vpnRunning = false }) { Text("Stop local VPN now") }
                            else Button(onClick = { disclosure = true }) { Text("Enable optional local VPN") }
                        }
                        if (vpnMessage.isNotBlank()) Text(vpnMessage)
                        if (BuildConfig.ENABLE_BLE_PRESENCE && state.focusState == "active") {
                            if (bleStatus == "advertising" || bleStatus == "starting") OutlinedButton(onClick = { model.stopPresence(); bleStatus = "off" }) { Text("Stop Bluetooth now") }
                            else Button(onClick = { bleDisclosure = true }) { Text("Enable optional Bluetooth evidence") }
                            Text("Bluetooth: $bleStatus. Receiver: ${state.receiverState}. Missing evidence never marks absence.")
                        }
                        Text("Pause works without internet. Ending focus early needs the teacher's current QR or approval, so it needs a connection. The signed policy still expires on this device's elapsed clock, and focus turns off for everyone when the teacher ends the session. If the app cannot report an event, the teacher's status is unknown.", style = MaterialTheme.typography.bodySmall)
                        Text("While focus is active, leaving the app with the screen on may show a local reminder and send a neutral background-interruption flag for teacher review. Screen-off and an approved exit do not trigger it. FocusLock cannot identify another app or why you left. Attendance is unchanged.", style = MaterialTheme.typography.bodySmall)
                        if (state.focusState !in setOf("active", "paused")) TextButton(onClick = { nav.popBackStack() }) { Text("Back to session") }
                    }
                }
                composable("resource/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val resource = state.resources.find { it.id == backStack.arguments!!.getInt("id") }
                    if (resource == null) Text("Resource unavailable. Return to the session and refresh.")
                    else ResourceViewer(resource) { nav.popBackStack() }
                }
            }
        }
    }
}

@Composable
private fun SignIn(loading: Boolean, config: AuthConfig?, submit: (String, String) -> Unit,
                   collegeSignIn: () -> Unit, offlineFocus: (() -> Unit)?) {
    var email by rememberSaveable { mutableStateOf("student@focuslock.test") }
    var password by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(20.dp), modifier = Modifier.padding(vertical = 16.dp).glass()) {
        item { Text("Welcome to class.", style = MaterialTheme.typography.headlineLarge) }
        if (config?.developmentLogin == true) {
            item { Text("Sign in to see your classes and attendance. Development accounts only.") }
            item { OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Demo password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { Button(onClick = { submit(email.trim().lowercase(), password.trim()); password = "" }, enabled = !loading && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") } }
            item { Text("Use DEV_PASSWORD from your local .env. No phone permissions are required. Teachers can confirm attendance without the app.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item { Text("Use your college account to see classes and attendance.") }
            item { Button(onClick = collegeSignIn, enabled = !loading && config?.collegeOidc == true && config.authorizationEndpoint != null && config.androidClientId != null && config.androidRedirectUri != null) { Text("Sign in with college") } }
            if (config?.collegeOidc == false) item { Text("College authentication needs administrator configuration.") }
        }
        offlineFocus?.let { open -> item { OutlinedButton(onClick = open) { Text("Open saved focus session") } } }
    }
}

@Composable
private fun SessionCard(session: ClassSession, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth().glass()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.course, style = MaterialTheme.typography.labelLarge)
            Text(session.title, style = MaterialTheme.typography.titleLarge)
            Text(time(session.startsAt))
            Text("Attendance: ${session.status ?: "Not recorded"}", color = MaterialTheme.colorScheme.primary)
            Text("View session →", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ResourceViewer(resource: CourseResource, back: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ResourceStore(context) }
    val scope = rememberCoroutineScope()
    var file by remember(resource.id) { mutableStateOf<File?>(null) }
    var text by remember(resource.id) { mutableStateOf<String?>(null) }
    var bitmap by remember(resource.id) { mutableStateOf<Bitmap?>(null) }
    var page by remember(resource.id) { mutableIntStateOf(0) }
    var pages by remember(resource.id) { mutableIntStateOf(0) }
    var error by remember(resource.id) { mutableStateOf<String?>(null) }
    var busy by remember(resource.id) { mutableStateOf(false) }
    var saved by remember(resource.id) { mutableStateOf(store.cached(resource)) }
    suspend fun fetch(save: Boolean) {
        busy = true; error = null
        try {
            val opened = store.open(resource, save)
            file = opened
            saved = store.cached(resource)
            if (resource.kind == "text") text = store.readText(opened)
        } catch (failure: Exception) { error = failure.message ?: "Could not open resource" }
        finally { busy = false }
    }
    LaunchedEffect(resource.id) { fetch(false) }
    LaunchedEffect(file, page) {
        if (resource.kind == "pdf") file?.let { source ->
            try { val result = store.renderPage(source, page); bitmap = result.first; pages = result.second }
            catch (failure: Exception) { error = failure.message ?: "Could not render PDF" }
        }
    }
    DisposableEffect(file) { val transient = file?.takeIf { it.parentFile == context.cacheDir }; onDispose { transient?.delete() } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = back) { Text("Back") } }
        item { Text(resource.title, style = MaterialTheme.typography.headlineMedium) }
        item { Text("College resource · ${resource.kind.uppercase()}") }
        if (resource.downloadable) item {
            OutlinedButton(onClick = { scope.launch { fetch(true) } }, enabled = !saved && !busy) { Text(if (saved) "Saved privately for offline use" else "Save private offline copy") }
        }
        if (saved) item { TextButton(onClick = { if (store.removeCached(resource)) { saved = false; file = null; text = null; bitmap = null } }) { Text("Delete offline copy") } }
        if (busy) item { CircularProgressIndicator() }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (resource.kind == "text") text?.let { body -> item { Text(body) } }
        if (resource.kind == "pdf") {
            bitmap?.let { image -> item { Image(image.asImageBitmap(), contentDescription = "PDF page ${page + 1}", modifier = Modifier.fillMaxWidth()) } }
            if (pages > 0) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                Text("${page + 1} / $pages", modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Next") }
            } }
        }
    }
}
