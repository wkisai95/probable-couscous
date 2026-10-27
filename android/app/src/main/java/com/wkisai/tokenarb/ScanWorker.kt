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
        val ctx = applicationContext
        val s = Store.loadSettings(ctx)
        val st = Store.loadState(ctx)
        try {
            // se è già in corso una scansione (es. avviata dall'app) questa viene saltata
            val outcome = Scanner.runExclusive(s, st) { partial -> Store.saveResults(ctx, partial) }
            if (outcome != null) {
                Store.saveState(ctx, st)
                Store.saveResults(ctx, outcome)
                Notifier.notify(ctx, outcome, s)
            }
        } catch (e: Exception) {
            Store.saveFailure(ctx, e)
        }
        // in caso di errore si riprova alla prossima esecuzione periodica
        Result.success()
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
