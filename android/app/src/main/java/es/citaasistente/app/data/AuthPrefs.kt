package es.citaasistente.app.data

import android.content.Context

/**
 * Credenciales y sesión de ESTE teléfono. No hay registro ni servidor: la primera vez que alguien entra
 * con «master» o «uiux» crea su contraseña, que se guarda aquí (solo su huella, nunca en claro).
 * Nada de esto va dentro del respaldo.
 */
class AuthPrefs private constructor(ctx: Context) {

    private val p = ctx.getSharedPreferences("citaasistente_auth", Context.MODE_PRIVATE)

    private fun key(role: Role, what: String) = "${role.name.lowercase()}_$what"

    fun hasPassword(role: Role): Boolean =
        !p.getString(key(role, "hash"), "").isNullOrEmpty()

    fun setPassword(role: Role, pass: String) {
        p.edit()
            .putString(key(role, "hash"), PasswordHasher.create(pass))
            .putInt(key(role, "fails"), 0)
            .putLong(key(role, "lock"), 0L)
            .apply()
    }

    /** Segundos que faltan para poder volver a intentar (0 = se puede intentar). */
    fun lockedSeconds(role: Role, now: Long = System.currentTimeMillis()): Long {
        val left = p.getLong(key(role, "lock"), 0L) - now
        return if (left <= 0L) 0L else (left + 999L) / 1000L
    }

    /** Comprueba la contraseña. 5 errores seguidos bloquean 60 segundos. */
    fun check(role: Role, pass: String, now: Long = System.currentTimeMillis()): Boolean {
        if (lockedSeconds(role, now) > 0L) return false

        val stored = p.getString(key(role, "hash"), "").orEmpty()

        if (PasswordHasher.verify(pass, stored)) {
            p.edit().putInt(key(role, "fails"), 0).apply()
            return true
        }

        val fails = p.getInt(key(role, "fails"), 0) + 1
        if (fails >= MAX_FAILS) {
            p.edit()
                .putInt(key(role, "fails"), 0)
                .putLong(key(role, "lock"), now + LOCK_MS)
                .apply()
        } else {
            p.edit().putInt(key(role, "fails"), fails).apply()
        }
        return false
    }

    /** Perfil con sesión abierta («MASTER», «USER» o vacío). */
    var sessionRole: String
        get() = p.getString("session_role", "") ?: ""
        set(v) { p.edit().putString("session_role", v).apply() }

    companion object {
        private const val MAX_FAILS = 5
        private const val LOCK_MS = 60_000L

        @Volatile
        private var instance: AuthPrefs? = null

        fun get(ctx: Context): AuthPrefs =
            instance ?: synchronized(this) {
                instance ?: AuthPrefs(ctx.applicationContext).also { instance = it }
            }
    }
}
