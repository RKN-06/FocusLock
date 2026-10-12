package org.focuslock.app

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.os.SystemClock
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val student: Student? = null,
    val sessions: List<ClassSession> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val timetable: List<TimetableEntry> = emptyList(),
    val privacyRequests: List<PrivacyRequest> = emptyList(),
    val detail: ClassSession? = null,
    val pendingLocal: Set<Int> = emptySet(),
    val checkInResult: String? = null,
    val resources: List<CourseResource> = emptyList(),
    val focusState: String = "ready",
    val focusSessionId: Int? = null,
    val exitRequest: String = "none",
    val resumeLock: String = "none", // none, required, pending, denied
    val faceQr: FaceQr? = null,
    val faceMessage: String? = null,
    val faceRoster: List<FaceRosterEntry> = emptyList(),
    val autoNavigateFocusId: Int? = null,
    val quietRequested: Boolean = false,
    val dndReady: Boolean = false,
    val dndEffective: Boolean = false,
    val receiverState: String = "unknown",
    val presenceState: String = "off",
    val authConfig: AuthConfig? = null,
    val loading: Boolean = false,
    val error: String? = null
)

data class FaceQr(val sessionId: Int, val qr: String)

class FocusViewModel(application: Application) : AndroidViewModel(application) {
    private val api = Api()
    private val identity = DeviceIdentity(application)
    private val focus = FocusLocal(application)
    private val quietContext = application.applicationContext
    private val pendingPrefs = application.getSharedPreferences("focuslock-pending", android.content.Context.MODE_PRIVATE)
    private val consentPrefs = application.getSharedPreferences("focuslock-consent-withdrawals", android.content.Context.MODE_PRIVATE)
    private val autoFocusPrefs = application.getSharedPreferences("focuslock-auto-focus", android.content.Context.MODE_PRIVATE)
    private fun lockFor(studentId: Int, session: Int?) = if (session != null && focus.resumeLocked(studentId, session)) "required" else "none"
    private val mutable = MutableStateFlow(UiState(focusSessionId = focus.localSession(), focusState = focus.localSession()?.let { focus.state(focus.localStudentId()!!, it) } ?: "ready",
        resumeLock = focus.localStudentId()?.let { lockFor(it, focus.localSession()) } ?: "none"))
    private var faceStatus: FaceStatus? = null
    private val faceVerifiedAt = HashMap<String, Long>()
    val state = mutable.asStateFlow()
    private var job: Job? = null

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is ApiException && error.statusCode == 401 && mutable.value.student != null) {
                    api.clearToken()
                    mutable.value = UiState(authConfig = mutable.value.authConfig, error = "Your session expired. Please sign in again.")
                } else {
                    mutable.value = mutable.value.copy(error = if (error is java.io.IOException) "Cannot reach FocusLock. Check your connection and try again." else error.message ?: "Something went wrong. Try again.")
                }
            } finally { mutable.value = mutable.value.copy(loading = false) }
        }
    }

    fun signIn(email: String, password: String) = run {
        val student = api.signIn(email, password)
        reconcileWithdrawals()
        val focusSession = focus.currentSession(student.id)
        mutable.value = UiState(student = student, authConfig = mutable.value.authConfig, pendingLocal = pendingPrefs.getStringSet("student-${student.id}", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet(), focusSessionId = focusSession, focusState = focusSession?.let { focus.state(student.id, it) } ?: "ready", quietRequested = QuietMode.requested(quietContext), resumeLock = lockFor(student.id, focusSession), loading = true)
    }
    fun loadAuthConfig() = run { mutable.value = mutable.value.copy(authConfig = api.authConfig()) }
    fun beginCollegeSignIn() {
        val config = mutable.value.authConfig ?: return
        try {
            val intent = CollegeAuth(getApplication()).begin(config).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (error: Exception) { mutable.value = mutable.value.copy(error = error.message) }
    }
    fun completeCollegeSignIn(uri: Uri) = run {
        val (code, verifier) = CollegeAuth(getApplication()).consume(uri)
        val student = api.collegeExchange(code, verifier)
        reconcileWithdrawals()
        val focusSession = focus.currentSession(student.id)
        mutable.value = UiState(student = student, authConfig = mutable.value.authConfig,
            pendingLocal = pendingPrefs.getStringSet("student-${student.id}", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet(),
            focusSessionId = focusSession, focusState = focusSession?.let { focus.state(student.id, it) } ?: "ready", quietRequested = QuietMode.requested(quietContext), resumeLock = lockFor(student.id, focusSession), loading = true)
    }
    private fun acceptedFocus(studentId: Int, sessionId: Int) = autoFocusPrefs.getBoolean("student-$studentId-session-$sessionId", false)
    private fun faceKey(studentId: Int, sessionId: Int) = "$studentId:$sessionId"
    private fun faceFresh(studentId: Int, sessionId: Int): Boolean {
        val at = faceVerifiedAt[faceKey(studentId, sessionId)] ?: return false
        return SystemClock.elapsedRealtime() - at < 120_000L
    }
    /** After the disclosure is accepted: the first scan needs a face match when staff recorded the student's face. */
    fun acceptScannedCheckIn(id: Int, qr: String) = run {
        val studentId = mutable.value.student?.id ?: error("Sign in required")
        val status = api.faceStatus().also { faceStatus = it }
        when {
            status.mode == "off" || (!status.enrolled && status.mode != "required") -> checkInInternal(id, qr, false)
            !status.enrolled -> requestReviewInternal(id, "No face record exists for me yet, so I need the teacher to review my attendance")
            faceFresh(studentId, id) -> checkInInternal(id, qr, true)
            else -> mutable.value = mutable.value.copy(faceQr = FaceQr(id, qr), faceMessage = null)
        }
    }
    fun faceTemplate(): FloatArray? = faceStatus?.template
    fun faceMatched(id: Int) {
        val studentId = mutable.value.student?.id ?: return
        val waiting = mutable.value.faceQr ?: return
        faceVerifiedAt[faceKey(studentId, id)] = SystemClock.elapsedRealtime()
        mutable.value = mutable.value.copy(faceQr = null)
        run { checkInInternal(id, waiting.qr, true) }
    }
    fun faceGaveUp(id: Int, message: String) {
        mutable.value = mutable.value.copy(faceQr = null)
        run { requestReviewInternal(id, message) }
    }
    fun faceCancelled() { mutable.value = mutable.value.copy(faceQr = null) }
    fun declineScannedCheckIn(id: Int) {
        val student = mutable.value.student ?: return
        autoFocusPrefs.edit().remove("student-${student.id}-session-$id").apply()
        requestReview(id, "I declined the optional focus disclosure after scanning")
    }
    fun loadFaceRoster() = run { mutable.value = mutable.value.copy(faceRoster = api.faceRoster()) }
    fun enrollFace(studentId: Int, template: FloatArray) = run {
        api.enrollFace(studentId, template)
        mutable.value = mutable.value.copy(faceRoster = api.faceRoster(), faceMessage = "Face recorded.")
    }
    fun deleteFace(studentId: Int) = run {
        api.deleteFace(studentId)
        mutable.value = mutable.value.copy(faceRoster = api.faceRoster(), faceMessage = "Face record deleted.")
    }
    fun consumeAutoNavigation() { mutable.value = mutable.value.copy(autoNavigateFocusId = null) }
    fun loadToday() = run {
        reconcileWithdrawals()
        val sessions = api.sessions()
        mutable.value = mutable.value.copy(sessions = sessions)
        val student = mutable.value.student ?: return@run
        val current = focus.currentSession(student.id)
        // A session that ended, or that the teacher deleted, can no longer hold the phone in focus or Do Not Disturb.
        if (current != null && (sessions.any { it.id == current && it.state == "ended" } || sessions.none { it.id == current })) {
            endLocalSession(current)
        }
        if (focus.currentSession(student.id) != null) return@run
        val active = sessions.filter { it.state == "active" }.sortedBy { it.startsAt }
            .filter { focus.state(student.id, it.id) == "ready" }
        val focusTarget = active.firstOrNull { acceptedFocus(student.id, it.id) }
        if (focusTarget != null) {
            runCatching { startFocusInternal(focusTarget.id, true) }
                .onFailure { mutable.value = mutable.value.copy(error = "Automatic focus could not start: ${it.message}") }
        } else if (active.isNotEmpty() && QuietMode.optedIn(quietContext) && QuietMode.access(quietContext) && QuietMode.session(quietContext) != active.first().id) {
            runCatching { startQuietInternal(active.first().id) }
                .onFailure { if (!(it is ApiException && it.statusCode == 409)) mutable.value = mutable.value.copy(error = "Quiet mode could not start: ${it.message}") }
        }
    }
    fun loadTimetable(start: java.time.LocalDate) = run { mutable.value = mutable.value.copy(timetable = api.timetable(start.toString(), 7)) }
    fun loadHistory() = run { mutable.value = mutable.value.copy(history = api.history(), privacyRequests = api.privacyRequests()) }
    fun submitPrivacyRequest(kind: String, sessionId: Int?, reason: String) = run {
        api.submitPrivacyRequest(kind, sessionId, reason)
        mutable.value = mutable.value.copy(privacyRequests = api.privacyRequests())
    }
    fun loadDetail(id: Int) {
        run {
            val detail = api.session(id)
            val resources = api.resources(id)
            val receiver = runCatching { api.receiverStatus(id) }.getOrDefault("unknown")
            mutable.value = mutable.value.copy(detail = detail, resources = resources,
                receiverState = receiver, focusState = mutable.value.student?.let { focus.state(it.id, id) } ?: "ready")
            val student = mutable.value.student
            val localState = student?.let { focus.state(it.id, id) } ?: "ready"
            if (detail.state == "active" && student != null && acceptedFocus(student.id, id) && localState == "ready" && focus.currentSession(student.id) == null) {
                runCatching { startFocusInternal(id, true) }
                    .onFailure { mutable.value = mutable.value.copy(error = "Automatic focus could not start: ${it.message}") }
            } else if (detail.state == "active" && localState == "ready" && QuietMode.optedIn(quietContext) && QuietMode.access(quietContext) && QuietMode.session(quietContext) != id) {
                runCatching { startQuietInternal(id) }
                    .onFailure { if (!(it is ApiException && it.statusCode == 409)) mutable.value = mutable.value.copy(error = "Quiet mode could not start: ${it.message}") }
            } else if (detail.state == "ended" && QuietMode.session(quietContext) == id) {
                QuietMode.stop(quietContext)
            }
            if (detail.state == "ended" && student != null && focus.currentSession(student.id) == id) endLocalSession(id)
            mutable.value = mutable.value.copy(quietRequested = QuietMode.requested(quietContext))
        }
    }
    private suspend fun startQuietInternal(id: Int) {
        val studentId = mutable.value.student?.id ?: error("Sign in required")
        val started = SystemClock.elapsedRealtime()
        val nonce = UUID.randomUUID().toString()
        val policy = api.focusPolicy(id, nonce)
        val (boot, deadline) = focus.verifiedDeadline(studentId, id, policy, nonce, started)
        QuietMode.begin(quietContext, studentId, id, boot, deadline)
        mutable.value = mutable.value.copy(quietRequested = true)
    }
    fun enableQuiet(id: Int) = run {
        require(QuietMode.supported()) { "Automatic Do Not Disturb needs Android 10 or newer" }
        require(QuietMode.access(quietContext)) { "Allow Do Not Disturb access in Android Settings" }
        QuietMode.setOptedIn(quietContext, true)
        if (mutable.value.detail?.id == id && mutable.value.detail?.state == "active") startQuietInternal(id)
    }
    fun enableQuietForFutureSessions() = run {
        require(QuietMode.supported() && QuietMode.access(quietContext)) { "Allow Do Not Disturb access in Android Settings" }
        QuietMode.setOptedIn(quietContext, true)
        val student = mutable.value.student ?: return@run
        val active = mutable.value.sessions.firstOrNull { it.state == "active" && focus.state(student.id, it.id) !in setOf("paused", "ended") }
        if (active != null && QuietMode.session(quietContext) != active.id) startQuietInternal(active.id)
    }
    fun refreshSessionStatus(id: Int) {
        if (mutable.value.student == null) return
        viewModelScope.launch {
            runCatching { api.session(id) }.onSuccess { session ->
                if (mutable.value.detail?.id == id) mutable.value = mutable.value.copy(detail = session)
                if (session.state == "ended") endLocalSession(id)
            }
        }
    }
    fun recordBackgroundInterruption(id: Int) {
        val student = mutable.value.student ?: return
        val local = focus.state(student.id, id)
        if (local !in setOf("active", "paused")) return
        focus.setResumeLock(true)
        mutable.value = mutable.value.copy(resumeLock = "required")
        if (local != "active") return
        val policy = focus.token()
        if (policy.isBlank()) return
        viewModelScope.launch {
            runCatching { api.focusEvent(id, UUID.randomUUID().toString(), policy, "backgrounded") }
        }
    }
    fun requestResumeApproval(id: Int) {
        if (mutable.value.student == null) return
        viewModelScope.launch {
            runCatching { api.requestFocusResume(id) }.onSuccess { mutable.value = mutable.value.copy(resumeLock = "pending") }
        }
    }
    fun pollResume(id: Int) {
        if (mutable.value.student == null || mutable.value.resumeLock != "pending") return
        viewModelScope.launch {
            runCatching { api.focusResumeStatus(id) }.onSuccess { status ->
                when (status) {
                    "approved" -> { focus.setResumeLock(false); mutable.value = mutable.value.copy(resumeLock = "none") }
                    "denied" -> mutable.value = mutable.value.copy(resumeLock = "denied")
                }
            }
        }
    }
    fun stopQuiet() {
        QuietMode.setOptedIn(quietContext, false)
        mutable.value = mutable.value.copy(quietRequested = false)
    }
    fun refreshQuietStatus() {
        QuietMode.reconcile(quietContext)
        QuietMode.reassert(quietContext)
        val current = mutable.value
        val requested = QuietMode.requested(quietContext)
        val ready = QuietMode.ready(quietContext)
        val effective = QuietMode.effective(quietContext)
        if (requested != current.quietRequested || ready != current.dndReady || effective != current.dndEffective)
            mutable.value = current.copy(quietRequested = requested, dndReady = ready, dndEffective = effective)
    }
    /** Turns Do Not Disturb back on for the running class session after the student switched it off. */
    fun restoreQuiet() {
        val studentId = mutable.value.student?.id ?: focus.localStudentId() ?: return
        val id = mutable.value.focusSessionId ?: return
        if (!QuietMode.ready(quietContext)) return
        QuietMode.reassert(quietContext)
        if (!QuietMode.requested(quietContext)) focus.liveDeadline(studentId, id)?.let { (boot, deadline) ->
            runCatching { QuietMode.begin(quietContext, studentId, id, boot, deadline) }
        }
        refreshQuietStatus()
    }
    fun prepareScan() { mutable.value = mutable.value.copy(checkInResult = null, error = null) }
    private fun savePending(id: Int, include: Boolean) {
        val studentId = mutable.value.student?.id ?: return
        val changed = mutable.value.pendingLocal.toMutableSet().apply { if (include) add(id) else remove(id) }
        pendingPrefs.edit().putStringSet("student-$studentId", changed.map(Int::toString).toSet()).apply()
        mutable.value = mutable.value.copy(pendingLocal = changed)
    }
    private suspend fun checkInInternal(id: Int, qr: String, face: Boolean) {
        val studentId = mutable.value.student?.id ?: error("Sign in required")
        mutable.value = mutable.value.copy(checkInResult = null)
        try {
            val deviceId = identity.ensureRegistered(api, studentId)
            val challenge = api.checkInChallenge(deviceId, qr, face)
            val signature = identity.signCheckIn(studentId, deviceId, challenge, qr, face)
            val result = api.checkIn(deviceId, qr, challenge.id, signature, null, face)
            autoFocusPrefs.edit().putBoolean("student-$studentId-session-$id", true).apply()
            savePending(id, false)
            val detail = api.session(id)
            mutable.value = mutable.value.copy(checkInResult = if (result.duplicate) "Attendance was already recorded: ${result.status}." else "Check-in recorded: ${result.status}.", detail = detail)
            if (detail.state == "active" && acceptedFocus(studentId, id) && focus.state(studentId, id) in setOf("ready", "ended"))
                runCatching { startFocusInternal(id, true) }
                    .onFailure { mutable.value = mutable.value.copy(error = "Attendance recorded. Focus could not start: ${it.message}") }
        } catch (offline: java.io.IOException) {
            savePending(id, true)
            throw offline
        }
    }
    fun requestReview(id: Int, reason: String) = run { requestReviewInternal(id, reason) }
    private suspend fun requestReviewInternal(id: Int, reason: String) {
        try {
            api.requestReview(id, reason.trim())
            savePending(id, false)
            mutable.value = mutable.value.copy(checkInResult = "Request sent. Attendance is pending teacher review.", detail = api.session(id))
        } catch (offline: java.io.IOException) {
            savePending(id, true)
            throw offline
        }
    }
    private suspend fun startFocusInternal(id: Int, automatic: Boolean) {
        val studentId = mutable.value.student?.id ?: error("Sign in required")
        val requestStart = SystemClock.elapsedRealtime()
        val nonce = UUID.randomUUID().toString()
        val policy = api.focusPolicy(id, nonce)
        focus.start(studentId, id, policy, nonce, requestStart)
        mutable.value = mutable.value.copy(focusState = "active", focusSessionId = id,
            autoNavigateFocusId = if (automatic) id else null)
        emitFocus(id, "started")
        if (QuietMode.optedIn(quietContext) && QuietMode.access(quietContext)) {
            runCatching {
                val (boot, deadline) = focus.verifiedDeadline(studentId, id, policy, nonce, requestStart)
                QuietMode.begin(quietContext, studentId, id, boot, deadline)
                mutable.value = mutable.value.copy(quietRequested = true)
            }.onFailure { mutable.value = mutable.value.copy(error = "Focus started, but DND did not: ${it.message}") }
        }
    }
    fun startFocus(id: Int) = run { startFocusInternal(id, false) }
    private fun emitFocus(id: Int, action: String) {
        val policy = focus.token()
        if (policy.isNotBlank()) viewModelScope.launch {
            runCatching { api.focusEvent(id, UUID.randomUUID().toString(), policy, action) }
        }
    }
    fun pauseFocus(id: Int) {
        stopPresence()
        stopVpnConsent()
        val studentId = mutable.value.student?.id ?: focus.localStudentId() ?: return
        focus.pause(studentId, id)
        mutable.value = mutable.value.copy(focusState = focus.state(studentId, id))
        emitFocus(id, "paused")
    }
    fun resumeFocus(id: Int) {
        val studentId = mutable.value.student?.id ?: focus.localStudentId() ?: return
        focus.resume(studentId, id)
        mutable.value = mutable.value.copy(focusState = focus.state(studentId, id))
        if (mutable.value.focusState == "active" && QuietMode.optedIn(quietContext) && QuietMode.access(quietContext)) {
            focus.deadline(studentId, id)?.let { (boot, deadline) ->
                runCatching { QuietMode.begin(quietContext, studentId, id, boot, deadline) }
                    .onSuccess { mutable.value = mutable.value.copy(quietRequested = true) }
            }
        }
        emitFocus(id, "resumed")
    }
    private fun endLocalSession(id: Int) {
        val studentId = mutable.value.student?.id ?: focus.localStudentId() ?: return
        QuietMode.stop(quietContext)
        faceVerifiedAt.remove(faceKey(studentId, id)) // rejoining always needs a new face check
        focus.exit(studentId, id)
        stopPresence()
        stopVpnConsent()
        focus.setResumeLock(false)
        mutable.value = mutable.value.copy(focusState = "ended", focusSessionId = null, quietRequested = false, exitRequest = "none", resumeLock = "none")
    }
    // Early exit is only reachable after the server accepted the teacher's current QR or the teacher approved a request.
    private fun exitFocus(id: Int) {
        endLocalSession(id)
        emitFocus(id, "exited")
    }
    fun portalToken(): String? = api.token
    fun checkInWithCode(id: Int, code: String) = run {
        val result = api.checkInWithCode(id, code.trim())
        mutable.value = mutable.value.copy(checkInResult = if (result.duplicate) "Attendance was already recorded: ${result.status}." else "Checked in with the class code: ${result.status}.", detail = api.session(id))
    }
    fun exitFocusWithCode(id: Int, code: String) = run {
        api.focusExitWithCode(id, code.trim())
        exitFocus(id)
    }
    fun exitFocusWithQr(id: Int, qr: String) = run {
        api.focusExitWithQr(id, qr)
        exitFocus(id)
    }
    fun requestFocusExitApproval(id: Int) = run {
        api.requestFocusExit(id)
        mutable.value = mutable.value.copy(exitRequest = "pending")
    }
    fun clearExitRequest() { mutable.value = mutable.value.copy(exitRequest = "none") }
    fun pollFocusExit(id: Int) {
        if (mutable.value.student == null || mutable.value.exitRequest != "pending") return
        viewModelScope.launch {
            runCatching { api.focusExitStatus(id) }.onSuccess { status ->
                when (status) {
                    "approved" -> exitFocus(id)
                    "denied" -> mutable.value = mutable.value.copy(exitRequest = "denied")
                }
            }
        }
    }
    fun tickFocus(id: Int) {
        val studentId = mutable.value.student?.id ?: focus.localStudentId() ?: return
        val current = focus.state(studentId, id)
        if (current != mutable.value.focusState) {
            mutable.value = mutable.value.copy(focusState = current)
            if (current == "ended") emitFocus(id, "expired")
            if (current == "ended") { QuietMode.stop(quietContext); stopPresence(); stopVpnConsent() }
            if (current == "ended") mutable.value = mutable.value.copy(focusSessionId = null)
        }
    }
    fun startPresence(id: Int) = run {
        if (!BuildConfig.ENABLE_BLE_PRESENCE) error("Bluetooth presence is disabled in this build")
        api.grantConsent("bluetooth")
        consentPrefs.edit().remove("bluetooth").apply()
        val requestStart = SystemClock.elapsedRealtime()
        val credential = api.bleCredential(id)
        BleFeature.start(getApplication(), credential, requestStart, id)
        mutable.value = mutable.value.copy(presenceState = BleFeature.status)
    }
    fun enableVpnConsent(onGranted: () -> Unit) = run {
        api.grantConsent("vpn")
        consentPrefs.edit().remove("vpn").apply()
        onGranted()
    }
    private suspend fun reconcileWithdrawals() {
        for (feature in listOf("vpn", "bluetooth")) if (consentPrefs.getBoolean(feature, false)) {
            runCatching { api.withdrawConsent(feature) }.onSuccess { consentPrefs.edit().remove(feature).apply() }
        }
    }
    fun stopVpnConsent() {
        consentPrefs.edit().putBoolean("vpn", true).apply()
        viewModelScope.launch { reconcileWithdrawals() }
    }
    fun stopPresence() {
        BleFeature.stop()
        mutable.value = mutable.value.copy(presenceState = "off")
        consentPrefs.edit().putBoolean("bluetooth", true).apply()
        viewModelScope.launch { reconcileWithdrawals() }
    }
    fun signOut() {
        if (mutable.value.focusState in setOf("active", "paused")) {
            mutable.value = mutable.value.copy(error = "Sign out is unavailable while focus is running. End focus first with the teacher's QR or approval.")
            return
        }
        QuietMode.stop(quietContext)
        stopPresence()
        stopVpnConsent()
        job?.cancel()
        job = viewModelScope.launch {
            reconcileWithdrawals()
            try { api.signOut() } catch (_: Exception) { api.clearToken() }
            val localSession = focus.localSession()
            mutable.value = UiState(authConfig = mutable.value.authConfig, focusSessionId = localSession,
                resumeLock = focus.localStudentId()?.let { lockFor(it, localSession) } ?: "none",
                focusState = localSession?.let { focus.state(focus.localStudentId()!!, it) } ?: "ready")
        }
    }
}
