package es.citaasistente.app.data

/**
 * Reglas de conservación (30 días):
 *  - Solicitudes ACTIVAS (pendientes o fallidas): viven 30 días desde que se registraron.
 *    Si no se procesan ni se cancelan, pasan solas al historial como «Vencida».
 *  - HISTORIAL (con cita, canceladas, vencidas): se conserva 30 días desde que entró y luego se borra.
 * Los contadores mensuales (Ledger) no se tocan nunca.
 */
object Retention {

    const val DAYS = 30
    const val EXPIRED_PREFIX = "Vencida"

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class Result(
        val list: List<Request>,
        val expired: Int,
        val purged: Int,
        val migrated: Int,
        val expiredList: List<Request> = emptyList()
    ) {
        val changed: Boolean get() = expired + purged + migrated > 0
    }

    fun isClosed(r: Request): Boolean =
        r.status == Status.CON_CITA || r.status == Status.CANCELADA

    fun apply(list: List<Request>, now: Long): Result {
        var expired = 0
        var purged = 0
        var migrated = 0
        val expiredNow = ArrayList<Request>()
        val out = ArrayList<Request>()

        for (r in list) {
            var x = r

            // Solicitudes cerradas de versiones anteriores: entran al historial con fecha de hoy.
            if (isClosed(x) && x.archivedAt == 0L) {
                x = x.copy(archivedAt = now)
                migrated++
            }

            // Activas que cumplen 30 días: pasan al historial como vencidas.
            if (x.archivedAt == 0L &&
                (x.status == Status.PENDIENTE || x.status == Status.FALLIDA) &&
                now - x.createdAt >= DAYS * DAY_MS
            ) {
                x = x.copy(
                    status = Status.CANCELADA,
                    failReason = "$EXPIRED_PREFIX: $DAYS días sin procesarse",
                    archivedAt = now,
                    updatedAt = now
                )
                expired++
                expiredNow.add(x)
            }

            // Historial que cumple 30 días: se borra.
            if (x.archivedAt > 0L && now - x.archivedAt >= DAYS * DAY_MS) {
                purged++
                continue
            }

            out.add(x)
        }

        return Result(out, expired, purged, migrated, expiredNow)
    }

    /** Días que le quedan a un registro del historial antes de borrarse. */
    fun daysLeft(r: Request, now: Long): Int {
        if (r.archivedAt <= 0L) return DAYS
        val elapsedDays = ((now - r.archivedAt) / DAY_MS).toInt()
        return (DAYS - elapsedDays).coerceAtLeast(0)
    }

    fun isExpired(r: Request): Boolean =
        r.status == Status.CANCELADA && r.failReason.startsWith(EXPIRED_PREFIX)
}
