package es.citaasistente.app.alerts

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store

/** Comprobaciones del funcionamiento del sistema. `critical` = sin esto puede que no te avise. */
object HealthChecker {

    data class Item(val ok: Boolean, val critical: Boolean, val label: String, val detail: String)

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun check(ctx: Context): List<Item> {
        val store = Store.get(ctx)
        val s = store.settings
        val out = ArrayList<Item>()
        val externalSources = s.sourceAlertsApp || s.sourceTelegram

        val canNotify = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        out += Item(canNotify, true, "Avisos de esta app", if (canNotify) "Permitidos" else "Actívalos para recibir la alarma")

        val pending = store.requests().count { it.status == Status.PENDIENTE }
        out += Item(true, false, "Cola de solicitudes", "$pending pendiente(s)")

        if (externalSources) {
            val access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            out += Item(access, false, "Alertas externas (acceso a notificaciones)",
                if (access) "Concedido" else "Opcional: concédelo si quieres usar Extranjería Alerts o el bot como fuente")
            if (access) {
                val alive = AlertListenerService.running
                out += Item(alive, false, "Escucha activa", if (alive) "En marcha" else "Reconectando…")
                if (!alive) try {
                    NotificationListenerService.requestRebind(ComponentName(ctx, AlertListenerService::class.java))
                } catch (_: Exception) { }
            }
            if (s.sourceAlertsApp) {
                val inst = isInstalled(ctx, AlertParser.ALERTS_PKG)
                out += Item(inst, false, "App Extranjería Alerts", if (inst) "Instalada (fuente opcional)" else "No instalada: no hace falta, esta app vigila por sí misma")
            }
        }

        if (s.autoLaunch) {
            val can = AutoLauncher.canAutoLaunch(ctx)
            out += Item(can, false, "Autoarranque al detectar cita", if (can) "Permitido" else "Concede «Mostrar sobre otras apps» para arrancar sin tocar la alarma")
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        out += Item(battery, false, "Sin límite de batería", if (battery) "Exento" else "Recomendado")

        if (s.telegramEnabled) {
            val ok = s.telegramToken.isNotBlank() && s.telegramChatId.isNotBlank()
            out += Item(ok, true, "Telegram", if (ok) "Configurado" else "Falta el token o el chat")
        }
        if (s.mailEnabled) {
            val ok = s.mailReady()
            out += Item(ok, true, "Correo", if (ok) "Configurado" else "Faltan datos SMTP o destinatario")
        }

        if (externalSources && s.silenceHours > 0 && store.lastAlertAt > 0) {
            val hours = (System.currentTimeMillis() - store.lastAlertAt) / 3_600_000L
            val quiet = hours >= s.silenceHours
            out += Item(!quiet, false, "Actividad de alertas", if (quiet) "Sin alertas desde hace $hours h" else "Última hace $hours h")
        }
        return out
    }
}
