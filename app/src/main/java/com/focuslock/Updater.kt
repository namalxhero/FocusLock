package com.focuslock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Self-update from GitHub Releases. Network functions are blocking: call them off the main thread. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/namalxhero/FocusLock/releases/latest"
    private const val PREFS = "focus_update"
    private const val WINDOW_MS = 5 * 60 * 1000L

    /** Screens allowed in front (by the guard) while an update is being installed. */
    val installerPackages = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.settings" // "Install unknown apps" permission screen
    )

    data class Release(val code: Int, val name: String, val apkUrl: String)

    fun latest(): Release? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = o.optString("tag_name")
        val code = tag.removePrefix("v").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(code, tag, a.getString("browser_download_url"))
            }
        }
        return null
    }

    /** Downloads the APK and checks it is signed by the SAME key as the installed app. */
    fun download(ctx: Context, r: Release): File? {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "FocusLock-${r.code}.apk")
        if (out.exists() && verify(ctx, out)) return out
        dir.listFiles()?.forEach { it.delete() }

        val tmp = File(dir, "download.part")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) return null
        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(out)) return null
        if (!verify(ctx, out)) { out.delete(); return null }
        return out
    }

    // ── signature helpers ────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun sigFlags() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerBytes(pi: PackageInfo): List<ByteArray> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return sigs?.map { it.toByteArray() } ?: emptyList()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    private fun digest(alg: String, b: ByteArray) = hex(MessageDigest.getInstance(alg).digest(b))

    fun verify(ctx: Context, apk: File): Boolean = try {
        val pm = ctx.packageManager
        val mine = pm.getPackageInfo(ctx.packageName, sigFlags())
        val theirs = pm.getPackageArchiveInfo(apk.absolutePath, sigFlags())
        val a = signerBytes(mine).map { digest("SHA-256", it) }.toSet()
        val b = theirs?.let { signerBytes(it).map { s -> digest("SHA-256", s) }.toSet() } ?: emptySet()
        theirs?.packageName == ctx.packageName && a.isNotEmpty() && a == b
    } catch (_: Exception) { false }

    /** SHA-1 of our signing certificate (used for the Google API key "Android app" restriction). */
    fun certSha1(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, sigFlags())
        signerBytes(pi).firstOrNull()?.let { digest("SHA-1", it) } ?: ""
    } catch (_: Exception) { "" }

    // ── install window (lets the guard allow the installer screens briefly) ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun windowOpen(ctx: Context): Boolean =
        System.currentTimeMillis() < prefs(ctx).getLong("window_until", 0L)

    private fun openWindow(ctx: Context) {
        prefs(ctx).edit().putLong("window_until", System.currentTimeMillis() + WINDOW_MS).commit()
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Starts the install. Android shows its own confirm screen; the app restarts after it. */
    fun install(ctx: Context, apk: File) {
        openWindow(ctx)
        if (!canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { i ->
                s.openWrite("FocusLock.apk", 0, apk.length()).use { o ->
                    i.copyTo(o)
                    s.fsync(o)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags
            )
            s.commit(pending.intentSender)
        }
    }
}
