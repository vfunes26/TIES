package es.citaasistente.app.alerts

import es.citaasistente.app.data.normalize

enum class Kind { AVAILABLE, NONE, IGNORE }

/** Resultado de interpretar una notificación. `province`/`procedure`: true=coincide, false=es de otra, null=no se sabe. */
data class Interpretation(
    val kind: Kind,
    val province: Boolean?,
    val procedure: Boolean?,
    val summary: String,
)

/**
 * Interpreta las notificaciones que llegan de:
 *  - la app "Extranjería Alerts" (es.extranjeriaalerts.app): "Cita disponible", etc.
 *  - el bot de Telegram (mensajes con etiqueta CITA_DISPONIBLE | provincia | trámite).
 * Es tolerante a cambios de redacción: se calibra con el botón "Probar interpretación" de la app.
 */
object AlertParser {
    const val ALERTS_PKG = "es.extranjeriaalerts.app"
    val TELEGRAM_PKGS = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")

    private val NONE_PATTERNS = listOf("no hay citas", "sin citas", "sin_citas", "no hay cita", "no disponible", "agotad", "appointment is not available", "ya no hay")
    private val AVAILABLE_PATTERNS = listOf("cita disponible", "citas disponibles", "hay cita", "hay citas", "nueva cita", "nuevas citas", "cita_disponible", "huecos disponibles", "appointment available", "slot")
    private val STOPWORDS = setOf("para", "desde", "sobre", "entre", "oficina", "tarjeta", "inicial", "duplicado", "renovacion", "autorizacion", "aut.")

    fun interpret(
        packageName: String,
        title: String?,
        text: String?,
        provinceName: String?,
        procedureId: String?,
        procedureName: String?,
        allProvinceNames: List<String>,
        otherProcedureNames: List<String> = emptyList(),
    ): Interpretation {
        val raw = listOfNotNull(title, text).joinToString(" · ").trim()
        val n = normalize(raw)
        val summary = raw.take(160)
        if (n.isEmpty()) return Interpretation(Kind.IGNORE, null, null, summary)

        val fromTelegram = packageName in TELEGRAM_PKGS
        if (fromTelegram && !n.contains("cita_disponible") && !n.contains("sin_citas")) {
            // En Telegram solo se hace caso al mensaje estructurado del bot; el resto de chats se ignora.
            return Interpretation(Kind.IGNORE, null, null, summary)
        }

        val kind = when {
            NONE_PATTERNS.any { n.contains(it) } -> Kind.NONE
            AVAILABLE_PATTERNS.any { n.contains(it) } -> Kind.AVAILABLE
            else -> Kind.IGNORE
        }
        if (kind != Kind.AVAILABLE) return Interpretation(kind, null, null, summary)

        return Interpretation(
            kind = kind,
            province = matchProvince(n, provinceName, allProvinceNames),
            procedure = matchProcedure(n, procedureId, procedureName, otherProcedureNames),
            summary = summary,
        )
    }

    /** ¿Se inicia el autorrelleno? En modo estricto exige coincidencia explícita de provincia y trámite. */
    fun shouldStart(i: Interpretation, strict: Boolean): Boolean {
        if (i.kind != Kind.AVAILABLE) return false
        return if (strict) i.province == true && i.procedure == true
        else i.province != false && i.procedure != false
    }

    internal fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        return Regex("(^|[\\s,.:;|\\-/(])" + Regex.escape(needle) + "($|[\\s,.:;|\\-/)])").containsMatchIn(haystack)
    }

    internal fun matchProvince(n: String, provinceName: String?, all: List<String>): Boolean? {
        if (provinceName.isNullOrBlank()) return null
        val mine = normalize(provinceName)
        if (containsWord(n, mine)) return true
        val others = all.map { normalize(it) }.filter { it != mine && containsWord(n, it) }
        return if (others.isNotEmpty()) false else null
    }

    private fun tokens(name: String): List<String> =
        normalize(name).split(' ', ',', '(', ')', '/', '-').filter { it.length >= 5 && it !in STOPWORDS }.distinct()

    private fun ratio(n: String, name: String): Double {
        val t = tokens(name)
        if (t.isEmpty()) return 0.0
        return t.count { n.contains(it) }.toDouble() / t.size
    }

    internal fun matchProcedure(n: String, id: String?, name: String?, others: List<String>): Boolean? {
        if (id.isNullOrBlank() && name.isNullOrBlank()) return null
        if (!id.isNullOrBlank() && containsWord(n, id)) return true
        val mine = if (name.isNullOrBlank()) 0.0 else ratio(n, name)
        if (mine >= 0.5) return true
        val other = others.maxOfOrNull { ratio(n, it) } ?: 0.0
        return if (other >= 0.6 && other > mine) false else null
    }
}
