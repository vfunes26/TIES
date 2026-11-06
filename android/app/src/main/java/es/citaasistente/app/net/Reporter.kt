package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Stats
import es.citaasistente.app.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Ev(val icon: String, val title: String) {
    ALERTA("🔔", "ALERTA DE CITA"),
    INICIO("▶️", "INICIO DE SOLICITUD"),
    HUMANO("✋", "NECESITA TU INTERVENCIÓN"),
    SIN_CITAS("⏳", "SIN CITAS"),
    CON_CITA("✅", "¡CITA CONSEGUIDA!"),
    FALLIDA("❌", "SOLICITUD FALLIDA"),
    REGISTRADA("📝", "SOLICITUD REGISTRADA"),
    INFO("ℹ️", "INFO"),
}

/**
 * Punto único de aviso: deja constancia en el historial de la solicitud y en el registro, y reenvía
 * a Telegram y/o correo según lo que hayas activado en Ajustes (cada canal se puede apagar).
 */
object Reporter {
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es", "ES"))

    /** Constancia local (historial de la solicitud + registro global). No envía nada fuera. */
    fun log(ctx: Context, req: Request?, kind: String, text: String) {
        val store = Store.get(ctx)
        if (req != null) store.addHistory(req.id, kind, text)
        store.appendEvent(kind, (req?.let { "${it.displayName()}: " } ?: "") + text)
    }

    fun notify(ctx: Context, ev: Ev, req: Request?, text: String) {
        val store = Store.get(ctx)
        val s = store.settings
        val catalog = runCatching { Catalog.load(ctx) }.getOrNull()
        val fresh = req?.let { store.request(it.id) } ?: req

        if (s.telegramReady()) {
            val allowed = when (ev) {
                Ev.ALERTA -> s.tgAlerts
                Ev.INICIO, Ev.HUMANO, Ev.SIN_CITAS, Ev.REGISTRADA, Ev.INFO -> s.tgProcess
                Ev.CON_CITA, Ev.FALLIDA -> s.tgResults
            }
            if (allowed) Telegram.send(s, buildTelegram(ev, fresh, catalog, text))
        }
        if (s.mailReady()) {
            val allowed = when (ev) {
                Ev.REGISTRADA -> s.mailOnRegistered
                Ev.CON_CITA, Ev.FALLIDA -> s.mailOnResult
                else -> false
            }
            if (allowed) {
                val stats = catalog?.let { Stats.compute(store.requests(), it).toText() }.orEmpty()
                Mailer.send(s, "[CitaAsistente] ${ev.title} — ${fresh?.displayName() ?: ""}", buildMail(ev, fresh, catalog, text, stats))
            }
        }
    }

    /** Resumen/estadística bajo demanda por los canales activos. */
    fun sendSummary(ctx: Context, onDone: (String) -> Unit) {
        val store = Store.get(ctx); val s = store.settings
        val catalog = Catalog.load(ctx)
        val text = "Resumen de solicitudes (${fmt.format(Date())})\n" + Stats.compute(store.requests(), catalog).toText()
        var sent = 0
        if (s.telegramReady()) { Telegram.send(s, "📊 $text"); sent++ }
        if (s.mailReady()) { Mailer.send(s, "[CitaAsistente] Resumen y estadísticas", text); sent++ }
        onDone(if (sent == 0) "Activa y configura Telegram o correo en Ajustes" else "Resumen enviado por $sent canal(es)")
    }

    private fun where(req: Request, catalog: Catalog?): String {
        val prov = catalog?.province(req.profile.provinceCode)
        return "${prov?.name ?: req.profile.provinceCode} · ${prov?.procedure(req.profile.procedureId)?.name ?: req.profile.procedureId}"
    }

    private fun buildTelegram(ev: Ev, req: Request?, catalog: Catalog?, text: String) = buildString {
        append("${ev.icon} ${ev.title}\n")
        if (req != null) {
            append("${req.displayName()} (${Nif.mask(req.profile.docNumber)})\n${where(req, catalog)}\n")
            append("Estado: ${req.status.label} · intento ${req.attempts}\n")
        }
        append(text)
    }

    private fun buildMail(ev: Ev, req: Request?, catalog: Catalog?, text: String, stats: String) = buildString {
        append("${ev.title}\nFecha: ${fmt.format(Date())}\n\n")
        if (req != null) {
            val p = req.profile
            append("SOLICITUD ${req.id.take(8)}\n")
            append("Persona: ${p.fullName}\nDocumento: ${p.docType} ${p.docNumber}\nNacionalidad: ${p.nationalityLabel}\n")
            append("Teléfono: ${p.phone}\nCorreo: ${p.email}\n")
            append("Trámite: ${where(req, catalog)}\nSedes: ${if (p.offices.isEmpty()) "cualquiera" else p.offices.joinToString(" | ")}\n")
            append("Fecha mínima aceptada: ${p.minDate}\nEstado: ${req.status.label} · intentos: ${req.attempts}\n")
            if (req.slot.isNotBlank()) append("Cita elegida: ${req.slot}\n")
            if (req.reference.isNotBlank()) append("Justificante: ${req.reference}\n")
            if (req.failReason.isNotBlank()) append("Motivo del fallo: ${req.failReason}\n")
            append("\n")
        }
        append("$text\n")
        if (req != null && ev != Ev.REGISTRADA) {
            append("\nPROCESO REALIZADO:\n")
            req.history.reversed().forEach { append("  ${fmt.format(Date(it.t))}  [${it.kind}] ${it.text}\n") }
        }
        if (req != null && ev == Ev.REGISTRADA) append("\nDATOS (formato del bot):\n${botJson(req, catalog)}\n")
        if (stats.isNotBlank()) append("\nESTADÍSTICAS\n$stats\n")
    }

    /** Misma estructura que `bot/config.json` (sin credenciales) para que el bot pueda procesar la solicitud. */
    fun botJson(req: Request, catalog: Catalog?): String {
        val p = req.profile
        val prov = catalog?.province(p.provinceCode)
        return JSONObject()
            .put("location", prov?.name ?: p.provinceCode)
            .put("tramiteLabel", prov?.procedure(p.procedureId)?.name ?: "")
            .put("tramiteValue", p.procedureId)
            .put("offices", JSONArray(p.offices))
            .put("minCitaDate", p.minDate)
            .put("personalData", JSONObject()
                .put("nie", p.docNumber).put("nombre", p.fullName).put("paisNac", p.nationalityLabel)
                .put("telefono", p.phone).put("email", p.email))
            .toString(2)
    }
}
