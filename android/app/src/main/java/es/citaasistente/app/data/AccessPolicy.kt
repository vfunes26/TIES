package es.citaasistente.app.data

/** Reglas (sin Android) del acceso del Usuario UI/UX con correo de Google + clave de aplicación. */
object AccessPolicy {

    /**
     * Si NO está vacío, solo este correo puede entrar como Usuario UI/UX.
     * Ejemplo: "ties.usuario@gmail.com". Vacío = vale cualquier correo cuya clave Google acepte.
     * (Recomendado: ponerlo con tu Gmail dedicado antes de entregar el APK.)
     */
    const val ALLOWED_USER_EMAIL = ""

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** Google muestra la clave como «abcd efgh ijkl mnop»: se quitan los espacios. */
    fun normalizeKey(raw: String): String = raw.filter { !it.isWhitespace() }

    fun isValidEmail(raw: String): Boolean = EMAIL.matches(raw.trim())

    fun isValidKey(raw: String): Boolean {
        val k = normalizeKey(raw)
        return k.length == 16 && k.all { it.isLetterOrDigit() }
    }

    fun allows(email: String, allowed: String = ALLOWED_USER_EMAIL): Boolean =
        allowed.isBlank() || email.trim().equals(allowed.trim(), ignoreCase = true)
}
