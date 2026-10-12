package org.focuslock.app

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Student(val id: Int, val name: String, val role: String = "student")
data class FaceStatus(val mode: String, val enrolled: Boolean, val template: FloatArray?)
data class FaceRosterEntry(val id: Int, val name: String, val email: String, val enrolled: Boolean)
data class ClassSession(val id: Int, val title: String, val course: String, val startsAt: String, val endsAt: String, val status: String?, val state: String, val pendingReview: Boolean, val roomCode: String = "")
data class TimetableEntry(val id: Int, val courseCode: String, val courseTitle: String, val teacher: String, val title: String, val roomCode: String, val startsAt: String, val endsAt: String,
                          val period: String?, val state: String, val attendance: String?)
data class CourseDay(val sessionId: Int, val title: String, val date: String, val startsAt: String, val roomCode: String, val status: String?)
data class CourseSummary(val courseId: Int, val code: String, val title: String, val teacher: String, val held: Int, val present: Int, val absent: Int, val excused: Int,
                         val notRecorded: Int, val rate: Double?, val quitFocus: Int, val breaks: Int, val days: List<CourseDay>)
data class TermSummary(val term: String, val courses: List<CourseSummary>)
data class HistoryEntry(val session: ClassSession, val status: String, val updatedAt: String)
class ApiException(val statusCode: Int, message: String) : Exception(message)
data class ServerChallenge(val id: String, val nonce: String, val integrityHash: String?)
data class CheckInResult(val status: String, val method: String, val duplicate: Boolean)
data class CourseResource(val id: Int, val title: String, val url: String, val kind: String, val downloadable: Boolean)
data class FocusPolicy(val token: String, val publicKey: String)
data class BleCredential(val secret: String, val serverTime: String, val expiresAt: String, val slotSeconds: Int)
data class AuthConfig(val developmentLogin: Boolean, val collegeOidc: Boolean, val authorizationEndpoint: String?, val androidClientId: String?, val androidRedirectUri: String?)
data class PrivacyRequest(val id: Int, val kind: String, val status: String, val resolution: String?)

class Api {
    // In memory only. No analytics, device identifiers or persistent credentials.
    var token: String? = null
        private set

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException(code, detail?.takeIf { it.isNotBlank() } ?: "Request failed ($code)")
            }
            text
        } finally { connection.disconnect() }
    }

    suspend fun signIn(email: String, password: String): Student {
        val response = JSONObject(request("/auth/dev-login", "POST", JSONObject().put("email", email).put("password", password)))
        token = response.getString("access_token")
        val user = response.getJSONObject("user")
        return Student(user.getInt("id"), user.getString("name"), user.getString("role"))
    }

    suspend fun authConfig(): AuthConfig = JSONObject(request("/auth/config")).let {
        AuthConfig(it.getBoolean("development_login"), it.getBoolean("college_oidc"),
            it.optString("authorization_endpoint").takeIf { value -> value.startsWith("https://") },
            it.optString("android_client_id").takeIf { value -> value.isNotBlank() && value != "null" },
            it.optString("android_redirect_uri").takeIf { value -> value == "focuslock://oidc/callback" })
    }
    suspend fun collegeExchange(code: String, verifier: String): Student {
        val response = JSONObject(request("/auth/oidc/exchange", "POST", JSONObject()
            .put("client", "android").put("code", code).put("verifier", verifier)))
        val user = response.getJSONObject("user")
        token = response.getString("access_token")
        return Student(user.getInt("id"), user.getString("name"), user.getString("role"))
    }

    suspend fun signOut() {
        try { request("/auth/logout", "POST") } finally { token = null }
    }

    fun clearToken() { token = null }
    suspend fun sessions(): List<ClassSession> = JSONArray(request("/sessions")).mapObjects(::parseSession)
    suspend fun timetable(start: String, days: Int): List<TimetableEntry> = JSONObject(request("/timetable?start=$start&days=$days")).getJSONArray("entries").mapObjects {
        TimetableEntry(it.getInt("id"), it.getString("course_code"), it.getString("course_title"), it.getString("teacher"), it.getString("title"), it.optString("room_code", ""),
            it.getString("starts_at"), it.getString("ends_at"), it.optString("period").takeIf { period -> period.isNotBlank() && period != "null" }, it.getString("state"),
            it.optString("attendance").takeIf { status -> status.isNotBlank() && status != "null" })
    }
    suspend fun session(id: Int): ClassSession = parseSession(JSONObject(request("/sessions/$id")))
    suspend fun history(): List<HistoryEntry> = JSONArray(request("/attendance/me")).mapObjects {
        HistoryEntry(parseSession(it.getJSONObject("session")), it.getString("status"), it.getString("updated_at"))
    }
    suspend fun attendanceSummary(): List<TermSummary> = JSONObject(request("/attendance/me/summary")).getJSONArray("terms").mapObjects { term ->
        TermSummary(term.getString("term"), term.getJSONArray("courses").mapObjects { c ->
            CourseSummary(c.getInt("course_id"), c.getString("code"), c.getString("title"), c.getString("teacher"), c.getInt("held"), c.getInt("present"), c.getInt("absent"),
                c.getInt("excused"), c.getInt("not_recorded"), if (c.isNull("rate")) null else c.getDouble("rate"), c.getInt("quit_focus"), c.getInt("breaks"),
                c.getJSONArray("days").mapObjects { d ->
                    CourseDay(d.getInt("session_id"), d.getString("title"), d.getString("date"), d.getString("starts_at"), d.optString("room_code", ""),
                        if (d.isNull("status")) null else d.getString("status"))
                })
        })
    }
    suspend fun privacyRequests(): List<PrivacyRequest> = JSONArray(request("/privacy/requests")).mapObjects {
        PrivacyRequest(it.getInt("id"), it.getString("kind"), it.getString("status"), it.optString("resolution").takeIf { value -> value.isNotBlank() && value != "null" })
    }
    suspend fun submitPrivacyRequest(kind: String, sessionId: Int?, reason: String) {
        val body = JSONObject().put("kind", kind).put("reason", reason)
        if (sessionId != null) body.put("session_id", sessionId)
        request("/privacy/requests", "POST", body)
    }

    suspend fun devices(): List<String> = JSONArray(request("/devices")).mapObjects { it.getString("device_id") }
    suspend fun registrationChallenge(): ServerChallenge = parseChallenge(JSONObject(request("/devices/challenge", "POST")))
    suspend fun registerDevice(challenge: ServerChallenge, publicKey: String, signature: String, integrityToken: String?): String =
        JSONObject(request("/devices/register", "POST", JSONObject().put("challenge_id", challenge.id)
            .put("public_key", publicKey).put("signature", signature).apply { if (integrityToken != null) put("integrity_token", integrityToken) })).getString("device_id")
    suspend fun checkInChallenge(deviceId: String, qr: String, faceVerified: Boolean = false): ServerChallenge = parseChallenge(JSONObject(request("/check-ins/challenge", "POST", JSONObject().put("device_id", deviceId).put("qr", qr).put("focus_disclosure", "accepted").put("face_verified", faceVerified))))
    suspend fun checkIn(deviceId: String, qr: String, challengeId: String, signature: String, integrityToken: String?, faceVerified: Boolean = false): CheckInResult {
        val response = JSONObject(request("/check-ins", "POST", JSONObject().put("device_id", deviceId).put("qr", qr)
            .put("challenge_id", challengeId).put("signature", signature).put("focus_disclosure", "accepted").put("face_verified", faceVerified).apply { if (integrityToken != null) put("integrity_token", integrityToken) }))
        return CheckInResult(response.getString("status"), response.getString("method"), response.getBoolean("duplicate"))
    }
    suspend fun requestReview(sessionId: Int, reason: String): String =
        JSONObject(request("/sessions/$sessionId/pending", "POST", JSONObject().put("reason", reason))).getString("state")
    suspend fun resources(sessionId: Int): List<CourseResource> = JSONArray(request("/sessions/$sessionId/resources")).mapObjects {
        CourseResource(it.getInt("id"), it.getString("title"), it.getString("url"), it.getString("kind"), it.getBoolean("downloadable"))
    }
    suspend fun focusPolicy(sessionId: Int, nonce: String): FocusPolicy = JSONObject(request("/sessions/$sessionId/focus-policy?nonce=$nonce")).let {
        FocusPolicy(it.getString("policy"), it.getString("public_key"))
    }
    suspend fun focusEvent(sessionId: Int, id: String, policy: String, action: String) {
        request("/sessions/$sessionId/focus-events", "POST", JSONObject().put("id", id).put("policy", policy).put("action", action))
    }
    suspend fun checkInWithCode(sessionId: Int, code: String): CheckInResult = JSONObject(request("/sessions/$sessionId/check-in/code", "POST", JSONObject().put("code", code))).let {
        CheckInResult(it.getString("status"), it.getString("method"), it.getBoolean("duplicate"))
    }
    suspend fun focusExitWithCode(sessionId: Int, code: String) {
        request("/sessions/$sessionId/focus-exit/code", "POST", JSONObject().put("code", code))
    }
    suspend fun focusExitWithQr(sessionId: Int, qr: String) {
        request("/sessions/$sessionId/focus-exit/qr", "POST", JSONObject().put("qr", qr))
    }
    suspend fun requestFocusExit(sessionId: Int): String =
        JSONObject(request("/sessions/$sessionId/focus-exit-requests", "POST")).getString("status")
    suspend fun focusExitStatus(sessionId: Int): String =
        JSONObject(request("/sessions/$sessionId/focus-exit-requests/mine")).getString("status")
    suspend fun requestFocusResume(sessionId: Int): String =
        JSONObject(request("/sessions/$sessionId/focus-resume-requests", "POST")).getString("status")
    suspend fun focusResumeStatus(sessionId: Int): String =
        JSONObject(request("/sessions/$sessionId/focus-resume-requests/mine")).getString("status")
    suspend fun faceStatus(): FaceStatus = JSONObject(request("/face/templates/me")).let { json ->
        val template = json.optJSONArray("template")?.let { array -> FloatArray(array.length()) { array.getDouble(it).toFloat() } }
        FaceStatus(json.getString("mode"), json.getBoolean("enrolled"), template)
    }
    suspend fun faceRoster(): List<FaceRosterEntry> = JSONArray(request("/face/roster")).mapObjects {
        FaceRosterEntry(it.getInt("id"), it.getString("name"), it.getString("email"), it.getBoolean("enrolled"))
    }
    suspend fun enrollFace(studentId: Int, template: FloatArray) {
        request("/face/templates/$studentId", "PUT", JSONObject().put("template", JSONArray(template.map { it.toDouble() }))
            .put("model", FaceEngine.MODEL_ID).put("consent", true))
    }
    suspend fun deleteFace(studentId: Int) { request("/face/templates/$studentId", "DELETE") }
    suspend fun bleCredential(sessionId: Int): BleCredential = JSONObject(request("/sessions/$sessionId/ble-credential", "POST")).let {
        BleCredential(it.getString("secret"), it.getString("server_time"), it.getString("expires_at"), it.getInt("slot_seconds"))
    }
    suspend fun receiverStatus(sessionId: Int): String = JSONObject(request("/sessions/$sessionId/receiver-status")).getString("state")
    suspend fun grantConsent(feature: String) {
        request("/privacy/consents/$feature", "PUT", JSONObject().put("disclosure_version", "2026-10-privacy-v1"))
    }
    suspend fun withdrawConsent(feature: String) { request("/privacy/consents/$feature", "DELETE") }

    private fun parseChallenge(json: JSONObject) = ServerChallenge(json.getString("challenge_id"), json.getString("nonce"),
        json.optString("integrity_request_hash").takeIf { it.isNotBlank() })

    private fun parseSession(json: JSONObject) = ClassSession(
        json.getInt("id"), json.getString("title"), json.getString("course_title"),
        json.getString("starts_at"), json.getString("ends_at"), json.optJSONObject("attendance")?.getString("status"), json.getString("state"), json.optBoolean("pending_review"), json.optString("room_code", "")
    )

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = (0 until length()).map { transform(getJSONObject(it)) }
}
