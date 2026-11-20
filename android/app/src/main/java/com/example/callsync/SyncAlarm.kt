package com.example.callsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms itself after every sync, so the next sync happens ~5 minutes later (also in Doze). */
object SyncAlarm {
    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + Config.SYNC_INTERVAL_MS,
                pending(ctx)
            )
        } catch (e: Exception) {
            // ignore; worker is the fallback
        }
    }

    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        } catch (e: Exception) { }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).token == null) return
        try {
            if (SyncService.running) {
                ctx.startService(Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_SYNC))
            } else {
                ServiceStarter.start(ctx)
            }
        } catch (e: Exception) { }
    }
}
