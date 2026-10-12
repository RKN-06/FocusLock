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
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.navigation.NavBackStackEntry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
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
import java.time.YearMonth
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
        enableEdgeToEdge()
        // Keep session content out of screenshots, screen recording, casting, and recents previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        QuietMode.reconcile(this)
        callbackUri = intent?.data
        setContent {
            var themeMode by remember { mutableStateOf(UiPrefs.load(this@MainActivity)) }
            val backdropScroll = remember { BackdropScroll() }
            FocusLockTheme(themeMode) {
                CompositionLocalProvider(LocalBackdropScroll provides backdropScroll) {
                    Box(Modifier.fillMaxSize().trackBackdropScroll(backdropScroll)) {
                        GlassBackdrop()
                        Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
                            FocusLock(callbackUri = callbackUri, clearCallback = { callbackUri = null }, model = model, themeMode = themeMode,
                                onThemeToggle = { themeMode = themeMode.next(); UiPrefs.save(this@MainActivity, themeMode) })
                        }
                    }
                }
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

private val TabRoutes = setOf("today", "timetable", "history")
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes

private fun time(value: String): String = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))

@Composable
fun FocusLock(callbackUri: Uri? = null, clearCallback: () -> Unit = {}, model: FocusViewModel = viewModel(),
              themeMode: ThemeMode = ThemeMode.System, onThemeToggle: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dnd = rememberDndSetup(model)
    LaunchedEffect(state.student?.id) { while (state.student != null) { model.refreshQuietStatus(); delay(1000) } }
    LaunchedEffect(state.student == null, state.authConfig == null) { if (state.student == null && state.authConfig == null) model.loadAuthConfig() }
    LaunchedEffect(callbackUri) { callbackUri?.let { model.completeCollegeSignIn(it); clearCallback() } }
    LaunchedEffect(state.student?.id) {
        nav.navigate(if (state.student == null) "sign-in" else if (state.student?.role == "student") "today" else "portal") {
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
        GlassScreen { ResumeLockScreen(state.resumeLock) { state.focusSessionId?.let(model::requestResumeApproval) } }
    }
    // Do Not Disturb is required while a class focus session runs; if it is off for a moment, the app locks until it is back on.
    val dndMissing = state.student != null && state.focusState in setOf("active", "paused") && !(state.dndReady && state.quietRequested && state.dndEffective)
    var dndLocked by remember { mutableStateOf(false) }
    LaunchedEffect(dndMissing) { if (dndMissing) { delay(2500); dndLocked = true } else dndLocked = false }
    val appContext = LocalContext.current
    if (dndLocked && !resumeLocked) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        GlassScreen { DndLockScreen(dnd.message) { if (QuietMode.access(appContext)) model.restoreQuiet() else dnd.open() } }
    }
    // The teacher ending a session turns focus off on every phone, whichever screen is open.
    LaunchedEffect(state.focusSessionId, state.student?.id) {
        val id = state.focusSessionId
        while (id != null && state.student != null) { delay(5_000); model.refreshSessionStatus(id) }
    }
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        val focusLocked = entry?.destination?.route == "focus/{id}" && state.focusState in setOf("active", "paused")
        val showTabs = state.student != null && state.student?.role == "student" && !focusLocked && !resumeLocked
        AnimatedVisibility(showTabs, enter = fadeIn(tween(300)) + androidx.compose.animation.slideInVertically(tween(350)) { it }, exit = fadeOut(tween(150)) + androidx.compose.animation.slideOutVertically(tween(250)) { it }) {
            GlassTabBar(
                tabs = listOf(TabItem("today", "Today", Icons.Rounded.Home), TabItem("timetable", "Timetable", Icons.Rounded.Menu), TabItem("history", "History", Icons.Rounded.DateRange)),
                selected = entry?.destination?.route,
                onSelect = { route -> nav.navigate(route) { popUpTo("today"); launchSingleTop = true } })
        }
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("FocusLock", style = MaterialTheme.typography.headlineSmall)
                    if (state.authConfig?.developmentLogin == true) GlassChip("Demo")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(onThemeToggle, themeMode.label) {
                        AnimatedContent(themeMode, transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120), 0.6f)) }, label = "theme-glyph") { mode ->
                            Text(mode.glyph, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (state.student != null && state.focusState !in setOf("active", "paused")) GTextButton(onClick = model::signOut) { Text("Sign out") }
                }
            }
            AnimatedVisibility(state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(50))) }
            AnimatedVisibility(state.error != null) { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.dp).padding(14.dp)) }
            NavHost(navController = nav, startDestination = "sign-in", modifier = Modifier.weight(1f),
                // Tabs cross-fade with a soft zoom; deeper screens push in with depth, like a native stack.
                enterTransition = {
                    if (isTabSwitch()) fadeIn(tween(280, delayMillis = 90)) + scaleIn(tween(320, delayMillis = 90), 0.95f)
                    else slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { (it * 0.24f).toInt() } + fadeIn(tween(320)) + scaleIn(tween(420, easing = FastOutSlowInEasing), 0.96f)
                },
                exitTransition = {
                    if (isTabSwitch()) fadeOut(tween(160)) + scaleOut(tween(220), 1.03f)
                    else slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { -(it * 0.14f).toInt() } + fadeOut(tween(240)) + scaleOut(tween(420, easing = FastOutSlowInEasing), 0.97f)
                },
                popEnterTransition = {
                    if (isTabSwitch()) fadeIn(tween(280, delayMillis = 90)) + scaleIn(tween(320, delayMillis = 90), 0.95f)
                    else slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { -(it * 0.14f).toInt() } + fadeIn(tween(320)) + scaleIn(tween(420, easing = FastOutSlowInEasing), 0.97f)
                },
                popExitTransition = {
                    if (isTabSwitch()) fadeOut(tween(160)) + scaleOut(tween(220), 1.03f)
                    else slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { (it * 0.24f).toInt() } + fadeOut(tween(240)) + scaleOut(tween(420, easing = FastOutSlowInEasing), 0.96f)
                }) {
                composable("sign-in") { SignIn(state.loading, state.authConfig, model::signIn, model::beginCollegeSignIn,
                    state.focusSessionId?.let { focusId -> { nav.navigate("focus/$focusId") } }) }
                composable("today") {
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(state.student?.id, lifecycleOwner) {
                        if (state.student != null) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            while (true) { model.loadToday(); delay(10_000) }
                        }
                    }
                    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
                    val selected = LocalDate.parse(selectedDay)
                    val sessionDays = state.sessions.map { Instant.parse(it.startsAt).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
                    val firstDay = minOf(LocalDate.now().minusDays(14), sessionDays.minOrNull() ?: LocalDate.now())
                    val lastDay = maxOf(LocalDate.now().plusDays(14), sessionDays.maxOrNull() ?: LocalDate.now())
                    val days = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.take(240).toList()
                    val dayClasses = state.sessions.filter { Instant.parse(it.startsAt).atZone(ZoneId.systemDefault()).toLocalDate() == selected }
                    val todayList = rememberLazyListState()
                    LazyColumn(state = todayList, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                        item { Row(Modifier.fillMaxWidth().appear(0).heroParallax(todayList), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text("Hello, ${state.student?.name?.substringBefore(' ').orEmpty()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(if (selected == LocalDate.now()) "Today's classes" else selected.format(DateTimeFormatter.ofPattern("EEEE, d MMM")), style = MaterialTheme.typography.headlineLarge)
                            }
                            GlassIconButton(model::loadToday, "Refresh") { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } }
                        val waiting = state.pendingLocal.size
                        val alertCount = listOf(!state.dndReady, state.focusSessionId != null, waiting > 0).count { it }
                        if (alertCount > 0) item { SectionLabel("Alerts", alertColor(), Icons.Rounded.Notifications, Modifier.appear(1), count = alertCount) }
                        if (!state.dndReady) item { AlertCard("Do Not Disturb is required", dnd.message.ifBlank { "Turn it on to join class sessions." }, "Set up", Modifier.appear(1)) { dnd.open() } }
                        state.focusSessionId?.let { focusId -> item { AlertCard("Focus in progress", "Pause or exit any time.", "Return to focus", Modifier.appear(1)) { nav.navigate("focus/$focusId") } } }
                        if (waiting > 0) item { AlertCard("Waiting for teacher review", "$waiting check-in${if (waiting == 1) "" else "s"} not counted yet.", modifier = Modifier.appear(1)) }
                        item { SectionLabel("Classes", MaterialTheme.colorScheme.primary, Icons.Rounded.DateRange, Modifier.appear(2), count = dayClasses.size) }
                        item { DateStrip(days, selected, sessionDays, { selectedDay = it.toString() }, Modifier.appear(2)) }
                        if (!state.loading && dayClasses.isEmpty()) item { Text(if (selected == LocalDate.now()) "No classes today" else "No classes on this date", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp).appear(2)) }
                        itemsIndexed(dayClasses, key = { _, it -> it.id }) { index, session -> SessionCard(session, Modifier.appear(index + 2)) { nav.navigate("session/${session.id}") } }
                        item { SectionLabel("Settings", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.Settings, Modifier.appear(3)) }
                        item { GlassCard(Modifier.appear(3)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Do Not Disturb", style = MaterialTheme.typography.titleMedium)
                                    Text(if (state.dndReady) "Required · ready for class" else "Required · needs setup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!state.dndReady) GButton(onClick = { dnd.open() }) { Text("Set up") }
                            }
                        } }
                    }
                }
                composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    LaunchedEffect(id, state.student?.id) { if (state.student != null) model.loadDetail(id) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                        item { BackButton { nav.popBackStack() } }
                        state.detail?.let { session ->
                            item { Column(Modifier.appear(0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session.course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(session.title, style = MaterialTheme.typography.headlineMedium)
                                Text("${time(session.startsAt)} – ${time(session.endsAt).substringAfterLast(" · ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { GlassCard(Modifier.appear(1)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("Attendance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(session.status?.replaceFirstChar { it.uppercase() } ?: "Not recorded", style = MaterialTheme.typography.titleLarge)
                                    }
                                    GlassChip(session.state.replace('_', ' ').replaceFirstChar { it.uppercase() })
                                }
                            } }
                            if (session.pendingReview || session.id in state.pendingLocal) item { AlertCard("Pending teacher review", "Not counted until your teacher confirms.", modifier = Modifier.appear(2)) }
                            if (session.state in setOf("checkin_pending", "active") && (session.status == null || state.focusState !in setOf("active", "paused"))) item {
                                if (state.dndReady) GButton(onClick = { model.prepareScan(); nav.navigate("scan/${session.id}") }, modifier = Modifier.fillMaxWidth().appear(2)) { Text(if (session.status == null) "Scan classroom QR" else "Scan QR to rejoin focus") }
                                else AlertCard("Do Not Disturb is required", dnd.message.ifBlank { "Turn it on to scan and join this class." }, "Set up", Modifier.appear(2)) { dnd.open() }
                            }
                            if (session.status == null && session.state != "ended") item { GTextButton(onClick = { model.prepareScan(); nav.navigate("scan/${session.id}") }) { Text("Can't scan? Ask for teacher review") } }
                            if (session.state == "active") item { GOutlinedButton(onClick = { nav.navigate("focus/${session.id}") }, modifier = Modifier.fillMaxWidth().appear(3)) { Text("Open focus") } }
                            if (session.state == "active") item { GlassCard(Modifier.appear(4)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Quiet mode", style = MaterialTheme.typography.titleMedium)
                                        Text(if (state.quietRequested && state.dndEffective) "On for this class" else if (state.dndReady) "Starts with focus" else "Required · needs setup", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!state.dndReady) GButton(onClick = { dnd.open() }) { Text("Set up") }
                                }
                            } }
                            item { Text("Resources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp)) }
                            if (state.receiverState == "reduced_evidence") item { Text("Classroom receiver unavailable. Ask your teacher for review if Bluetooth evidence is missing.", color = MaterialTheme.colorScheme.tertiary) }
                            if (state.resources.isEmpty()) item { Text("Nothing attached yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            itemsIndexed(state.resources, key = { _, it -> it.id }) { index, resource ->
                                GlassCard(Modifier.appear(index + 5), onClick = { nav.navigate("resource/${resource.id}") }) {
                                    Text(resource.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${resource.kind.uppercase()} · ${if (resource.downloadable) "Offline allowed" else "View only"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { GTextButton(onClick = { model.loadDetail(id) }, enabled = !state.loading) { Text("Refresh") } }
                    }
                }
                composable("scan/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    ScanScreen(id, state.loading, id in state.pendingLocal || state.detail?.pendingReview == true, state.error, state.checkInResult,
                        onQr = { model.acceptScannedCheckIn(id, it) }, onDecline = { model.declineScannedCheckIn(id) }, onReview = { model.requestReview(id, it) }, onBack = { nav.popBackStack() },
                        dndReady = state.dndReady, onSetupDnd = dnd.open, onCode = { model.checkInWithCode(id, it) })
                }
                composable("face-check/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    FaceVerifyScreen(template = model.faceTemplate(),
                        onMatched = { nav.popBackStack(); model.faceMatched(id) },
                        onGiveUp = { reason -> nav.popBackStack(); model.faceGaveUp(id, reason) },
                        onCancel = { nav.popBackStack(); model.faceCancelled() })
                }
                composable("portal") {
                    PortalScreen(BuildConfig.DASHBOARD_URL, { model.portalToken() }, { model.signOut() },
                        extraAction = if (state.student?.role == "teacher") ({ GOutlinedButton(onClick = { nav.navigate("enroll") }) { Text("Record student faces") } }) else null)
                }
                composable("enroll") {
                    FaceEnrollScreen(state.faceRoster, state.loading, state.faceMessage, state.error,
                        onRefresh = model::loadFaceRoster, onEnroll = model::enrollFace, onDelete = model::deleteFace)
                }
                composable("timetable") {
                    var weekStart by rememberSaveable { mutableStateOf(currentWeekStart().toString()) }
                    val week = LocalDate.parse(weekStart)
                    LaunchedEffect(week, state.student?.id) { if (state.student != null) model.loadTimetable(week) }
                    TimetableScreen(state.timetable, week, state.loading, { weekStart = it.toString() }, { model.loadTimetable(week) }, { id -> nav.navigate("session/$id") })
                }
                composable("history") {
                    var privacyKind by remember { mutableStateOf<String?>(null) }
                    var privacySession by remember { mutableStateOf<Int?>(null) }
                    var privacyReason by remember { mutableStateOf("") }
                    LaunchedEffect(state.student?.id) { if (state.student != null) model.loadHistory() }
                    if (privacyKind != null) AlertDialog(onDismissRequest = { privacyKind = null },
                        title = { Text(if (privacyKind == "correction") "Request attendance correction" else "Request data deletion") },
                        text = { GTextField(value = privacyReason, onValueChange = { privacyReason = it },
                            label = "Reason (at least 10 characters)", minLines = 3) },
                        confirmButton = { TextButton(onClick = {
                            model.submitPrivacyRequest(privacyKind!!, privacySession, privacyReason.trim())
                            privacyKind = null; privacyReason = ""
                        }, enabled = privacyReason.trim().length >= 10) { Text("Send request") } },
                        dismissButton = { TextButton(onClick = { privacyKind = null }) { Text("Cancel") } })
                    var shownMonth by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
                    var pickedDay by rememberSaveable { mutableStateOf<String?>(null) }
                    val month = YearMonth.parse(shownMonth)
                    val recordDay = { record: HistoryEntry -> Instant.parse(record.session.startsAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                    val marks = state.history.groupBy(recordDay).mapValues { (_, rows) -> rows.map { it.status } }
                    val picked = pickedDay?.let(LocalDate::parse)
                    val shown = state.history.filter { YearMonth.from(recordDay(it)) == month && (picked == null || recordDay(it) == picked) }
                        .sortedByDescending { it.session.startsAt }
                    val historyList = rememberLazyListState()
                    LazyColumn(state = historyList, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                        item { Row(Modifier.fillMaxWidth().appear(0).heroParallax(historyList), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("History", style = MaterialTheme.typography.headlineLarge)
                            GlassIconButton(model::loadHistory, "Refresh") { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } }
                        if (!state.loading && state.history.isEmpty()) item { Text("No records yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
                        item { MonthCalendar(month, marks, picked, { pickedDay = it?.toString() }, { shownMonth = it.toString(); pickedDay = null }, Modifier.appear(1)) }
                        item { SectionLabel(picked?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) ?: month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                            MaterialTheme.colorScheme.primary, Icons.Rounded.DateRange, Modifier.appear(2), count = shown.size) }
                        if (!state.loading && state.history.isNotEmpty() && shown.isEmpty()) item { Text(if (picked != null) "No records on this day" else "No records this month", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        itemsIndexed(shown, key = { _, it -> it.session.id }) { index, record ->
                            Column(Modifier.appear(index + 2)) {
                                SessionCard(record.session.copy(status = record.status)) { nav.navigate("session/${record.session.id}") }
                                GTextButton(onClick = { privacyKind = "correction"; privacySession = record.session.id }) { Text("Request correction") }
                            }
                        }
                        item { GOutlinedButton(onClick = { privacyKind = "deletion"; privacySession = null }) { Text("Request data deletion") } }
                        items(state.privacyRequests, key = { "request-${it.id}" }) { request ->
                            Text("${request.kind.replaceFirstChar { it.uppercase() }} request #${request.id}: ${request.status}${request.resolution?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    var exitCodeDialog by remember { mutableStateOf(false) }
                    var exitCode by remember { mutableStateOf("") }
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
                        dismissButton = { Column { TextButton(onClick = { exitDialog = false; model.requestFocusExitApproval(id) }) { Text("Ask teacher to approve") }
                            TextButton(onClick = { exitDialog = false; exitCodeDialog = true }) { Text("Enter the exit code") } } })
                    if (exitCodeDialog) AlertDialog(onDismissRequest = { exitCodeDialog = false },
                        title = { Text("Exit code") },
                        text = { GTextField(exitCode, { exitCode = it }, "Code from your teacher", singleLine = true) },
                        confirmButton = { GTextButton(onClick = { exitCodeDialog = false; model.exitFocusWithCode(id, exitCode); exitCode = "" }, enabled = exitCode.trim().length >= 6) { Text("Leave focus") } },
                        dismissButton = { GTextButton(onClick = { exitCodeDialog = false }) { Text("Cancel") } })
                    if (scanningExit) AlertDialog(onDismissRequest = { scanningExit = false },
                        title = { Text("Scan the teacher's QR") },
                        text = {
                            if (!cameraGranted) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Camera access is needed to scan the teacher's code. You can also ask the teacher to approve instead.")
                                GButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera for this scan") }
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
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().padding(vertical = 8.dp).glass().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        val classState = state.detail?.takeIf { it.id == id }?.state
                        Spacer(Modifier.height(8.dp))
                        FocusOrb(state.focusState)
                        AnimatedContent(state.focusState, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "focus-title") { focus ->
                            Text(when (focus) { "active" -> "Focus is on"; "paused" -> "Paused"; "ended" -> "Focus ended"; else -> "Ready" }, style = MaterialTheme.typography.headlineMedium)
                        }
                        if (state.focusState == "ready" || state.focusState == "ended") {
                            if (classState == "ended") Text("This session has ended.", color = MaterialTheme.colorScheme.tertiary)
                            else Text("Scan the teacher's QR to join once class is active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.student != null && classState != "ended") GButton(onClick = { model.prepareScan(); nav.navigate("scan/$id") }, modifier = Modifier.fillMaxWidth()) { Text(if (state.focusState == "ended") "Scan QR to rejoin" else "Scan QR to join") }
                        }
                        if (state.focusState == "active") GButton(onClick = { model.pauseFocus(id) }, modifier = Modifier.fillMaxWidth()) { Text("Pause") }
                        if (state.focusState == "paused") GButton(onClick = { model.resumeFocus(id) }, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                        if (state.focusState in setOf("active", "paused")) GOutlinedButton(onClick = { exitDialog = true }, enabled = state.exitRequest != "pending", modifier = Modifier.fillMaxWidth()) { Text("Exit focus") }
                        if (state.exitRequest == "pending") Text("Waiting for your teacher to approve", color = MaterialTheme.colorScheme.tertiary)
                        if (state.exitRequest == "denied") Text("Not approved. You can scan their QR instead.", color = MaterialTheme.colorScheme.error)
                        if (state.focusState in setOf("active", "paused")) {
                            GOutlinedButton(onClick = {
                                BackgroundNotice.suppressForExpectedExternalActivity()
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
                                    .onFailure { phoneMessage = "No phone dialer is available on this device." }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open phone") }
                        }
                        if (phoneMessage.isNotBlank()) Text(phoneMessage, color = MaterialTheme.colorScheme.error)
                        if (BuildConfig.ENABLE_LOCAL_VPN && state.focusState == "active") {
                            if (vpnRunning) GOutlinedButton(onClick = { VpnFeature.stop(context); model.stopVpnConsent(); vpnRunning = false }, modifier = Modifier.fillMaxWidth()) { Text("Stop local VPN") }
                            else GButton(onClick = { disclosure = true }, modifier = Modifier.fillMaxWidth()) { Text("Enable local VPN") }
                        }
                        if (vpnMessage.isNotBlank()) Text(vpnMessage)
                        if (BuildConfig.ENABLE_BLE_PRESENCE && state.focusState == "active") {
                            if (bleStatus == "advertising" || bleStatus == "starting") GOutlinedButton(onClick = { model.stopPresence(); bleStatus = "off" }, modifier = Modifier.fillMaxWidth()) { Text("Stop Bluetooth") }
                            else GButton(onClick = { bleDisclosure = true }, modifier = Modifier.fillMaxWidth()) { Text("Enable Bluetooth evidence") }
                            Text("Bluetooth: $bleStatus · Receiver: ${state.receiverState}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.focusState !in setOf("active", "paused")) GTextButton(onClick = { nav.popBackStack() }) { Text("Back to session") }
                    }
                }
                composable("resource/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val resource = state.resources.find { it.id == backStack.arguments!!.getInt("id") }
                    if (resource == null) Text("Resource unavailable. Go back and refresh.")
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().appear(0).glass().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Welcome to class", style = MaterialTheme.typography.headlineLarge)
            if (config?.developmentLogin == true) {
                GTextField(email, { email = it }, "Email", singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email))
                GTextField(password, { password = it }, "Password", singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                GButton(onClick = { submit(email.trim().lowercase(), password.trim()); password = "" }, enabled = !loading && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            } else {
                GButton(onClick = collegeSignIn, enabled = !loading && config?.collegeOidc == true && config.authorizationEndpoint != null && config.androidClientId != null && config.androidRedirectUri != null, modifier = Modifier.fillMaxWidth()) { Text("Sign in with college") }
                if (config?.collegeOidc == false) Text("College sign-in isn't set up yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            offlineFocus?.let { open -> GOutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Open saved focus session") } }
        }
    }
}

@Composable
private fun SessionCard(session: ClassSession, modifier: Modifier = Modifier, open: () -> Unit) {
    GlassCard(modifier, onClick = open) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(session.title, style = MaterialTheme.typography.titleLarge)
                Text(time(session.startsAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.roomCode.isNotBlank() && session.roomCode != "UNASSIGNED") Text("Room ${session.roomCode}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            GlassChip(session.status?.replaceFirstChar { it.uppercase() } ?: "Not recorded",
                color = if (session.status == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    GlassIconButton(onClick, "Back") { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
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
        item { BackButton(back) }
        item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(resource.title, style = MaterialTheme.typography.headlineMedium)
            GlassChip(resource.kind.uppercase())
        } }
        if (resource.downloadable) item {
            GOutlinedButton(onClick = { scope.launch { fetch(true) } }, enabled = !saved && !busy) { Text(if (saved) "Saved for offline use" else "Save offline copy") }
        }
        if (saved) item { GTextButton(onClick = { if (store.removeCached(resource)) { saved = false; file = null; text = null; bitmap = null } }) { Text("Delete offline copy") } }
        if (busy) item { CircularProgressIndicator() }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (resource.kind == "text") text?.let { body -> item { Text(body) } }
        if (resource.kind == "pdf") {
            bitmap?.let { image -> item { Image(image.asImageBitmap(), contentDescription = "PDF page ${page + 1}", modifier = Modifier.fillMaxWidth()) } }
            if (pages > 0) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GOutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                Text("${page + 1} / $pages")
                GOutlinedButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Next") }
            } }
        }
    }
}
