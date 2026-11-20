package com.example.callsync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object ApkInstaller {
    private const val RESULT_ACTION = "in.linkmy.callsync.INSTALL_RESULT"
    private val running = AtomicBoolean(false)
    private var host: MainActivity? = null

    fun start(act: MainActivity, url: String) {
        if (!url.startsWith("https://")) {
            done(false, "Invalid update link")
            return
        }
        if (!running.compareAndSet(false, true)) return
        host = act

        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            running.set(false)
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
            done(false, "Allow 'Install unknown apps' for CallSync, then tap Update again")
            return
        }

        phase("Downloading", 0)
        Thread {
            try {
                val apk = File(act.cacheDir, "update.apk")
                download(url, apk)
                install(act, apk)
                phase("Tap Install on the screen", 100)
            } catch (e: Exception) {
                running.set(false)
                done(false, e.message ?: "Update failed")
            }
        }.start()
    }

    private fun download(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) throw Exception("Server error ${conn.responseCode}")

        val total = conn.contentLengthLong
        var read = 0L
        var last = -1
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val p = (read * 100 / total).toInt()
                        if (p != last) { last = p; phase("Downloading", p) }
                    }
                }
            }
        }
        if (out.length() < 100_000) throw Exception("Downloaded file is too small")
        if (total > 0 && out.length() != total) throw Exception("Download was incomplete, try again")
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)

        pi.openSession(id).use { session ->
            val size = apk.length()
            session.openWrite("base.apk", 0, size).use { out ->
                apk.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val p = (written * 100 / size).toInt()
                        if (p != last) { last = p; phase("Installing", p) }
                    }
                }
                session.fsync(out)
            }

            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(RESULT_ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun phase(text: String, pct: Int) {
        js("window.onUpdatePhase && window.onUpdatePhase(${q(text)}, $pct)")
    }

    internal fun done(ok: Boolean, msg: String) {
        running.set(false)
        js("window.onUpdateDone && window.onUpdateDone($ok, ${q(msg)})")
    }

    private fun js(code: String) {
        host?.js(code)
    }

    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
                ApkInstaller.phase("Tap Install on the screen", 100)
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.done(true, "installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                ApkInstaller.done(false, msg)
            }
        }
    }
}
