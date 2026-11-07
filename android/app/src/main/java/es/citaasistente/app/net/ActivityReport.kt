package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.alerts.HealthChecker
import es.citaasistente.app.data.ActivityStats
import es.citaasistente.app.data.BackupPrefs
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supervisión del master: avisos de lo que pasa en cada teléfono (agregar ya lo avisa Reporter).
 *  - Cancelada, reactivada, vencida y ELIMINADA → siempre por Telegram. La eliminada, además, por correo.
 *  - Informe diario de operación del teléfono del Usuario UI/UX → Telegram, una vez al día.
 * Estos avisos salen aunque estén desactivados los interruptores de avisos: son de supervisión.
 */
object ActivityReport {

    /** true = también avisa lo que hace el master en su propio teléfono; false = solo el Usuario UI/UX. */
    private const val REPORT_MASTER_TOO = true

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val PREFS = "citaasistente_activity"

    private val fmt get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))

    /** Lo llama BackupManager.attach: así las vencidas (que detecta el mantenimiento de 30 días) se avisan solas. */
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        Store.expiredListener = { list -> list.forEach { expired(app, it) } }
    }

    fun cancelled(ctx: Context, r: Request) =
        send(ctx, "🚫", "SOLICITUD CANCELADA", r, "", mail = false)

    fun reactivated(ctx: Context, r: Request) =
        send(ctx, "♻️", "SOLICITUD REACTIVADA", r, "", mail = false)

    fun expired(ctx: Context, r: Request) =
        send(ctx, "⌛", "SOLICITUD VENCIDA", r, "Pasaron 30 días sin procesarse; pasó al historial.", mail = false)

    fun deleted(ctx: Context, r: Request) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("deleted", p.getInt("deleted", 0) + 1).apply()
        Store.get(app).appendEvent("manual", "Eliminada: ${r.displayName()}")
        send(ctx, "🗑️", "SOLICITUD ELIMINADA", r, "Se borró definitivamente.", mail = true)
    }

    private fun who(ctx: Context): String =
        if (Session.role(ctx) == Role.USER) "👤 Usuario UI/UX" else "🛡️ Master"

    private fun shouldReport(ctx: Context): Boolean =
        REPORT_MASTER_TOO || Session.role(ctx) == Role.USER

    private fun where(r: Request, catalog: Catalog?): String {
        val prov = catalog?.province(r.profile.provinceCode)
        return "${prov?.name ?: r.profile.provinceCode} · ${prov?.procedure(r.profile.procedureId)?.name ?: r.profile.procedureId}"
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

    // ------------------------------------------------------------------ informe diario

    /** Lo llama el trabajo periódico (~15 min). Solo el teléfono del Usuario UI/UX informa, una vez cada 24 h. */
    fun dailyIfDue(ctx: Context) {
        val app = ctx.applicationContext
        if (Session.role(app) != Role.USER) return

        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = p.getLong("lastDaily", 0L)
        if (now - last < DAY_MS) return

        val store = Store.get(app)
        val s = store.settings
        if (!s.telegramReady()) return

        val since = if (last > 0L) last else now - DAY_MS
        val text = dailyText(app, store, since, now, p.getInt("deleted", 0))

        Telegram.send(s, text)
        p.edit().putLong("lastDaily", now).putInt("deleted", 0).apply()
    }

    private fun dailyText(app: Context, store: Store, since: Long, now: Long, deleted: Int): String {
        val d = ActivityStats.compute(store.requests(), since)
        val s = store.settings
        val bp = BackupPrefs.get(app)

        val health: List<HealthChecker.Item> = try {
            HealthChecker.check(app).filter { !it.ok && it.critical }
        } catch (e: Exception) {
            emptyList()
        }

        val problems = ArrayList<String>()
        if (d.failedActive > 0) problems.add("${d.failedActive} solicitud(es) fallida(s) sin atender")
        health.forEach { problems.add("${it.label}: ${it.detail}") }
        if (bp.lastError.isNotBlank()) problems.add("Respaldo: ${bp.lastError}")

        val proxies = store.proxies()
        val verified = proxies.count { it.status == 1 || it.status == 3 }

        return buildString {
            append("📊 INFORME DIARIO\n")
            append("${who(app)} · ${fmt.format(Date(now))}\n")
            append(if (problems.isEmpty()) "Estado: ✅ operando con normalidad\n" else "Estado: ⚠️ requiere atención\n")
            append("\nDesde el último informe\n")
            append("• Agregadas: ${d.added} · Con cita: ${d.ok} · Canceladas: ${d.cancelled}\n")
            append("• Vencidas: ${d.expired} · Fallidas: ${d.failed} · Eliminadas: $deleted\n")
            append("\nActivas ahora: ${d.active} (pendientes ${d.pending} · en proceso ${d.processing} · esperando captcha/SMS ${d.waitingHuman} · fallidas ${d.failedActive})\n")
            append("Última actividad: ${if (d.lastActivity > 0L) fmt.format(Date(d.lastActivity)) else "ninguna"}\n")
            append("Proxy: ${if (s.proxyEnabled) "activado · ${proxies.size} guardados · $verified verificados" else "desactivado"}\n")
            append("Último respaldo: ${if (bp.lastOkAt > 0L) fmt.format(Date(bp.lastOkAt)) else "nunca"}")
            if (problems.isNotEmpty()) {
                append("\n\n⚠️ Problemas\n")
                problems.forEach { append("• $it\n") }
            }
        }
    }
}
