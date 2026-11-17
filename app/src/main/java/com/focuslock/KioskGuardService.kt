package com.focuslock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service (root needed for enforcement):
 * - Every second checks which app is in front (on a background thread)
 * - Anything not allowed -> sent back to MainActivity
 * - If Device Admin gets switched off -> re-activates it using root
 * - After 30 days: revokes Device Admin, sends completion notification, stops self
 */
class KioskGuardService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private lateinit var lock: TimeLockManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val allowed = setOf(
        "com.focuslock",
        "com.google.android.youtube",
        "com.whatsapp",
        // dialer + call screens
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.app.telephonyui",
        "com.android.emergency",
        // clock / alarms
        "com.sec.android.app.clockpackage",
        "com.samsung.android.app.clockpackage",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.oneplus.deskclock",
        "com.android.BBKClock",
        "com.vivo.alarmclock",
        "com.motorola.blur.alarmclock",
        "com.asus.deskclock",
        "com.lge.clock",
        "com.huawei.deskclock",
        // system
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "android"
    )

    private var loops = 0L

    /** Time keeping: real network time, "automatic date & time" kept ON, checkpoint kept outside app data. */
    private fun housekeeping() {
        // network time: every 10 min, or every 30 s until the first successful sync of this boot
        val period = if (TrustedClock.isVerified()) 600L else 30L
        if (loops % period == 2L) TrustedClock.syncAsync(this)

        if (loops % 30L == 1L) {
            // the phone must follow network time; undo a manual switch to "off"
            val auto = try {
                android.provider.Settings.Global.getInt(contentResolver, "auto_time", 1)
            } catch (_: Exception) { 1 }
            if (auto == 0) runRoot("settings put global auto_time 1")
            // mirror the clock checkpoint to Settings.Global (survives clear data / reinstall)
            TrustedClock.mirrorCommand()?.let { runRoot(it) }
        }
    }

    private val guard = object : Runnable {
        override fun run() {
            if (!lock.isActive()) {
                mainHandler.post { handleUnlock() }
                return
            }
            try { enforce() } catch (_: Exception) {}
            loops++
            try { housekeeping() } catch (_: Exception) {}
            bg.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lock = TimeLockManager(this)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, FocusDeviceAdminReceiver::class.java)
        startForegroundNotification()
        worker = HandlerThread("focus-guard").also { it.start() }
        bg = Handler(worker.looper)
        bg.post(guard)
        // Safe-mode / uninstall protection: backup APK + root module that re-installs the app
        Thread { try { SelfHeal.install(this) } catch (_: Exception) {} }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }

    // ── Unlock after 30 days ─────────────────────────────────────────────────
    private fun handleUnlock() {
        lock.unlock()
        Thread { try { SelfHeal.remove() } catch (_: Exception) {} }.start()
        try {
            if (dpm.isAdminActive(adminComponent)) dpm.removeActiveAdmin(adminComponent)
        } catch (_: Exception) {}

        if (!lock.isNotified()) {
            sendCompletionNotification()
            lock.markNotified()
        }
        stopSelf()
    }

    private fun sendCompletionNotification() {
        val channelId = "focuslock_complete"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Focus Complete", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Focus period complete! 🎉")
            .setContentText("ඔයා 30 days challenge complete කළා! දැන් app delete කරන්න පුළුවන්.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    // ── Root helpers ─────────────────────────────────────────────────────────
    private fun runRoot(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) { null }

    private val componentRegex = Regex("""u\d+\s+([\w.]+)/([\w.$]+)""")

    private fun enforce() {
        // 1. Keep Device Admin switched on
        if (!dpm.isAdminActive(adminComponent)) {
            runRoot("dpm set-active-admin --user 0 com.focuslock/com.focuslock.FocusDeviceAdminReceiver")
        }

        // 2. Which app is in front?
        val out = runRoot(
            "dumpsys window | grep mCurrentFocus; " +
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
        ) ?: return
        val m = componentRegex.find(out) ?: return
        val pkg = m.groupValues[1]
        var cls = m.groupValues[2]
        if (cls.startsWith(".")) cls = pkg + cls

        if (pkg in allowed) return
        // Package installer / "install unknown apps" screen, only while an update is in progress
        if (pkg in Updater.installerPackages && Updater.windowOpen(this)) return
        // Any OEM clock/alarm app (package name contains "clock")
        if (pkg.contains("clock", ignoreCase = true)) return
        // Let the Device Admin activation dialog (lives inside Settings) open
        if (cls.endsWith("DeviceAdminAdd")) return

        bringBack()
    }

    private fun bringBack() {
        val r = runRoot("am start -n com.focuslock/com.focuslock.MainActivity --activity-clear-top")
        if (r == null) {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ── Persistent guard notification ────────────────────────────────────────
    private fun startForegroundNotification() {
        val channelId = "focuslock_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Focus Guard", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Focus Lock active")
            .setContentText("30-day study mode is running.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }
}
