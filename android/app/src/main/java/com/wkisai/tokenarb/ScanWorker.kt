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
        runScan(applicationContext)
        // in caso di errore si riprova alla prossima esecuzione periodica
        Result.success()
    }

    companion object {
        private const val NAME = "tokenarb-scan"

        /**
         * Scansione completa con salvataggio dei risultati (anche parziali), delle posizioni e delle
         * notifiche. Se è già in corso un'altra scansione non fa nulla e restituisce false.
         */
        fun runScan(ctx: Context): Boolean {
            val s = Store.loadSettings(ctx)
            val st = Store.loadState(ctx)
            val positions = Store.loadPositions(ctx)
            return try {
                val outcome = Scanner.runExclusive(
                    s, st, positions,
                    onUpdate = { partial -> Store.saveResults(ctx, partial) },
                    onPositions = { statuses -> Notifier.notifyPositions(ctx, positions, statuses, s) },
                )
                if (outcome != null) {
                    Store.saveState(ctx, st)
                    Store.saveResults(ctx, outcome)
                    Notifier.notify(ctx, outcome, s)
                }
                outcome != null
            } catch (e: Exception) {
                Store.saveFailure(ctx, e)
                true
            }
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
