package org.focuslock.app

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.Condition

/** Manages only FocusLock's own DND rule; other user/system DND rules are untouched. */
object QuietMode {
    private const val PREFS = "focuslock-quiet"
    private const val RULE = "rule"
    private const val CHANNEL = "focuslock-quiet-session"
    private const val NOTICE = 4105
    const val START = "org.focuslock.app.QUIET_START"
    const val STOP = "org.focuslock.app.QUIET_STOP"
    const val EXPIRE = "org.focuslock.app.QUIET_EXPIRE"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 29
    fun optedIn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("opted_in", false)
    fun setOptedIn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("opted_in", value).apply()
        if (!value) stop(context)
    }
    fun access(context: Context): Boolean = supported() &&
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun requested(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)
    /** Do Not Disturb is required for class sessions: supported, agreed to, and Android access granted. */
    fun ready(context: Context): Boolean = supported() && optedIn(context) && access(context)
    /** True while FocusLock's rule is requested and Android is actually filtering interruptions. */
    fun effective(context: Context): Boolean = requested(context) && access(context) &&
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun conditionId(context: Context): Uri = Condition.newId(context).appendPath("class-session").build()

    fun live(context: Context): Boolean {
        val saved = prefs(context)
        if (!saved.getBoolean("active", false)) return false
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return saved.getInt("student", -1) > 0 && saved.getInt("session", -1) > 0 &&
            FocusDeadline.isLive(saved.getInt("boot", -2), boot,
                saved.getLong("deadline", 0L), SystemClock.elapsedRealtime())
    }

    fun session(context: Context): Int? = prefs(context).getInt("session", -1).takeIf { it > 0 && live(context) }
    fun deadline(context: Context): Long = prefs(context).getLong("deadline", 0L)

    fun begin(context: Context, studentId: Int, sessionId: Int, boot: Int, deadline: Long) {
        require(supported() && optedIn(context) && access(context)) { "Allow Do Not Disturb access in Android Settings first" }
        val currentBoot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        require(FocusDeadline.isLive(boot, currentBoot, deadline, SystemClock.elapsedRealtime())) { "Session policy expired" }
        if (requested(context)) stop(context)
        prefs(context).edit().putBoolean("active", true).putInt("student", studentId)
            .putInt("session", sessionId).putInt("boot", boot).putLong("deadline", deadline).apply()
        try {
            context.startForegroundService(Intent(context, QuietSessionService::class.java).setAction(START))
        } catch (failure: Exception) {
            stop(context)
            throw failure
        }
    }

    fun activateRule(context: Context) {
        require(live(context) && access(context))
        val notificationManager = manager(context)
        val saved = prefs(context)
        var id = saved.getString(RULE, null)
        if (id == null || !notificationManager.automaticZenRules.containsKey(id)) {
            val rule = if (Build.VERSION.SDK_INT >= 35) {
                AutomaticZenRule.Builder("FocusLock class session", conditionId(context))
                    .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    .setTriggerDescription("While a student-approved class session is active")
                    .build()
            } else {
                AutomaticZenRule("FocusLock class session", null,
                    ComponentName(context, MainActivity::class.java), conditionId(context), null,
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            }
            id = notificationManager.addAutomaticZenRule(rule)
            require(!id.isNullOrBlank()) { "Android could not create the FocusLock DND rule" }
            saved.edit().putString(RULE, id).apply()
        }
        notificationManager.setAutomaticZenRuleState(id!!,
            Condition(conditionId(context), "FocusLock class session", Condition.STATE_TRUE))
    }

    /** Puts the rule back if Do Not Disturb was switched off (or the rule disabled or removed) during a class session. */
    fun reassert(context: Context) {
        if (!live(context) || !access(context)) return
        val notificationManager = manager(context)
        val id = prefs(context).getString(RULE, null)
        runCatching {
            val rule = id?.let { notificationManager.getAutomaticZenRule(it) }
            if (id != null && rule != null && !rule.isEnabled) {
                rule.isEnabled = true
                notificationManager.updateAutomaticZenRule(id, rule)
            }
            if (rule == null || notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) activateRule(context)
        }
    }

    private fun deactivateRule(context: Context) {
        if (!access(context)) return
        val id = prefs(context).getString(RULE, null) ?: return
        runCatching {
            if (manager(context).automaticZenRules.containsKey(id))
                manager(context).setAutomaticZenRuleState(id,
                    Condition(conditionId(context), "FocusLock class session ended", Condition.STATE_FALSE))
        }
    }

    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 4105,
        Intent(context, QuietSessionReceiver::class.java).setAction(EXPIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun scheduleExpiry(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val deadline = deadline(context)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm(context))
        else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm(context))
    }

    fun stop(context: Context) {
        prefs(context).edit().putBoolean("active", false).apply()
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarm(context))
        deactivateRule(context)
    }

    fun reconcile(context: Context) {
        if (requested(context) && (!live(context) || !access(context))) stop(context)
        else if (requested(context) && !QuietSessionService.running)
            runCatching { context.startForegroundService(Intent(context, QuietSessionService::class.java).setAction(START)) }
                .onFailure { stop(context) }
    }

    fun createChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, "FocusLock quiet session", NotificationManager.IMPORTANCE_LOW))
    }

    fun notification(context: Context): Notification {
        val open = PendingIntent.getActivity(context, 4106,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("Do Not Disturb is on for class")
            .setContentText("It is required during class and ends when the session ends.")
            .setContentIntent(open).setOngoing(true)
            .build()
    }

    fun notificationId(): Int = NOTICE
}

class QuietSessionService : Service() {
    companion object {
        @Volatile var running = false
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (!QuietMode.live(this@QuietSessionService) || !QuietMode.access(this@QuietSessionService)) {
                stopNow()
                return
            }
            QuietMode.reassert(this@QuietSessionService)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == QuietMode.STOP || !QuietMode.live(this) || !QuietMode.access(this)) {
            stopNow()
            return START_NOT_STICKY
        }
        try {
            QuietMode.createChannel(this)
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(QuietMode.notificationId(), QuietMode.notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(QuietMode.notificationId(), QuietMode.notification(this))
            running = true
            QuietMode.activateRule(this)
            QuietMode.scheduleExpiry(this)
            handler.removeCallbacks(watchdog)
            handler.post(watchdog)
        } catch (_: Exception) { stopNow() }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        running = false
        handler.removeCallbacks(watchdog)
        QuietMode.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        if (QuietMode.requested(this)) QuietMode.stop(this)
        super.onDestroy()
    }
}

class QuietSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in setOf(QuietMode.EXPIRE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED))
            QuietMode.stop(context)
    }
}
