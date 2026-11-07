package es.citaasistente.app.data

/**
 * Reglas puras (sin Android) de la lista de proxies:
 * límite de 50, qué proxies se pueden usar y en qué orden se prueban.
 */
object ProxyRules {

    const val MAX_PROXIES = 50

    private val TYPES = setOf("http", "https")

    /** Un proxy es utilizable si está activo, es HTTP/HTTPS y tiene host y puerto válidos. Con o sin usuario/contraseña. */
    fun isUsable(p: ProxyEntry): Boolean =
        p.enabled &&
            p.type.lowercase() in TYPES &&
            p.host.isNotBlank() &&
            p.port in 1..65535

    /** Cuántos proxies más caben sin pasar del máximo. */
    fun room(currentCount: Int): Int = (MAX_PROXIES - currentCount).coerceAtLeast(0)

    /**
     * Candidatos en orden circular a partir del cursor, sin los ya descartados en esta ejecución.
     * El cursor cuenta sobre la lista de proxies utilizables.
     */
    fun candidates(
        all: List<ProxyEntry>,
        cursor: Int,
        exclude: Set<String> = emptySet()
    ): List<ProxyEntry> {
        val usable = all.filter { isUsable(it) }
        if (usable.isEmpty()) return emptyList()

        val start = cursor.coerceAtLeast(0) % usable.size
        val rotated = usable.drop(start) + usable.take(start)
        return rotated.filter { it.id !in exclude }
    }

    /** Cursor para la próxima ejecución: el proxy siguiente al que se acaba de elegir. */
    fun nextCursor(all: List<ProxyEntry>, chosen: ProxyEntry): Int {
        val usable = all.filter { isUsable(it) }
        if (usable.isEmpty()) return 0

        val index = usable.indexOfFirst { it.id == chosen.id }
        return if (index < 0) 0 else (index + 1) % usable.size
    }
}
