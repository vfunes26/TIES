package es.citaasistente.app.data

enum class Role { MASTER, USER }

/**
 * Los dos perfiles de la app. El NOMBRE DE USUARIO decide el perfil:
 *  - «master»  → acceso total (configuración, proxy, Telegram, correo, respaldo...).
 *  - «uiux»    → Usuario UI/UX: crea y activa solicitudes, ve historial y estadísticas,
 *                personaliza sus alertas; NO toca la configuración.
 * Para cambiar los nombres basta editar las dos constantes de aquí.
 */
object Roles {

    const val MASTER_NAME = "funes"
    const val USER_NAME = "usuario"
    const val MIN_PASS = 6

    /** Minúsculas y solo letras/números: «UI/UX», «ui ux» y «uiux» son el mismo usuario. */
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun roleOf(name: String): Role? = when (normalize(name)) {
        MASTER_NAME -> Role.MASTER
        USER_NAME -> Role.USER
        else -> null
    }

    fun label(role: Role): String = if (role == Role.MASTER) "Master" else "Usuario UI/UX"
}
