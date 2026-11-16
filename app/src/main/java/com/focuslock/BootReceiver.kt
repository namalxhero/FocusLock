package com.focuslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lock = TimeLockManager(context)
        if (!lock.isActive()) return
        // Re-launch MainActivity as home after boot
        val home = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(home)
        // Restart guard service
        context.startForegroundService(Intent(context, KioskGuardService::class.java))
    }
}
