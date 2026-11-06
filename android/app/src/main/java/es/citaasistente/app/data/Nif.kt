package es.citaasistente.app.data

/** Validación de documentos: evita enviar al sitio un NIE/DNI con la letra de control mal puesta. */
object Nif {
    private const val LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE"
    private val NIE = Regex("^[XYZ]\\d{7}[A-Z]$")
    private val DNI = Regex("^\\d{8}[A-Z]$")

    fun clean(s: String): String = s.uppercase().replace(Regex("[\\s.\\-]"), "")

    fun isValidNie(raw: String): Boolean {
        val s = clean(raw)
        if (!NIE.matches(s)) return false
        val num = (("XYZ".indexOf(s[0])).toString() + s.substring(1, 8)).toInt()
        return LETTERS[num % 23] == s[8]
    }

    fun isValidDni(raw: String): Boolean {
        val s = clean(raw)
        if (!DNI.matches(s)) return false
        return LETTERS[s.substring(0, 8).toInt() % 23] == s[8]
    }

    fun isValidPassport(raw: String): Boolean = Regex("^[A-Z0-9]{5,20}$").matches(clean(raw))

    /** Documento enmascarado para mensajes (no se envía completo a Telegram). */
    fun mask(raw: String): String { val s = clean(raw); return if (s.length <= 4) "****" else "***" + s.takeLast(3) }

    /** Deduce el tipo de documento a partir del formato (para importar desde el bot). */
    fun guessType(raw: String): String {
        val s = clean(raw)
        return when {
            NIE.matches(s) -> "NIE"
            DNI.matches(s) -> "DNI"
            else -> "PASAPORTE"
        }
    }
}
