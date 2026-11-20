package com.example.callsync

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live UI updates: put a newer index.html + version.txt in <SERVER_URL>/ui/ on your hosting
 * and every phone picks it up the next time the app is opened. No new APK needed.
 */
object UiUpdater {
    @Volatile private var lastCheck = 0L

    private fun cachedFile(ctx: Context) = File(ctx.filesDir, "ui/index.html")

    fun cachedPrefix(ctx: Context): String = "file://" + File(ctx.filesDir, "ui").absolutePath

    /** The downloaded UI if it is newer than the bundled one, otherwise the bundled UI. */
    fun currentUrl(ctx: Context): String {
        val f = cachedFile(ctx)
        return if (f.exists() && Prefs(ctx).uiVersion > Config.UI_VERSION)
            "file://" + f.absolutePath
        else "file:///android_asset/www/index.html"
    }

    fun currentVersion(ctx: Context): Int {
        val f = cachedFile(ctx)
        return if (f.exists()) maxOf(Config.UI_VERSION, Prefs(ctx).uiVersion) else Config.UI_VERSION
    }

    /** Checks the server (at most every 10 minutes). Returns true when a newer UI was saved. */
    fun check(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 10 * 60 * 1000L) return false
        lastCheck = now
        return try {
            val base = Config.SERVER_URL.trimEnd('/')
            val remote = download("$base/ui/version.txt", 100).trim().toInt()
            if (remote <= currentVersion(ctx)) return false

            val html = download("$base/ui/index.html", 2_000_000)
            // basic sanity check so a broken upload can never blank the app
            if (!html.contains("</html>") || !html.contains("CallSync")) return false

            val f = cachedFile(ctx)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "index.tmp")
            tmp.writeText(html)
            if (!tmp.renameTo(f)) {
                f.writeText(html)
                tmp.delete()
            }
            Prefs(ctx).uiVersion = remote
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun download(url: String, maxChars: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            if (text.length > maxChars) throw IOException("too large")
            return text
        } finally {
            c.disconnect()
        }
    }
}
