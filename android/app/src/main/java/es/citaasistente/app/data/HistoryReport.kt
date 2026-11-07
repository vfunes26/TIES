package es.citaasistente.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texto del historial para mandártelo por correo o Telegram cuando quieras. */
object HistoryReport {

    fun kind(r: Request): String = when {
        r.status == Status.CON_CITA -> "CON CITA"
        Retention.isExpired(r) -> "VENCIDA"
        r.status == Status.CANCELADA -> "CANCELADA"
        else -> r.status.label.uppercase()
    }

    fun text(history: List<Request>, label: (Request) -> String, now: Long): String {
        val f = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))
        val sb = StringBuilder()

        val conCita = history.count { it.status == Status.CON_CITA }
        val vencidas = history.count { Retention.isExpired(it) }
        val canceladas = history.count { it.status == Status.CANCELADA } - vencidas

        sb.append("HISTORIAL TIES · ${f.format(Date(now))}\n")
        sb.append("Registros: ${history.size} · Con cita: $conCita · Canceladas: $canceladas · Vencidas: $vencidas\n")
        sb.append("Cada registro se conserva ${Retention.DAYS} días desde que entra al historial.\n\n")

        history.sortedByDescending { it.archivedAt }.forEach { r ->
            sb.append("• ${f.format(Date(r.archivedAt))} · ${kind(r)} · ${r.displayName()} (${Nif.mask(r.profile.docNumber)})\n")
            sb.append("  ${label(r)}")
            if (r.slot.isNotBlank()) sb.append(" · Cita: ${r.slot}")
            if (r.reference.isNotBlank()) sb.append(" · Ref: ${r.reference}")
            sb.append(" · Intentos: ${r.attempts} · Registrada: ${f.format(Date(r.createdAt))}")
            sb.append(" · Se borra en ${Retention.daysLeft(r, now)} d\n")
        }

        return sb.toString()
    }
}
