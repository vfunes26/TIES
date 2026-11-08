package es.citaasistente.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import es.citaasistente.app.alerts.HealthChecker
import es.citaasistente.app.data.ActivityStats
import es.citaasistente.app.data.BackupPrefs
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.ProxyRules
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supervisión del master.
 *
 * AVISOS DE EVENTOS (agregar ya lo avisa Reporter):
 *  - Cancelada, reactivada, vencida y ELIMINADA → siempre por Telegram. La eliminada, además, por correo.
 *
 * INFORME DE OPERACIÓN CONTINUO (solo lo envía el teléfono del Usuario UI/UX):
 *  - Cuando hay un cambio (se agrupa ~90 s), cuando se abre la app y cada ~15 min si quedó algo pendiente.
 *  - Telegram: máximo 1 informe cada 10 min. Correo: máximo 1 cada 60 min. Solo si algo cambió.
 *  - Además, 1 informe cada 24 h aunque no haya cambios («sigue operando»).
 * Estos avisos salen aunque estén apagados los interruptores de avisos: son de supervisión.
 */
object ActivityReport {

    /** true = también avisa lo que hace el master en su propio teléfono; false = solo el Usuario UI/UX. */
    private const val REPORT_MASTER_TOO = true

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val TG_MIN_MS = 10L * 60L * 1000L
    private const val MAIL_MIN_MS = 60L * 60L * 1000L
    private const val DEBOUNCE_MS = 90_000L
    private const val PREFS = "citaasistente_activity"

    private val fmt get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var appCtx: Context? = null

    private val debounced = Runnable {
        val c = appCtx
        if (c != null) {
            Thread { sendIfDue(c) }.start()
        }
    }

    /** Lo llama BackupManager.attach: así las vencidas (que detecta el mantenimiento de 30 días) se avisan solas. */
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        Store.expiredListener = { list -> list.forEach { expired(app, it) } }
    }

    /** Hubo un cambio en las solicitudes: programa un informe (se agrupan los cambios seguidos). */
    fun onChange(ctx: Context) {
        appCtx = ctx.applicationContext
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    /** Se abrió la app. Llamar fuera del hilo principal. */
    fun onOpen(ctx: Context) = sendIfDue(ctx.applicationContext)

    /** Trabajo periódico (~15 min). Llamar fuera del hilo principal. */
    fun dailyIfDue(ctx: Context) = sendIfDue(ctx.applicationContext)

    // ------------------------------------------------------------------ avisos de eventos

    fun cancelled(ctx: Context, r: Request) {
        send(ctx, "🚫", "SOLICITUD CANCELADA", r, "", mail = false)
        ClientNotify.status(ctx, r, "🚫 Tu solicitud fue cancelada.")
    }

    fun reactivated(ctx: Context, r: Request) =
        send(ctx, "♻️", "SOLICITUD REACTIVADA", r, "", mail = false)

    fun expired(ctx: Context, r: Request) {
        send(ctx, "⌛", "SOLICITUD VENCIDA", r, "Pasaron 30 días sin procesarse; pasó al historial.", mail = false)
        ClientNotify.status(ctx, r, "⌛ Tu solicitud venció: pasaron 30 días sin conseguir cita.")
    }

    fun deleted(ctx: Context, r: Request) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("deleted", p.getInt("deleted", 0) + 1).apply()
        Store.get(app).appendEvent("manual", "Eliminada: ${r.displayName()}")
        send(ctx, "🗑️", "SOLICITUD ELIMINADA", r, "Se borró definitivamente.", mail = true)
        onChange(app)
    }

    private fun who(ctx: Context): String =
        if (Session.role(ctx) == Role.USER) "👤 Usuario UI/UX" else "🛡️ Master"

    private fun shouldReport(ctx: Context): Boolean =
        REPORT_MASTER_TOO || Session.role(ctx) == Role.USER

    private fun where(r: Request, catalog: Catalog?): String {
        val prov = catalog?.province(r.profile.provinceCode)
        return "${prov?.name ?: r.profile.provinceCode.ifBlank { "Cualquier provincia" }} · ${prov?.procedure(r.profile.procedureId)?.name ?: r.profile.procedureId}"
    }

    private fun send(ctx: Context, icon: String, title: String, r: Request, extra: String, mail: Boolean) {
        val app = ctx.applicationContext
        if (!shouldReport(app)) return

        val s = Store.get(app).settings
        val catalog = try {
            Catalog.load(app)
        } catch (e: Exception) {
            null
        }

        val body = buildString {
            append("$icon $title\n")
            append("${who(app)}\n")
            append("${r.displayName()} (${Nif.mask(r.profile.docNumber)})\n")
            append("${where(r, catalog)}\n")
            append("Estado: ${r.status.label} · intentos ${r.attempts}\n")
            append("Registrada: ${fmt.format(Date(r.createdAt))}")
            if (extra.isNotBlank()) append("\n$extra")
        }

        if (s.telegramReady()) Telegram.send(s, body)
        if (mail && s.mailReady()) {
            Mailer.send(s, "[CitaAsistente] $title — ${r.displayName()}", body)
        }
    }

    // ------------------------------------------------------------------ informe de operación continuo

    @Synchronized
    private fun sendIfDue(app: Context) {
        if (Session.role(app) != Role.USER) return

        val store = Store.get(app)
        val s = store.settings
        val tgOk = s.telegramReady()
        val mailOk = s.mailReady()
        if (!tgOk && !mailOk) return

        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val all = store.requests()
        val deleted = p.getInt("deleted", 0)
        val problems = problems(app, all)

        val fp = fingerprint(all, problems)
        val lastTg = p.getLong("lastTg", 0L)
        val lastMail = p.getLong("lastMail", 0L)
        val changedTg = fp != p.getString("fpTg", "")
        val changedMail = fp != p.getString("fpMail", "")

        val tgDue = tgOk && ((changedTg && now - lastTg >= TG_MIN_MS) || now - lastTg >= DAY_MS)
        val mailDue = mailOk && ((changedMail && now - lastMail >= MAIL_MIN_MS) || now - lastMail >= DAY_MS)
        if (!tgDue && !mailDue) return

        val last = p.getLong("lastReport", 0L)
        val since = if (last > 0L) last else now - DAY_MS
        val text = reportText(app, store, all, since, now, deleted, problems)

        val edit = p.edit()

        if (tgDue) {
            Telegram.send(s, text)
            edit.putLong("lastTg", now).putString("fpTg", fp)
        }
        if (mailDue) {
            Mailer.send(s, "[CitaAsistente] Informe de operación — ${Session.label(app)}", text)
            edit.putLong("lastMail", now).putString("fpMail", fp)
        }

        edit.putLong("lastReport", now).putInt("deleted", 0).apply()
    }

    private fun problems(app: Context, all: List<Request>): List<String> {
        val d = ActivityStats.compute(all, 0L)
        val bp = BackupPrefs.get(app)

        val health: List<HealthChecker.Item> = try {
            HealthChecker.check(app).filter { !it.ok && it.critical }
        } catch (e: Exception) {
            emptyList()
        }

        val out = ArrayList<String>()
        if (d.failedActive > 0) out.add("${d.failedActive} solicitud(es) fallida(s) sin atender")
        health.forEach { out.add("${it.label}: ${it.detail}") }
        if (bp.lastError.isNotBlank()) out.add("Respaldo: ${bp.lastError}")
        return out
    }

    /** Resumen del estado: si cambia, hay algo nuevo que informar. */
    private fun fingerprint(all: List<Request>, problems: List<String>): String =
        "${all.size}|${all.count { it.archivedAt == 0L }}|${all.maxOfOrNull { it.updatedAt } ?: 0L}|${problems.joinToString(";")}"

    private fun reportText(
        app: Context,
        store: Store,
        all: List<Request>,
        since: Long,
        now: Long,
        deleted: Int,
        problems: List<String>
    ): String {
        val d = ActivityStats.compute(all, since)
        val s = store.settings
        val bp = BackupPrefs.get(app)

        val proxies = store.proxies()
        val verified = proxies.count { it.status == 1 || it.status == 3 }
        val active = proxies.count { ProxyRules.isUsable(it) }
        val disabled = proxies.count { !it.enabled }

        return buildString {
            append("📊 INFORME DE OPERACIÓN\n")
            append("${who(app)} · v${es.citaasistente.app.BuildConfig.VERSION_NAME} · ${fmt.format(Date(now))}\n")
            append(if (problems.isEmpty()) "Estado: ✅ operando con normalidad\n" else "Estado: ⚠️ requiere atención\n")
            append("\nDesde el último informe\n")
            append("• Agregadas: ${d.added} · Con cita: ${d.ok} · Canceladas: ${d.cancelled}\n")
            append("• Vencidas: ${d.expired} · Fallidas: ${d.failed} · Eliminadas: $deleted\n")
            append("\nActivas ahora: ${d.active} (pendientes ${d.pending} · en proceso ${d.processing} · esperando captcha/SMS ${d.waitingHuman} · fallidas ${d.failedActive})\n")
            append("Última actividad: ${if (d.lastActivity > 0L) fmt.format(Date(d.lastActivity)) else "ninguna"}\n")
            append("Proxy: ${if (s.proxyEnabled) "activado · $active activos de ${proxies.size} · $verified verificados · $disabled desactivados" else "desactivado"}\n")
            append("Último respaldo: ${if (bp.lastOkAt > 0L) fmt.format(Date(bp.lastOkAt)) else "nunca"}")
            if (problems.isNotEmpty()) {
                append("\n\n⚠️ Problemas\n")
                problems.forEach { append("• $it\n") }
            }
        }
    }
}
