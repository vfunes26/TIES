package es.citaasistente.app.data

/** Cuentas del informe diario (sin Android, para poder probarlas). */
object ActivityStats {

    data class Daily(
        val added: Int,
        val ok: Int,
        val cancelled: Int,
        val expired: Int,
        val failed: Int,
        val active: Int,
        val pending: Int,
        val processing: Int,
        val waitingHuman: Int,
        val failedActive: Int,
        val lastActivity: Long
    )

    /** `since` = desde cuándo se cuenta (normalmente, hace 24 horas). */
    fun compute(all: List<Request>, since: Long): Daily {
        val active = all.filter { it.archivedAt == 0L }

        return Daily(
            added = all.count { it.createdAt >= since },
            ok = all.count { it.status == Status.CON_CITA && it.updatedAt >= since },
            cancelled = all.count {
                it.status == Status.CANCELADA && !Retention.isExpired(it) && it.updatedAt >= since
            },
            expired = all.count { Retention.isExpired(it) && it.updatedAt >= since },
            failed = all.count { it.status == Status.FALLIDA && it.updatedAt >= since },
            active = active.size,
            pending = active.count { it.status == Status.PENDIENTE },
            processing = active.count { it.status == Status.EN_PROCESO },
            waitingHuman = active.count { it.status == Status.ESPERANDO_HUMANO },
            failedActive = active.count { it.status == Status.FALLIDA },
            lastActivity = all.maxOfOrNull { it.updatedAt } ?: 0L
        )
    }
}
