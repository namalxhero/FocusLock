package com.focuslock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Device Admin enabled — now uninstall is blocked
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Focus Lock is active. You cannot remove Device Admin during the 30-day period."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Only reaches here after 30 days (auto-revoke) or force via root
    }
}
