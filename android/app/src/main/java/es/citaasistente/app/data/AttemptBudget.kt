package es.citaasistente.app.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Presupuesto de intentos por NIE y día (hora de España). El portal permite 5 pulsaciones de «Solicitar cita»
 * al día por NIE; a la sexta bloquea ese NIE hasta las 00:00. Aquí solo hay lógica pura (se prueba sin Android).
 */
object AttemptBudget {

    const val DAILY_LIMIT = 5

    /** Espera de seguridad entre intentos del mismo NIE tras un «sin citas» (reparte los intentos a lo largo del día). */
    const val COOLDOWN_MIN = 30L

    private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

    data class State(val date: String, val used: Map<String, Int>, val cooldown: Map<String, Long>)

    fun today(now: Long): String = Instant.ofEpochMilli(now).atZone(MADRID).toLocalDate().toString()

    fun empty(now: Long): State = State(today(now), emptyMap(), emptyMap())

    /** Si cambió el día (en España), todo vuelve a cero. */
    fun parse(raw: String, now: Long): State {
        val t = today(now)
        return try {
            val o = JSONObject(raw)
            if (o.optString("date") != t) {
                empty(now)
            } else {
                State(t, readInts(o.optJSONObject("used")), readLongs(o.optJSONObject("cool")))
            }
        } catch (e: Exception) {
            empty(now)
        }
    }

    private fun readInts(o: JSONObject?): Map<String, Int> {
        val m = HashMap<String, Int>()
        if (o == null) return m
        o.keys().forEach { m[it] = o.optInt(it) }
        return m
    }

    private fun readLongs(o: JSONObject?): Map<String, Long> {
        val m = HashMap<String, Long>()
        if (o == null) return m
        o.keys().forEach { m[it] = o.optLong(it) }
        return m
    }

    fun toRaw(s: State): String {
        val u = JSONObject()
        s.used.forEach { (k, v) -> u.put(k, v) }
        val c = JSONObject()
        s.cooldown.forEach { (k, v) -> c.put(k, v) }
        return JSONObject().put("date", s.date).put("used", u).put("cool", c).toString()
    }

    fun used(s: State, doc: String): Int = s.used[doc] ?: 0

    fun add(s: State, doc: String, n: Int = 1): State =
        s.copy(used = s.used + (doc to (used(s, doc) + n)))

    fun setUsed(s: State, doc: String, n: Int): State =
        s.copy(used = s.used + (doc to n.coerceIn(0, DAILY_LIMIT)))

    fun block(s: State, doc: String): State = setUsed(s, doc, DAILY_LIMIT)

    fun coolUntil(s: State, doc: String, untilMs: Long): State =
        s.copy(cooldown = s.cooldown + (doc to untilMs))

    /** null = se puede intentar; «limit» = agotó los intentos de hoy; «cooldown» = en espera de seguridad. */
    fun reason(s: State, doc: String, now: Long, ignoreCooldown: Boolean): String? {
        if (used(s, doc) >= DAILY_LIMIT) return "limit"
        if (!ignoreCooldown && (s.cooldown[doc] ?: 0L) > now) return "cooldown"
        return null
    }

    fun msToMidnight(now: Long): Long {
        val z = Instant.ofEpochMilli(now).atZone(MADRID)
        return z.toLocalDate().plusDays(1).atStartOfDay(MADRID).toInstant().toEpochMilli() - now
    }

    /** Cuánto falta para que alguno de esos NIE pueda volver a intentar (0 si ya puede). */
    fun waitMillis(s: State, docs: List<String>, now: Long): Long =
        docs.mapNotNull { d ->
            val cool = s.cooldown[d] ?: 0L
            when {
                used(s, d) >= DAILY_LIMIT -> msToMidnight(now)
                cool > now -> cool - now
                else -> null
            }
        }.minOrNull() ?: 0L

    fun describe(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0L) + 59_999L) / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}
