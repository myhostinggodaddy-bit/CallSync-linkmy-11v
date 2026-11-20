package com.example.callsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Foreground service: uploads new calls every 5 minutes, even when the app is closed. */
class SyncService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_SYNC = "com.example.callsync.SYNC"
        private const val CHANNEL_ID = "callsync_bg"
        private const val NOTIF_ID = 1001
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            try {
                val n = buildNotification("Starting…")
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                foregroundStarted = true
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (Prefs(this).token == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        executor.execute { doSync() }
        return START_STICKY
    }

    private fun doSync() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callsync:sync")
        wl.acquire(2 * 60 * 1000L)
        try {
            val r = SyncEngine.run(this)
            val t = DateFormat.getTimeFormat(this).format(Date())
            val text = if (r.ok) "Last sync $t • updates every 5 min" else "Sync issue • retrying in 5 min"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } finally {
            if (wl.isHeld) wl.release()
            SyncAlarm.schedule(this)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("CallSync is running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        executor.shutdown()
        super.onDestroy()
    }
}

object ServiceStarter {
    /** Starts the service if registered and not already running. Never throws. */
    fun start(ctx: Context) {
        if (Prefs(ctx).token == null || SyncService.running) return
        try {
            val i = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            // Android refused a background start; the 15-minute worker will try again.
        }
    }
}
