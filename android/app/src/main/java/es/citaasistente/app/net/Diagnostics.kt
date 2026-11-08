package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.Ids
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store

/** Avisos de diagnóstico al master: cuando la sede muestra una página que el autorrelleno no reconoce. */
object Diagnostics {

    private const val EVERY_MS = 20L * 60L * 1000L
    private val last = HashMap<String, Long>()

    /** Solo dirección sin parámetros (sin datos personales), el paso y el estado. Como máximo 1 aviso por paso cada 20 min. */
    fun unknownPage(ctx: Context, req: Request, step: String, status: String, detail: String, url: String?) {
        val app = ctx.applicationContext
        val key = "$step/$status"
        val now = System.currentTimeMillis()

        synchronized(last) {
            val t = last[key] ?: 0L
            if (now - t < EVERY_MS) return
            last[key] = now
        }

        val s = Store.get(app).settings
        if (!s.telegramReady()) return

        val clean = (url ?: "").substringBefore('?').substringBefore('#').take(200).ifBlank { "(desconocida)" }

        val text = buildString {
            append("🧩 PÁGINA NO RECONOCIDA\n")
            append("${Session.label(app)} · versión ${es.citaasistente.app.BuildConfig.VERSION_NAME}\n")
            append("Solicitud: ${Ids.short(req.id)}\n")
            append("Paso: $step · estado: $status\n")
            append("Dirección: $clean\n")
            if (detail.isNotBlank()) append("Detalle: ${detail.take(200)}\n")
            append("El autorrelleno necesita ayuda en este paso: puede que la sede haya cambiado.")
        }

        Telegram.send(s, text)
    }
}
