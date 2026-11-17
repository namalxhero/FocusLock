package com.focuslock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A clock that does NOT depend on the phone's date/time setting.
 *
 * - While the phone is running: trusted time = anchor + SystemClock.elapsedRealtime() delta
 *   (monotonic, keeps counting in deep sleep, offline, and ignores any date change).
 * - Whenever the internet is available: re-anchored to the real time from an HTTPS "Date" header.
 * - After a reboot while offline: the wall clock is used only for a capped downtime credit
 *   (at most MAX_OFFLINE_DOWNTIME_MS), so moving the date forward cannot skip the lock.
 * - The checkpoint is kept in app prefs AND mirrored (by the guard service, via root) to
 *   Settings.Global, so clearing app data / reinstalling does not lose it.
 */
object TrustedClock {
    private const val PREFS = "focus_clock"
    private const val GLOBAL_KEY = "focuslock_clock"
    private const val MIN_SANE_MS = 1_763_000_000_000L              // ~Nov 2025: older = bogus clock
    private const val MAX_OFFLINE_DOWNTIME_MS = 6L * 60 * 60 * 1000 // reboot-while-offline credit cap
    private const val SAVE_EVERY_MS = 5_000L

    private val hosts = listOf(
        "https://www.google.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://github.com"
    )

    private var loaded = false
    private var boot = -1

    // Trusted time anchorT was true when elapsedRealtime() == anchorE during boot anchorBoot
    private var anchorT = 0L
    private var anchorE = 0L
    private var anchorBoot = -1
    // Last moment we were alive (trusted time + wall clock then): used to estimate downtime
    private var aliveT = 0L
    private var aliveWall = 0L
    private var lastSaveE = 0L

    @Volatile private var verified = false
    private val syncing = AtomicBoolean(false)

    fun isVerified(): Boolean = verified

    @Synchronized
    fun now(ctx: Context): Long {
        load(ctx)
        val e = SystemClock.elapsedRealtime()

        if (anchorT == 0L) {
            // Nothing saved anywhere (very first run): start from the wall clock until the first sync
            val w = System.currentTimeMillis()
            setAnchor(w, e)
            saveAlive(ctx, w, e)
            return w
        }

        val otherBoot = boot != -1 && anchorBoot != -1 && boot != anchorBoot
        if (!otherBoot && e >= anchorE) {
            val t = anchorT + (e - anchorE)
            if (e - lastSaveE >= SAVE_EVERY_MS) saveAlive(ctx, t, e)
            return t
        }

        // Rebooted since the last anchor: credit downtime from the wall clock, but capped
        val wall = System.currentTimeMillis()
        val credit = (wall - aliveWall).coerceIn(e, e + MAX_OFFLINE_DOWNTIME_MS)
        val t = aliveT + credit
        setAnchor(t, e)
        verified = false
        saveAlive(ctx, t, e)
        return t
    }

    /** Re-anchor to real network time (call from any thread; does nothing if already running). */
    fun syncAsync(ctx: Context) {
        if (!syncing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try { sync(app) } finally { syncing.set(false) }
        }.start()
    }

    private fun sync(ctx: Context) {
        for (h in hosts) {
            val net = fetchDate(h) ?: continue
            if (net < MIN_SANE_MS) continue
            synchronized(this) {
                load(ctx)
                val e = SystemClock.elapsedRealtime()
                setAnchor(net, e)
                verified = true
                saveAlive(ctx, net, e)
            }
            return
        }
    }

    private fun fetchDate(url: String): Long? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.instanceFollowRedirects = false
        c.useCaches = false
        val t0 = SystemClock.elapsedRealtime()
        c.responseCode
        val rtt = SystemClock.elapsedRealtime() - t0
        val d = c.date
        c.disconnect()
        if (d <= 0L) null else d + rtt / 2
    } catch (_: Exception) { null }

    /** Shell command (run as root by the guard service) that mirrors the checkpoint outside app data. */
    @Synchronized
    fun mirrorCommand(): String? {
        if (anchorT == 0L) return null
        return "settings put global $GLOBAL_KEY '$anchorT,$anchorE,$anchorBoot,$aliveT,$aliveWall'"
    }

    // ── internals ────────────────────────────────────────────────────────────
    private fun setAnchor(t: Long, e: Long) {
        anchorT = t
        anchorE = e
        anchorBoot = boot
    }

    private fun saveAlive(ctx: Context, t: Long, e: Long) {
        aliveT = t
        aliveWall = System.currentTimeMillis()
        lastSaveE = e
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("anchorT", anchorT).putLong("anchorE", anchorE).putInt("anchorBoot", anchorBoot)
            .putLong("aliveT", aliveT).putLong("aliveWall", aliveWall)
            .apply()
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        boot = try { Settings.Global.getInt(app.contentResolver, "boot_count", -1) } catch (_: Exception) { -1 }

        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        anchorT = p.getLong("anchorT", 0L)
        anchorE = p.getLong("anchorE", 0L)
        anchorBoot = p.getInt("anchorBoot", -1)
        aliveT = p.getLong("aliveT", 0L)
        aliveWall = p.getLong("aliveWall", 0L)

        // Restore from Settings.Global (survives "clear data" / reinstall) if it is newer
        try {
            val g = Settings.Global.getString(app.contentResolver, GLOBAL_KEY)
            val v = g?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
            if (v != null && v.size == 5 && v[3] > aliveT) {
                anchorT = v[0]
                anchorE = v[1]
                anchorBoot = v[2].toInt()
                aliveT = v[3]
                aliveWall = v[4]
            }
        } catch (_: Exception) {}
    }
}
