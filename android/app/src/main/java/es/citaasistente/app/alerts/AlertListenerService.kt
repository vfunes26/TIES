package es.citaasistente.app.alerts

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import es.citaasistente.app.data.normalize
import es.citaasistente.app.net.Ev
import es.citaasistente.app.net.Reporter

/**
 * Fuente OPCIONAL de alertas externas (app Extranjería Alerts y mensajes del bot de Telegram).
 * La app ya vigila por sí misma al procesar la cola; esto solo acelera el arranque cuando otra
 * fuente avisa antes. Filtra por paquete antes de leer el contenido.
 */
class AlertListenerService : NotificationListenerService() {

    private var lastKey: String = ""
    private var lastKeyAt: Long = 0L

    override fun onListenerConnected() {
        running = true
        Store.get(this).listenerConnectedAt = System.currentTimeMillis()
        Store.get(this).appendEvent("sistema", "Escucha de alertas externas activa")
    }

    override fun onListenerDisconnected() {
        running = false
        Store.get(this).appendEvent("sistema", "Escucha de alertas desconectada; reintentando")
        try {
            NotificationListenerService.requestRebind(ComponentName(this, AlertListenerService::class.java))
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val store = Store.get(this)
        val settings = store.settings
        val fromAlerts = pkg == AlertParser.ALERTS_PKG
        val fromTelegram = pkg in AlertParser.TELEGRAM_PKGS
        if (!(fromAlerts && settings.sourceAlertsApp) && !(fromTelegram && settings.sourceTelegram)) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        val catalog = runCatching { Catalog.load(this) }.getOrNull() ?: return
        val allProvinces = catalog.provinces.map { it.name }
        val pending = store.requests().filter { it.status == Status.PENDIENTE }

        // Un grupo = misma provincia + trámite. Se interpreta la alerta una vez por grupo.
        val matchedIds = ArrayList<String>()
        var summary = ""
        var kind = AlertParser.interpret(pkg, title, text, null, null, null, allProvinces).also { summary = it.summary }.kind
        for ((_, group) in pending.groupBy { it.groupKey() }) {
            val p = group[0].profile
            val prov = catalog.province(p.provinceCode)
            val proc = prov?.procedure(p.procedureId)
            val others = prov?.procedures?.filter { it.id != p.procedureId }?.map { it.name } ?: emptyList()
            val r = AlertParser.interpret(pkg, title, text, prov?.name, p.procedureId, proc?.name, allProvinces, others)
            kind = r.kind
            if (AlertParser.shouldStart(r, settings.strictMatch)) matchedIds.addAll(group.map { it.id })
        }
        if (kind == Kind.IGNORE && !fromAlerts) return

        // Evitar duplicados (las apps repiten/actualizan la misma notificación).
        val key = normalize(summary)
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastKeyAt < 90_000) return
        lastKey = key; lastKeyAt = now

        if (fromAlerts) store.lastAlertAt = now
        val origen = if (fromAlerts) "Extranjería Alerts" else "Telegram (bot)"
        Reporter.log(this, null,
            when (kind) { Kind.AVAILABLE -> "alerta"; Kind.NONE -> "sin citas"; Kind.IGNORE -> "ignorada" },
            "$origen: $summary" + if (kind == Kind.AVAILABLE) " → ${matchedIds.size} solicitud(es) coinciden" else "")

        if (kind == Kind.AVAILABLE && matchedIds.isNotEmpty()) {
            Reporter.notify(this, Ev.ALERTA, null, "$origen: $summary\nSolicitudes que coinciden: ${matchedIds.size}")
            AutoLauncher.launch(this, matchedIds, "¡Cita disponible! ${matchedIds.size} solicitud(es)", "$summary\nSe inicia el autorrelleno automáticamente.")
        }
    }

    companion object {
        @Volatile var running: Boolean = false
    }
}
