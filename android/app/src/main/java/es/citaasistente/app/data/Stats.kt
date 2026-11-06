package es.citaasistente.app.data

/** Estadísticas del registro de solicitudes (para la pantalla y para los resúmenes por correo/Telegram). */
data class Stats(
    val total: Int, val pendientes: Int, val enCurso: Int, val conCita: Int, val fallidas: Int, val canceladas: Int,
    val intentos: Int, val intentosPorExito: Double, val porProvincia: List<Triple<String, Int, Int>>, // nombre, total, con cita
) {
    val tasaExito: Int get() = if (total - canceladas <= 0) 0 else Math.round(conCita * 100f / (total - canceladas))

    fun toText(): String = buildString {
        append("Solicitudes: $total · Con cita: $conCita ($tasaExito %) · Pendientes: $pendientes")
        append(" · En curso: $enCurso · Fallidas: $fallidas · Canceladas: $canceladas\n")
        append("Intentos totales: $intentos")
        if (conCita > 0) append(" · Media de intentos por cita: ${"%.1f".format(intentosPorExito)}")
        if (porProvincia.isNotEmpty()) {
            append("\nPor provincia:")
            porProvincia.forEach { append("\n  • ${it.first}: ${it.third}/${it.second} con cita") }
        }
    }

    companion object {
        fun compute(reqs: List<Request>, catalog: Catalog): Stats {
            val ok = reqs.filter { it.status == Status.CON_CITA }
            val byProv = reqs.groupBy { catalog.province(it.profile.provinceCode)?.name ?: "?" }
                .map { (n, l) -> Triple(n, l.size, l.count { it.status == Status.CON_CITA }) }
                .sortedByDescending { it.second }
            return Stats(
                total = reqs.size,
                pendientes = reqs.count { it.status == Status.PENDIENTE },
                enCurso = reqs.count { it.status == Status.EN_PROCESO || it.status == Status.ESPERANDO_HUMANO },
                conCita = ok.size,
                fallidas = reqs.count { it.status == Status.FALLIDA },
                canceladas = reqs.count { it.status == Status.CANCELADA },
                intentos = reqs.sumOf { it.attempts },
                intentosPorExito = if (ok.isEmpty()) 0.0 else ok.sumOf { it.attempts }.toDouble() / ok.size,
                porProvincia = byProv,
            )
        }
    }
}
