package com.example.callsync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * App update check.
 * The server keeps  <SERVER_URL>/ui/apk/version.json :
 *   {"versionCode":145,"versionName":"7.0.45","url":"https://linkmy.in/backend/ui/apk/CallSync-apk/CallSync.apk","notes":"..."}
 *
 * The app does NOT install anything itself (it has no "install other apps" permission,
 * which banking / payment apps treat as a risk). The download link is opened in the
 * phone's browser, and Android's own installer takes over from there.
 */
object ApkUpdater {

    fun currentCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) { 0L }

    /** Reads version.json. Result: {available, versionCode, versionName, notes, url, current, currentName} */
    fun check(ctx: Context): JSONObject {
        val base = Config.SERVER_URL.trim().trimEnd('/')
        val info = JSONObject(get("$base/ui/apk/version.json", 20_000))
        val code = info.optLong("versionCode", 0L)
        val url = info.optString("url")
        val cur = currentCode(ctx)
        return JSONObject()
            .put("available", code > cur && url.startsWith("https://"))
            .put("versionCode", code)
            .put("versionName", info.optString("versionName"))
            .put("notes", info.optString("notes"))
            .put("url", url)
            .put("current", cur)
            .put("currentName", DeviceInfo.appVersion(ctx))
            .put("ready", false)
            .put("browser", false)
    }

    /** Opens the APK link in the browser. Returns false if no browser could open it. */
    fun openInBrowser(act: Activity, url: String): Boolean {
        if (!url.startsWith("https://")) return false
        return try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }
    }

    private fun get(url: String, max: Int): String {
        val c = URL(url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.useCaches = false
            if (c.responseCode != 200) throw IOException("version.json: HTTP ${c.responseCode}")
            val t = c.inputStream.bufferedReader().use { it.readText() }
            if (t.length > max) throw IOException("version.json too large")
            return t
        } finally {
            c.disconnect()
        }
    }
}
