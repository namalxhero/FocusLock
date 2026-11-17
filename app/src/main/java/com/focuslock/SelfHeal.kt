package com.focuslock

import android.content.Context

/**
 * Uninstall / Safe-Mode protection (root).
 *
 * Keeps a small root module + a backup copy of this APK in /data/adb/modules/focuslock_guard.
 * The module's script runs in the background and re-installs FocusLock (and re-activates Device
 * Admin) a few seconds after it is removed, e.g. uninstalled from Safe Mode. The unlock date is
 * baked into the app, so re-installing can never shorten the lock. When the lock ends the app
 * removes the module itself ([remove]).
 */
object SelfHeal {
    private const val DIR = "/data/adb/modules/focuslock_guard"

    private const val MODULE_PROP = """id=focuslock_guard
name=FocusLock Guard
version=1
versionCode=1
author=FocusLock
description=Re-installs FocusLock if it gets removed (Safe Mode protection). Removed automatically when the lock ends."""

    // § is replaced by $ below (keeps Kotlin from treating shell variables as templates)
    private val SERVICE_SH = """#!/system/bin/sh
MODDIR=§{0%/*}
# only one copy of this loop at a time
if [ -f "§MODDIR/run.pid" ] && grep -q focuslock_guard "/proc/§(cat "§MODDIR/run.pid")/cmdline" 2>/dev/null; then exit 0; fi
echo §§ > "§MODDIR/run.pid"
while [ "§(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
sleep 20
while [ -d "§MODDIR" ]; do
  if [ -z "§(pm path com.focuslock 2>/dev/null)" ] && [ -f "§MODDIR/focuslock.apk" ]; then
    pm install -r -g "§MODDIR/focuslock.apk" >/dev/null 2>&1
    sleep 4
    dpm set-active-admin --user 0 com.focuslock/com.focuslock.FocusDeviceAdminReceiver >/dev/null 2>&1
    am start -n com.focuslock/com.focuslock.MainActivity >/dev/null 2>&1
  fi
  sleep 15
done
""".replace('§', '$')

    private fun su(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) { null }

    /** Create/refresh the module and make sure its loop is running. Call from a background thread. */
    fun install(ctx: Context) {
        val apk = ctx.applicationInfo.sourceDir
        val propB64 = android.util.Base64.encodeToString(MODULE_PROP.toByteArray(), android.util.Base64.NO_WRAP)
        val shB64 = android.util.Base64.encodeToString(SERVICE_SH.toByteArray(), android.util.Base64.NO_WRAP)
        su(
            "mkdir -p $DIR && " +
            "echo '$propB64' | base64 -d > $DIR/module.prop && " +
            "echo '$shB64' | base64 -d > $DIR/service.sh && " +
            "chmod 755 $DIR/service.sh && chmod 644 $DIR/module.prop && " +
            // keep the backup APK identical to the installed one (also after every self-update)
            "(cmp -s '$apk' $DIR/focuslock.apk || cp -f '$apk' $DIR/focuslock.apk) && " +
            "chmod 644 $DIR/focuslock.apk && rm -f $DIR/disable $DIR/remove && " +
            "(nohup sh $DIR/service.sh >/dev/null 2>&1 &)"
        )
    }

    /** Lock finished: remove the module so the app can be uninstalled normally. */
    fun remove() {
        su("rm -rf $DIR")
    }
}
