package com.focuslock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var lock: TimeLockManager
    private lateinit var countdown: TextView
    private lateinit var status: TextView
    private lateinit var currentTime: TextView
    private lateinit var unlockDate: TextView
    private lateinit var adminWarning: TextView
    private lateinit var updateBanner: TextView
    private var checkingUpdate = false
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val handler = Handler(Looper.getMainLooper())

    // Sri Lanka timezone (UTC+5:30)
    private val sltZone = TimeZone.getTimeZone("Asia/Colombo")
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.US).apply { timeZone = sltZone }
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).apply { timeZone = sltZone }

    private val tick = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lock          = TimeLockManager(this)
        countdown     = findViewById(R.id.countdown)
        status        = findViewById(R.id.status)
        currentTime   = findViewById(R.id.currentTime)
        unlockDate    = findViewById(R.id.unlockDate)
        adminWarning  = findViewById(R.id.adminWarning)
        updateBanner  = findViewById(R.id.updateBanner)
        dpm           = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, FocusDeviceAdminReceiver::class.java)

        lock.startIfNeeded()

        // Show unlock date
        val deadline = lock.deadline()
        if (deadline > 0L) {
            unlockDate.text = "🔓 Unlocks: ${dateFmt.format(Date(deadline))} (SLT)"
        }

        // Request Device Admin if not active
        if (!dpm.isAdminActive(adminComponent)) {
            requestDeviceAdmin()
        }

        startForegroundService(Intent(this, KioskGuardService::class.java))

        // Admin warning tap to re-request
        adminWarning.setOnClickListener { requestDeviceAdmin() }

        findViewById<Button>(R.id.studyButton).setOnClickListener {
            startActivity(Intent(this, StudyWebViewActivity::class.java))
        }
        findViewById<Button>(R.id.whatsappButton).setOnClickListener {
            val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (i != null) startActivity(i)
            else Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.callsButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        }
        findViewById<Button>(R.id.alarmsButton).setOnClickListener {
            if (!openClockApp()) {
                Toast.makeText(this, "Clock app unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Known clock/alarm app package names across OEMs (Xiaomi, Samsung, Google, Oppo, OnePlus, Vivo...). */
    private val clockPackages = listOf(
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.samsung.android.app.clockpackage",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.oneplus.deskclock",
        "com.android.BBKClock",
        "com.vivo.alarmclock",
        "com.motorola.blur.alarmclock",
        "com.asus.deskclock",
        "com.lge.clock",
        "com.sonyericsson.organizer",
        "com.huawei.deskclock"
    )

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    private fun openClockApp(): Boolean {
        // 1. Standard "show alarms" intent
        if (tryStart(Intent(AlarmClock.ACTION_SHOW_ALARMS))) return true

        // 2. Launch known clock apps directly
        for (pkg in clockPackages) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null && tryStart(i)) return true
        }

        // 3. Any installed launcher app whose package name contains "clock"
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0)
            .firstOrNull { it.activityInfo.packageName.contains("clock", ignoreCase = true) }
        if (match != null) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            if (tryStart(i)) return true
        }

        // 4. Last resort: Show timers
        return tryStart(Intent(AlarmClock.ACTION_SHOW_TIMERS))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* blocked */ }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        if (!lock.isActive() && lock.deadline() > 0L) lock.unlock()
        // Check admin status every resume
        adminWarning.visibility =
            if (dpm.isAdminActive(adminComponent)) View.GONE else View.VISIBLE
        checkForUpdate()
    }

    private var readyApk: java.io.File? = null

    /** Looks for a newer release on GitHub, downloads it, then shows a "tap to install" banner. */
    private fun checkForUpdate(force: Boolean = false) {
        if (checkingUpdate || readyApk != null) return
        val prefs = getSharedPreferences("focus_update", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0L) < 5 * 60 * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        checkingUpdate = true
        updateBanner.visibility = View.VISIBLE
        updateBanner.text = "🔄 Checking for updates… (v${BuildConfig.VERSION_CODE})"
        Thread {
            var msg: String? = null
            try {
                val r = Updater.latest()
                if (r == null) {
                    msg = "⚠️ Update check failed: no release found (v${BuildConfig.VERSION_CODE}) — tap to retry"
                } else if (r.code > BuildConfig.VERSION_CODE) {
                    runOnUiThread { updateBanner.text = "⬇️ Downloading update ${r.name}…" }
                    val apk = Updater.download(this, r)
                    if (apk == null) {
                        msg = "⚠️ Update ${r.name} could not be downloaded or verified — tap to retry"
                    } else {
                        readyApk = apk
                        runOnUiThread {
                            updateBanner.text = "⬆️ Update ${r.name} ready — tap to install"
                            updateBanner.setOnClickListener {
                                try { Updater.install(this, apk) }
                                catch (e: Exception) {
                                    Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                } else {
                    msg = "✅ v${BuildConfig.VERSION_CODE} — up to date (tap to re-check)"
                }
            } catch (e: Exception) {
                msg = "⚠️ Update check failed: ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""} — tap to retry"
            } finally {
                checkingUpdate = false
            }
            val m = msg
            if (m != null) runOnUiThread {
                updateBanner.text = m
                updateBanner.setOnClickListener { checkForUpdate(force = true) }
            }
        }.start()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Focus Lock needs Device Admin to prevent uninstall during your 30-day challenge.")
        }
        startActivity(intent)
    }

    private fun updateUI() {
        // Sri Lanka current time
        currentTime.text = "🕐 ${timeFmt.format(Date(TrustedClock.now(this)))} (SLT${if (TrustedClock.isVerified()) " ✓" else ""})"

        val remaining = lock.remainingMs()
        if (remaining <= 0L) {
            countdown.text = "00d 00h 00m 00s"
            status.text    = "🎉 30-day focus period completed! Well done."
            return
        }
        val d = TimeUnit.MILLISECONDS.toDays(remaining)
        val h = TimeUnit.MILLISECONDS.toHours(remaining)   % 24
        val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        countdown.text = String.format("%02dd %02dh %02dm %02ds", d, h, m, s)
        status.text    = "Focus mode active — O/L 30 day challenge 💪"
    }
}
