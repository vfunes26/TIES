package es.citaasistente.app.data

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Contadores por mes. Son permanentes: NO se pierden aunque la solicitud se borre o se purgue el historial.
 * Se guardan como JSON: {"2026-11":{"added":12,"ok":5,"cancelled":2,"expired":1,"failed":1}, ...}
 */
object Ledger {

    const val ADDED = "added"          // solicitudes agregadas
    const val OK = "ok"                // procesadas hasta obtener cita
    const val CANCELLED = "cancelled"  // canceladas a mano
    const val EXPIRED = "expired"      // vencidas (30 días sin procesarse)
    const val FAILED = "failed"        // fallidas

    val FIELDS = listOf(ADDED, OK, CANCELLED, EXPIRED, FAILED)

    data class Row(
        val month: String,
        val added: Int,
        val ok: Int,
        val cancelled: Int,
        val expired: Int,
        val failed: Int
    )

    fun monthKey(t: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    private fun parse(raw: String): JSONObject =
        try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }

    fun bump(raw: String, month: String, field: String, by: Int = 1): String {
        val root = parse(raw)
        val m = root.optJSONObject(month) ?: JSONObject().also { root.put(month, it) }
        m.put(field, m.optInt(field, 0) + by)
        return root.toString()
    }

    /** Une dos contadores quedándose con el mayor valor de cada campo (así restaurar nunca infla los números). */
    fun merge(a: String, b: String): String {
        val ra = parse(a)
        val rb = parse(b)
        val months = HashSet<String>()
        ra.keys().forEach { months.add(it) }
        rb.keys().forEach { months.add(it) }

        val out = JSONObject()
        for (month in months) {
            val ma = ra.optJSONObject(month)
            val mb = rb.optJSONObject(month)
            val merged = JSONObject()
            for (f in FIELDS) {
                merged.put(f, maxOf(ma?.optInt(f, 0) ?: 0, mb?.optInt(f, 0) ?: 0))
            }
            out.put(month, merged)
        }
        return out.toString()
    }

    fun rows(raw: String): List<Row> {
        val root = parse(raw)
        val out = ArrayList<Row>()
        root.keys().forEach { month ->
            val m = root.optJSONObject(month)
            if (m != null) {
                out.add(
                    Row(
                        month,
                        m.optInt(ADDED, 0),
                        m.optInt(OK, 0),
                        m.optInt(CANCELLED, 0),
                        m.optInt(EXPIRED, 0),
                        m.optInt(FAILED, 0)
                    )
                )
            }
        }
        return out.sortedByDescending { it.month }
    }

    /** Construye los contadores a partir de las solicitudes que ya existen (primera vez que se usa la función). */
    fun seed(reqs: List<Request>): String {
        var raw = "{}"
        for (r in reqs) {
            raw = bump(raw, monthKey(r.createdAt), ADDED)
            val month = monthKey(if (r.updatedAt > 0L) r.updatedAt else r.createdAt)
            when (r.status) {
                Status.CON_CITA -> raw = bump(raw, month, OK)
                Status.FALLIDA -> raw = bump(raw, month, FAILED)
                Status.CANCELADA ->
                    raw = bump(
                        raw,
                        month,
                        if (r.failReason.startsWith(Retention.EXPIRED_PREFIX)) EXPIRED else CANCELLED
                    )
                else -> {}
            }
        }
        return raw
    }

    fun toText(raw: String, limit: Int = 12): String {
        val rows = rows(raw)
        if (rows.isEmpty()) return "Sin datos todavía."

        val sb = StringBuilder()
        rows.take(limit).forEach {
            sb.append(
                "${it.month}: agregadas ${it.added} · con cita ${it.ok} · " +
                    "canceladas ${it.cancelled} · vencidas ${it.expired} · fallidas ${it.failed}\n"
            )
        }
        sb.append(
            "TOTAL: agregadas ${rows.sumOf { it.added }} · con cita ${rows.sumOf { it.ok }} · " +
                "canceladas ${rows.sumOf { it.cancelled }} · vencidas ${rows.sumOf { it.expired }} · " +
                "fallidas ${rows.sumOf { it.failed }}"
        )
        return sb.toString()
    }
}
