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
            try { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            catch (_: Exception) {
                Toast.makeText(this, "Clock app unavailable", Toast.LENGTH_SHORT).show()
            }
        }
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
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Focus Lock needs Device Admin to prevent uninstall during your 25-day challenge.")
        }
        startActivity(intent)
    }

    private fun updateUI() {
        // Sri Lanka current time
        currentTime.text = "🕐 ${timeFmt.format(Date())} (SLT)"

        val remaining = lock.remainingMs()
        if (remaining <= 0L) {
            countdown.text = "00d 00h 00m 00s"
            status.text    = "🎉 25-day focus period completed! Well done."
            return
        }
        val d = TimeUnit.MILLISECONDS.toDays(remaining)
        val h = TimeUnit.MILLISECONDS.toHours(remaining)   % 24
        val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        countdown.text = String.format("%02dd %02dh %02dm %02ds", d, h, m, s)
        status.text    = "Focus mode active — O/L 25 day challenge 💪"
    }
}
