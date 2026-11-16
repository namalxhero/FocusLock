package com.focuslock

import android.content.Context

/**
 * Hard 25-day timer backed by SharedPreferences.
 * Device Admin revoke + notification on completion.
 */
class TimeLockManager(context: Context) {
    private val prefs = context.getSharedPreferences("focus_lock", Context.MODE_PRIVATE)

    fun startIfNeeded(now: Long = System.currentTimeMillis()): Long {
        val existing = prefs.getLong(KEY_DEADLINE, 0L)
        if (existing > 0L) return existing
        val deadline = now + FOCUS_PERIOD_MS
        prefs.edit()
            .putLong(KEY_STARTED, now)
            .putLong(KEY_DEADLINE, deadline)
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_NOTIFIED, false)
            .commit()
        return deadline
    }

    fun deadline(): Long = prefs.getLong(KEY_DEADLINE, 0L)

    fun isActive(): Boolean {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
        return deadline() > System.currentTimeMillis()
    }

    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        val d = deadline()
        if (d == 0L) return FOCUS_PERIOD_MS
        return (d - now).coerceAtLeast(0L)
    }

    fun isNotified(): Boolean = prefs.getBoolean(KEY_NOTIFIED, false)

    fun markNotified() {
        prefs.edit().putBoolean(KEY_NOTIFIED, true).commit()
    }

    /** Called only after 25 days expire naturally */
    fun unlock() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).commit()
    }

    companion object {
        const val FOCUS_PERIOD_MS = 25L * 24 * 60 * 60 * 1000
        private const val KEY_STARTED  = "started_at"
        private const val KEY_DEADLINE = "unlock_at"
        private const val KEY_ACTIVE   = "active"
        private const val KEY_NOTIFIED = "notified"
    }
}
