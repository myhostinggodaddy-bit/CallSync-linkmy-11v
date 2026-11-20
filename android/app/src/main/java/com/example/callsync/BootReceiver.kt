package com.example.callsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts background sync after the phone boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Prefs(context).token == null) return
            SyncWorker.schedule(context)
            ServiceStarter.start(context)
        }
    }
}
