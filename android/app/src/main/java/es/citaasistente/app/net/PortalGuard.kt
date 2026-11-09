package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.AttemptBudget
import es.citaasistente.app.data.AttemptStore
import es.citaasistente.app.data.Ids
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.PortalNotice
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store

/**
 * Interpreta lo que responde la sede, cuida los intentos del día de cada NIE y te lo cuenta por Telegram al instante:
 * qué solicitud, qué mensaje apareció (figura), qué significa y qué hace la app (seguir, esperar o parar).
 * No intenta saltarse ningún bloqueo: al contrario, para y espera.
 */
object PortalGuard {

    private val lastSent = HashMap<String, Long>()

    /** Lo llama AssistantActivity con cada informe del motor de autorrelleno. */
    fun onReport(ctx: Context, r: Request, step: String, status: String, detail: String) {
        val app = ctx.applicationContext
        val doc = r.profile.docNumber

        // «Solicitar cita» se pulsa justo al rellenar la sede: ahí se gasta un intento.
        if (step == "office" && status == "filled") {
            val used = AttemptStore.record(app, doc)
            val left = AttemptBudget.DAILY_LIMIT - used

            when {
                left <= 0 -> send(
                    app, "bud0-${AttemptBudget.today(System.currentTimeMillis())}-${Nif.clean(doc)}", 12L * H,
                    "⚠️ ÚLTIMO INTENTO DEL DÍA USADO\n${header(app, r)}\n" +
                        "Intentos de hoy con este NIE: $used/${AttemptBudget.DAILY_LIMIT}.\n" +
                        "Acción: no se hacen más intentos con este NIE hasta las 00:00 (hora de España; faltan " +
                        "${AttemptBudget.describe(AttemptBudget.msToMidnight(System.currentTimeMillis()))})."
                )
                left == 1 -> send(
                    app, "bud1-${AttemptBudget.today(System.currentTimeMillis())}-${Nif.clean(doc)}", 12L * H,
                    "⚠️ Queda 1 intento hoy\n${header(app, r)}\nIntentos de hoy con este NIE: $used/${AttemptBudget.DAILY_LIMIT}."
                )
            }
            return
        }

        if (status != "no_slots" && status != "nie_blocked") return

        val classified = PortalNotice.classify(detail)
        val kind = if (status == "nie_blocked") PortalNotice.Kind.FIG4_BLOQUEO else classified.kind
        val quote = "«${detail.replace(Regex("\\s+"), " ").trim().take(240)}»"

        when (kind) {
            PortalNotice.Kind.FIG4_BLOQUEO -> {
                AttemptStore.block(app, doc)
                val wait = AttemptBudget.describe(AttemptBudget.msToMidnight(System.currentTimeMillis()))
                val code = if (classified.code.isNotBlank()) "\nCod. Oper.: ${classified.code} ${classified.stamp}".trimEnd() else ""

                send(
                    app, "f4-${AttemptBudget.today(System.currentTimeMillis())}-${Nif.clean(doc)}", 24L * H,
                    "🚫 BLOQUEO DEL PORTAL (Fig. 4)\n${header(app, r)}$code\n" +
                        "Mensaje del portal: $quote\n\n" +
                        "Qué significa: este NIE superó los ${AttemptBudget.DAILY_LIMIT} intentos del día. Aunque haya citas, el portal no deja " +
                        "solicitar con él hasta las 00:00 (hora de España).\n" +
                        "Acción: DETENIDA. No se reintenta con este NIE hoy; reanudo tras las 00:00 (faltan $wait). " +
                        "Las solicitudes con otros NIE siguen su curso."
                )
            }

            PortalNotice.Kind.FIG1_SIN_CLAVE -> {
                AttemptStore.cooldown(app, doc, AttemptBudget.COOLDOWN_MIN)
                send(
                    app, "f1-${Nif.clean(doc)}", 5L * M,
                    "ℹ️ SIN CITAS SIN CL@VE (Fig. 1)\n${header(app, r)}\n" +
                        "Mensaje del portal: $quote\n\n" +
                        "Qué significa: no hay citas para reservar SIN Cl@ve (sí las hay con Cl@ve/certificado digital). " +
                        "Se gastó un intento.\n" + attemptsLine(app, doc) +
                        "Acción: PAUSA de ${AttemptBudget.COOLDOWN_MIN} min con este NIE antes del siguiente intento."
                )
            }

            PortalNotice.Kind.FIG3_SIN_CITAS -> {
                AttemptStore.cooldown(app, doc, AttemptBudget.COOLDOWN_MIN)
                send(
                    app, "f3-${Nif.clean(doc)}", 5L * M,
                    "⏸ SIN CITAS (Fig. 3)\n${header(app, r)}\n" +
                        "Mensaje del portal: $quote\n\n" +
                        "Qué significa: se pulsó «Solicitar cita» y no había citas; se gastó un intento.\n" + attemptsLine(app, doc) +
                        "Acción: PAUSA de ${AttemptBudget.COOLDOWN_MIN} min con este NIE antes del siguiente intento."
                )
            }

            PortalNotice.Kind.FIG2_AVISO -> {
                send(
                    app, "f2-${Nif.clean(doc)}", 30L * M,
                    "ℹ️ AVISO INFORMATIVO DEL PORTAL (Fig. 2)\n${header(app, r)}\n" +
                        "Mensaje del portal: $quote\n\n" +
                        "Qué significa: el portal avisa de que no hay citas. NO gasta intentos (recargar es gratis).\n" +
                        attemptsLine(app, doc) +
                        "Acción: espero y vuelvo a mirar más tarde; no se pulsa «Solicitar cita»."
                )
            }

            PortalNotice.Kind.NONE -> {}
        }
    }

    private const val M = 60_000L
    private const val H = 60L * M

    private fun attemptsLine(app: Context, doc: String): String =
        "Intentos de hoy con este NIE: ${AttemptStore.used(app, doc)}/${AttemptBudget.DAILY_LIMIT}.\n"

    private fun who(app: Context): String =
        if (Session.role(app) == Role.USER) "👤 Usuario UI/UX" else "🛡️ Master"

    private fun header(app: Context, r: Request): String =
        "${who(app)}\nSolicitud ${Ids.short(r.id)} · ${r.displayName()} (${Nif.mask(r.profile.docNumber)})"

    /** Solo Telegram, al instante, y sin repetir el mismo aviso dentro del plazo. */
    private fun send(app: Context, key: String, everyMs: Long, text: String) {
        val now = System.currentTimeMillis()

        synchronized(lastSent) {
            val last = lastSent[key] ?: 0L
            if (now - last < everyMs) return
            lastSent[key] = now
        }

        val s = Store.get(app).settings
        if (s.telegramReady()) Telegram.send(s, text)
    }
}
