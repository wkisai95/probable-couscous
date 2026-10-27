package com.wkisai.tokenarb

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Scansione in background ogni 15 minuti (intervallo minimo di Android). */
class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Store.loadSettings(applicationContext)
        try {
            val outcome = Scanner.run(s)
            Store.saveResults(applicationContext, outcome)
            Notifier.notify(applicationContext, outcome, s)
            Result.success()
        } catch (e: Exception) {
            val prev = Store.loadResults(applicationContext)
            Store.saveResults(applicationContext, ScanOutcome(
                System.currentTimeMillis(), Market.session(), prev?.tokensFound ?: 0,
                prev?.opportunities ?: emptyList(), listOf("Scansione fallita: ${e.message}")))
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "tokenarb-scan"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
