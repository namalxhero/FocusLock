package com.focuslock

import android.content.Context

/**
 * 30-day lock. The unlock moment is an absolute date baked into the app, and "now" comes from
 * [TrustedClock] (not the phone's date/time setting), so changing the date, going offline,
 * updating, clearing app data or reinstalling can never shorten, restart or extend the lock.
 */
class TimeLockManager(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("focus_lock", Context.MODE_PRIVATE)

    private fun trustedNow(): Long = TrustedClock.now(ctx)

    fun startIfNeeded(): Long {
        if (!prefs.contains(KEY_NOTIFIED)) {
            // First run, or app data was cleared: nothing here can move the unlock date
            prefs.edit()
                .putLong(KEY_STARTED, FIXED_DEADLINE_MS - FOCUS_PERIOD_MS)
                .putBoolean(KEY_NOTIFIED, trustedNow() >= FIXED_DEADLINE_MS)
                .commit()
        }
        return FIXED_DEADLINE_MS
    }

    fun deadline(): Long = FIXED_DEADLINE_MS

    fun isActive(): Boolean = trustedNow() < FIXED_DEADLINE_MS

    fun remainingMs(at: Long = trustedNow()): Long = (FIXED_DEADLINE_MS - at).coerceAtLeast(0L)

    fun isNotified(): Boolean = prefs.getBoolean(KEY_NOTIFIED, false)

    fun markNotified() {
        prefs.edit().putBoolean(KEY_NOTIFIED, true).commit()
    }

    /** Called only after the 30 days expire naturally */
    fun unlock() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).commit()
    }

    companion object {
        const val FOCUS_PERIOD_MS = 30L * 24 * 60 * 60 * 1000
        // 17 Dec 2026, 15:00 (3:00 PM) Sri Lanka time
        const val FIXED_DEADLINE_MS = 1797499800000L
        private const val KEY_STARTED   = "started_at"
        private const val KEY_NOTIFIED  = "notified"
        private const val KEY_COMPLETED = "completed"
    }
}
