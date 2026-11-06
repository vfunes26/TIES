package es.citaasistente.app.alerts

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import es.citaasistente.app.data.Store
import java.util.concurrent.TimeUnit

/** Cada ~15 min revisa la cadena de alertas y te avisa SOLO cuando algo cambia (falla o se recupera). */
class HealthWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val store = Store.get(applicationContext)
        val bad = HealthChecker.check(applicationContext).filter { !it.ok && it.critical }
        val key = bad.joinToString("|") { it.label }
        if (key != store.lastHealthIssues) {
            if (bad.isNotEmpty()) {
                Notifier.health(applicationContext, "Atención: las alertas pueden no llegar", bad.joinToString("\n") { "• ${it.label}: ${it.detail}" })
                store.appendEvent("salud", "Problema: $key")
            } else if (store.lastHealthIssues.isNotEmpty()) {
                Notifier.health(applicationContext, "Todo en orden", "El sistema de alertas vuelve a funcionar.")
                store.appendEvent("salud", "Recuperado")
            }
            store.lastHealthIssues = key
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("health", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
