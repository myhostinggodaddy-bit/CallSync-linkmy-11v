package com.example.callsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Safety net. Android never runs WorkManager more often than every 15 minutes,
 * so the 5-minute upload is done by SyncService. This worker uploads too and
 * restarts the service if the phone's battery manager killed it.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        SyncEngine.run(applicationContext)
        ServiceStarter.start(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("callsync_periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }
}
