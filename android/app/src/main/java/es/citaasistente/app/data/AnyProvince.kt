package es.citaasistente.app.data

/** Solicitudes SIN provincia: se prueba cualquier provincia que ofrezca el trámite, rotando en cada intento. */
object AnyProvince {

    fun provincesWith(catalog: Catalog, procedureId: String): List<Province> =
        catalog.provinces.filter { it.procedure(procedureId) != null }

    /** Código de la provincia para el intento número `attempts` (0, 1, 2…). Vacío si ninguna ofrece el trámite. */
    fun pick(catalog: Catalog, procedureId: String, attempts: Int): String {
        val list = provincesWith(catalog, procedureId)
        if (list.isEmpty()) return ""
        return list[attempts.coerceAtLeast(0) % list.size].code
    }
}
