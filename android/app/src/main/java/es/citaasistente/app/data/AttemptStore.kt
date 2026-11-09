package es.citaasistente.app.data

import android.content.Context

/** Guarda en el teléfono cuántos intentos se gastaron hoy con cada NIE y las esperas de seguridad. */
object AttemptStore {

    private const val PREFS = "citaasistente_attempts"

    private fun key(doc: String): String = Nif.clean(doc)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context, now: Long): AttemptBudget.State =
        AttemptBudget.parse(prefs(ctx).getString("state", "") ?: "", now)

    private fun save(ctx: Context, s: AttemptBudget.State) {
        prefs(ctx).edit().putString("state", AttemptBudget.toRaw(s)).apply()
    }

    @Synchronized
    fun allows(ctx: Context, doc: String, ignoreCooldown: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        return AttemptBudget.reason(load(ctx, now), key(doc), now, ignoreCooldown) == null
    }

    @Synchronized
    fun used(ctx: Context, doc: String): Int {
        val now = System.currentTimeMillis()
        return AttemptBudget.used(load(ctx, now), key(doc))
    }

    /** Suma un intento gastado (se llama al pulsar «Solicitar cita»). Devuelve cuántos lleva hoy. */
    @Synchronized
    fun record(ctx: Context, doc: String): Int {
        val now = System.currentTimeMillis()
        val s = AttemptBudget.add(load(ctx, now), key(doc))
        save(ctx, s)
        return AttemptBudget.used(s, key(doc))
    }

    @Synchronized
    fun block(ctx: Context, doc: String) {
        val now = System.currentTimeMillis()
        save(ctx, AttemptBudget.block(load(ctx, now), key(doc)))
    }

    @Synchronized
    fun cooldown(ctx: Context, doc: String, minutes: Long) {
        val now = System.currentTimeMillis()
        save(ctx, AttemptBudget.coolUntil(load(ctx, now), key(doc), now + minutes * 60_000L))
    }

    @Synchronized
    fun waitMillis(ctx: Context, docs: List<String>): Long {
        val now = System.currentTimeMillis()
        return AttemptBudget.waitMillis(load(ctx, now), docs.map { key(it) }, now)
    }

    @Synchronized
    fun set(ctx: Context, doc: String, n: Int) {
        val now = System.currentTimeMillis()
        save(ctx, AttemptBudget.setUsed(load(ctx, now), key(doc), n))
    }

    /** Respuesta del comando /intentos del master: sin datos = resumen; «NIE N» = corrige los intentos usados. */
    @Synchronized
    fun command(ctx: Context, argument: String): String {
        val now = System.currentTimeMillis()

        if (argument.isNotBlank()) {
            val parts = argument.trim().split(Regex("\\s+"))
            val n = parts.getOrNull(1)?.toIntOrNull()
            val doc = Nif.clean(parts[0])

            if (doc.isBlank() || n == null || n < 0 || n > AttemptBudget.DAILY_LIMIT) {
                return "Uso: /intentos NIE N (N de 0 a ${AttemptBudget.DAILY_LIMIT}). Ejemplo: /intentos Y1234567X 2"
            }

            set(ctx, doc, n)
            return "✅ ${Nif.mask(doc)}: ahora cuenta $n/${AttemptBudget.DAILY_LIMIT} intentos usados hoy."
        }

        val s = load(ctx, now)
        val docs = (s.used.keys + s.cooldown.keys).toSortedSet()

        val head = "🎯 Intentos de hoy (${s.date}, hora de España). El portal permite ${AttemptBudget.DAILY_LIMIT} por NIE y día.\n"
        if (docs.isEmpty()) {
            return head + "Hoy no se ha usado ningún intento desde la app.\nSi gastaste alguno a mano: /intentos NIE N"
        }

        val lines = docs.joinToString("\n") { d ->
            val u = AttemptBudget.used(s, d)
            val cool = s.cooldown[d] ?: 0L
            val extra = when {
                u >= AttemptBudget.DAILY_LIMIT ->
                    " 🚫 sin intentos: se reanuda a las 00:00 (faltan ${AttemptBudget.describe(AttemptBudget.msToMidnight(now))})"
                cool > now -> " ⏳ en espera ${AttemptBudget.describe(cool - now)}"
                else -> ""
            }
            "• ${Nif.mask(d)}: $u/${AttemptBudget.DAILY_LIMIT} (quedan ${AttemptBudget.DAILY_LIMIT - u})$extra"
        }

        return head + lines + "\n\nCorregir: /intentos NIE N"
    }
}
