package es.citaasistente.app.data

/**
 * Interpreta los mensajes que muestra la sede (las 4 figuras de la guía de la comunidad):
 *  - FIG. 1  «No hay citas para la reserva sin Cl@ve» (sí con Cl@ve) → se gastó un intento.
 *  - FIG. 2  Aviso informativo «No hay citas disponibles… rellene todos los campos…» → NO gasta intento (recargar es gratis).
 *  - FIG. 3  «No hay citas disponibles. En breve, la Oficina…» tras pulsar «Solicitar cita» → se gastó un intento.
 *  - FIG. 4  Lo mismo con «Cod. Oper.» → NIE BLOQUEADO hasta las 00:00 (hora de España).
 * Nota: es información de la comunidad de usuarios, no documentación oficial.
 */
object PortalNotice {

    enum class Kind { NONE, FIG1_SIN_CLAVE, FIG2_AVISO, FIG3_SIN_CITAS, FIG4_BLOQUEO }

    data class Notice(val kind: Kind, val code: String = "", val stamp: String = "")

    private val COD_OPER = Regex("cod\\.?\\s*oper")
    private val CODE = Regex("(?i)cod\\.?\\s*oper\\.?\\s*:?\\s*([A-Za-z0-9]{8,})")
    private val STAMP = Regex("(\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}:\\d{2})")

    fun classify(text: String): Notice {
        val n = normalize(text)

        val noSlots = n.contains("no hay citas disponibles")
        val clave = n.contains("sin cl@ve") || n.contains("uso de cl@ve")

        if (!noSlots && !clave) return Notice(Kind.NONE)

        if (noSlots && COD_OPER.containsMatchIn(n)) {
            return Notice(
                Kind.FIG4_BLOQUEO,
                CODE.find(text)?.groupValues?.get(1).orEmpty(),
                STAMP.find(text)?.groupValues?.get(1).orEmpty()
            )
        }

        if (clave) return Notice(Kind.FIG1_SIN_CLAVE)

        if (n.contains("consultar o anular una cita") || n.contains("rellene todos los campos obligatorios")) {
            return Notice(Kind.FIG2_AVISO)
        }

        return Notice(Kind.FIG3_SIN_CITAS)
    }
}
