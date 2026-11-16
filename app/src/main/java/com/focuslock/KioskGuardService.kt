package com.focuslock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.Service

/**
 * Foreground service:
 * - Watches foreground app every 1.5s
 * - After 25 days: revokes Device Admin, sends completion notification, stops self
 */
class KioskGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lock: TimeLockManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val allowed = setOf(
        "com.focuslock",
        "com.google.android.youtube",
        "com.whatsapp",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.app.clockpackage",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.systemui",
        "android"
    )

    private val guard = object : Runnable {
        override fun run() {
            if (!lock.isActive()) {
                // 25 days done!
                handleUnlock()
                return
            }
            enforceForeground()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lock = TimeLockManager(this)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, FocusDeviceAdminReceiver::class.java)
        startForegroundNotification()
        handler.post(guard)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(guard)
        super.onDestroy()
    }

    // ── Unlock after 25 days ─────────────────────────────────────────────────
    private fun handleUnlock() {
        lock.unlock()

        // Revoke Device Admin so app can be deleted
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
            }
        } catch (_: Exception) {}

        // Send "Focus period complete!" notification (only once)
        if (!lock.isNotified()) {
            sendCompletionNotification()
            lock.markNotified()
        }

        stopSelf()
    }

    // ── Completion notification ───────────────────────────────────────────────
    private fun sendCompletionNotification() {
        val channelId = "focuslock_complete"
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Focus Complete",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }

        val n = Notification.Builder(this, channelId)
            .setContentTitle("Focus period complete! 🎉")
            .setContentText("ඔයා 25 days challenge complete කළා! දැන් app delete කරන්න පුළුවන්.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        nm.notify(2, n)
    }

    // ── Root shell: enforce foreground ───────────────────────────────────────
    private fun enforceForeground() {
        try {
            val proc = Runtime.getRuntime()
                .exec(arrayOf("su", "-c",
                    "dumpsys activity activities | grep mResumedActivity | head -1"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val pkg = Regex("""(\S+)/\.""").find(out)?.groupValues?.get(1) ?: return
            if (pkg !in allowed) bringBack()
        } catch (_: Exception) {}
    }

    private fun bringBack() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(i)
    }

    // ── Persistent guard notification ────────────────────────────────────────
    private fun startForegroundNotification() {
        val channelId = "focuslock_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "Focus Guard",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Focus Lock active")
            .setContentText("25-day study mode is running.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }
}
