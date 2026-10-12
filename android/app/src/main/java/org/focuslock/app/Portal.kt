package org.focuslock.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape

private const val ALERT_CHANNEL = "focuslock-teacher-alerts"

/** What the hosted dashboard can ask the app to do: read the signed-in token, raise a heads-up notification, and sign out. */
class PortalBridge(private val context: Context, private val token: () -> String?, private val onSignOut: () -> Unit) {
    @JavascriptInterface fun token(): String = token() ?: ""
    @JavascriptInterface fun signOut() { android.os.Handler(android.os.Looper.getMainLooper()).post(onSignOut) }
    @JavascriptInterface fun alert(title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Class alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Students asking for approval and focus interruptions" })
        val open = PendingIntent.getActivity(context, 7001, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title.take(60)).setContentText(text.take(200)).setStyle(android.app.Notification.BigTextStyle().bigText(text.take(400)))
            .setContentIntent(open).setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_MESSAGE).build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            manager.notify((System.currentTimeMillis() % 100000).toInt() + 8000, notification)
    }
}

/** Teachers and administrators use the same dashboard as the website, hosted here, so every feature matches. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalScreen(url: String, token: () -> String?, onSignOut: () -> Unit, extraAction: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val origin = remember(url) { android.net.Uri.parse(url).let { "${it.scheme}://${it.authority}" } }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(PortalBridge(context.applicationContext, token, onSignOut), "FocusLockAndroid")
            webViewClient = object : WebViewClient() {
                // Only the college dashboard may load here; anything else opens nowhere.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !request.url.toString().startsWith(origin)
            }
            loadUrl(url)
        }
    }
    DisposableEffect(web) { onDispose { web.removeJavascriptInterface("FocusLockAndroid"); web.destroy() } }
    Column(modifier.fillMaxSize()) {
        extraAction?.let { Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { it() } }
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
    }
}
