package org.focuslock.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One place to turn Do Not Disturb on. DND is required to join a class focus session. */
class DndSetup(val message: String, val open: () -> Unit)

@Composable
fun rememberDndSetup(model: FocusViewModel): DndSetup {
    val context = LocalContext.current
    var disclosure by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (QuietMode.access(context)) { message = ""; model.enableQuietForFutureSessions() }
        else message = "Do Not Disturb access wasn't granted. It is required to join class sessions."
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { BackgroundNotice.suppressForExpectedExternalActivity(); settings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        else message = "Notification permission is needed to show that Do Not Disturb is on."
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
        title = { Text("Do Not Disturb is required") },
        text = { Text("To join a class session you need Do Not Disturb on. FocusLock turns on its own DND rule while class is active, switches it back on if it is turned off, and ends it when the session ends or your teacher approves you leaving. Your Android DND exceptions decide which calls and messages still come through, so add emergency contacts there first. If you can't use this, your teacher can confirm your attendance instead.") },
        confirmButton = { GTextButton(onClick = {
            disclosure = false
            if (!QuietMode.supported()) message = "Do Not Disturb control needs Android 10 or newer. Ask your teacher to confirm your attendance."
            else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (!QuietMode.access(context)) { BackgroundNotice.suppressForExpectedExternalActivity(); settings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            else { message = ""; model.enableQuietForFutureSessions() }
        }) { Text("Continue") } },
        dismissButton = { GTextButton(onClick = { disclosure = false }) { Text("Not now") } })
    return DndSetup(message) { disclosure = true }
}

/** Covers the app while a class is running and Do Not Disturb is off. It clears when DND is back on or the session ends. */
@Composable
fun DndLockScreen(message: String, onFix: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).glass().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text("Do Not Disturb is required", style = MaterialTheme.typography.headlineMedium)
        Text("Class is in session. Turn Do Not Disturb back on to keep using FocusLock.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        GButton(onClick = onFix, modifier = Modifier.fillMaxWidth()) { Text("Turn it on") }
        Text("This clears by itself when the session ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
