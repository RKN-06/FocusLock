package org.focuslock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun decode64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

class FocusLocal(private val context: Context) {
    private val prefs = context.getSharedPreferences("focuslock-voluntary-focus", Context.MODE_PRIVATE)
    private fun boot(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun state(studentId: Int, sessionId: Int): String {
        if (prefs.getInt("student", -1) != studentId || prefs.getInt("session", -1) != sessionId) return "ready"
        val previous = prefs.getString("state", "ready") ?: "ready"
        if (previous !in setOf("active", "paused")) return previous
        if (!FocusDeadline.isLive(prefs.getInt("boot", -2), boot(), prefs.getLong("deadline", 0), SystemClock.elapsedRealtime())) {
            prefs.edit().putString("state", "ended").apply()
            return "ended"
        }
        return previous
    }

    fun token(): String = prefs.getString("policy", "") ?: ""
    fun deadline(studentId: Int, sessionId: Int): Pair<Int, Long>? = if (state(studentId, sessionId) == "active")
        prefs.getInt("boot", -1) to prefs.getLong("deadline", 0) else null
    /** Boot count and deadline of the running focus session, whether it is active or paused. */
    fun liveDeadline(studentId: Int, sessionId: Int): Pair<Int, Long>? = if (state(studentId, sessionId) in setOf("active", "paused"))
        prefs.getInt("boot", -1) to prefs.getLong("deadline", 0) else null
    fun currentSession(studentId: Int): Int? = prefs.getInt("session", -1).takeIf {
        it > 0 && prefs.getInt("student", -1) == studentId && state(studentId, it) in setOf("active", "paused")
    }
    fun localStudentId(): Int? = prefs.getInt("student", -1).takeIf { it > 0 }
    fun localSession(): Int? = localStudentId()?.let { currentSession(it) }

    fun verifiedDeadline(studentId: Int, sessionId: Int, policy: FocusPolicy, nonce: String, requestStart: Long): Pair<Int, Long> {
        val parts = policy.token.split('.')
        require(parts.size == 3 && parts[0] == "FP3") { "Invalid focus policy" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode64(policy.publicKey)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(parts[1].toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode64(parts[2]))) { "Invalid focus policy signature" }
        val payload = JSONObject(String(decode64(parts[1]), Charsets.UTF_8))
        val issued = payload.getLong("iat")
        val expiry = payload.getLong("exp")
        val maximum = payload.getLong("max")
        require(payload.getInt("v") == 3 && payload.getInt("uid") == studentId && payload.getInt("sid") == sessionId && payload.getString("nonce") == nonce)
        val deadline = FocusDeadline.fromPolicy(requestStart, issued, expiry, maximum)
        val currentBoot = boot()
        require(FocusDeadline.isLive(currentBoot, currentBoot, deadline, SystemClock.elapsedRealtime())) { "Focus policy expired or boot count unavailable" }
        return currentBoot to deadline
    }

    fun start(studentId: Int, sessionId: Int, policy: FocusPolicy, nonce: String, requestStart: Long) {
        val (currentBoot, deadline) = verifiedDeadline(studentId, sessionId, policy, nonce, requestStart)
        prefs.edit().putInt("student", studentId).putInt("session", sessionId).putInt("boot", currentBoot)
            .putLong("deadline", deadline).putString("policy", policy.token).putString("state", "active").putBoolean("resume_lock", false).apply()
    }

    /** After the student leaves the app during focus, a teacher must approve before they continue. Survives app restarts. */
    fun resumeLocked(studentId: Int, sessionId: Int): Boolean =
        prefs.getInt("student", -1) == studentId && prefs.getInt("session", -1) == sessionId && prefs.getBoolean("resume_lock", false) &&
            state(studentId, sessionId) in setOf("active", "paused")
    fun setResumeLock(on: Boolean) { prefs.edit().putBoolean("resume_lock", on).apply() }

    fun pause(studentId: Int, sessionId: Int) {
        if (state(studentId, sessionId) == "active") prefs.edit().putString("state", "paused").apply()
    }

    fun resume(studentId: Int, sessionId: Int) {
        if (state(studentId, sessionId) == "paused") prefs.edit().putString("state", "active").apply()
    }

    fun exit(studentId: Int, sessionId: Int) {
        if (prefs.getInt("student", -1) == studentId && prefs.getInt("session", -1) == sessionId)
            prefs.edit().putString("state", "ended").putBoolean("resume_lock", false).apply()
    }
}

class ResourceStore(private val context: Context) {
    private val hosts = BuildConfig.RESOURCE_ALLOWED_HOSTS.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    private val maxBytes = 10L * 1024 * 1024

    private fun checked(uri: URI): URI {
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.host != null && uri.host == uri.host.lowercase() && uri.host in hosts &&
                uri.port in setOf(-1, 443) && uri.rawPath.startsWith("/") &&
                uri.toASCIIString().none { it.isWhitespace() || it == '\\' }) { "Resource host or scheme is not allowed" }
        return uri
    }

    private fun target(resource: CourseResource): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(resource.url.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "course-resources").apply { mkdirs() }
        return File(directory, "${resource.id}-$digest.${resource.kind}")
    }

    fun cached(resource: CourseResource): Boolean = resource.downloadable && target(resource).isFile
    fun removeCached(resource: CourseResource): Boolean = !target(resource).exists() || target(resource).delete()

    suspend fun open(resource: CourseResource, saveOffline: Boolean = false): File = withContext(Dispatchers.IO) {
        require(resource.kind in setOf("pdf", "text")) { "Unsupported resource format" }
        checked(URI(resource.url))
        val saved = target(resource)
        if (saved.isFile) return@withContext saved
        require(!saveOffline || resource.downloadable) { "Offline copy is not allowed for this resource" }
        var uri = checked(URI(resource.url))
        repeat(4) { hop ->
            val connection = URL(uri.toASCIIString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(hop < 3) { "Too many resource redirects" }
                    val location = connection.getHeaderField("Location") ?: error("Redirect without location")
                    uri = checked(uri.resolve(location))
                } else {
                    require(status == 200) { "Resource request failed ($status)" }
                    val mime = connection.contentType.substringBefore(';').trim().lowercase()
                    require(mime == if (resource.kind == "pdf") "application/pdf" else "text/plain") { "Unexpected resource type" }
                    require(connection.contentLengthLong in -1..maxBytes) { "Resource is too large" }
                    val output = if (saveOffline) File(saved.parentFile, saved.name + ".part") else File.createTempFile("course-", ".${resource.kind}", context.cacheDir)
                    try {
                        connection.inputStream.use { input -> output.outputStream().use { sink ->
                            val buffer = ByteArray(8192)
                            var count = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= maxBytes) { "Resource is too large" }
                                sink.write(buffer, 0, read)
                            }
                        } }
                        if (saveOffline) {
                            require(output.renameTo(saved)) { "Could not save private copy" }
                            return@withContext saved
                        }
                        return@withContext output
                    } catch (error: Exception) { output.delete(); throw error }
                }
            } finally { connection.disconnect() }
        }
        error("Too many resource redirects")
    }

    suspend fun readText(file: File): String = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
    suspend fun renderPage(file: File, index: Int): Pair<Bitmap, Int> = withContext(Dispatchers.IO) {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            val page = pdf.openPage(index.coerceIn(0, pdf.pageCount - 1))
            page.use {
                val scale = minOf(2f, 1400f / page.width)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap to pdf.pageCount
            }
        }
    }
}
