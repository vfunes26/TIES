package es.citaasistente.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Correo de Google y clave de aplicación del Usuario UI/UX en ESTE teléfono (cifrados).
 * Sirven para volver a preguntarle a Google cada 30 min si la clave sigue existiendo.
 * No van dentro del respaldo.
 */
class AccessPrefs private constructor(ctx: Context) {

    private val p: SharedPreferences = run {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            ctx,
            "citaasistente_access",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var email: String
        get() = p.getString("email", "") ?: ""
        set(v) { p.edit().putString("email", v).apply() }

    /** Clave de aplicación sin espacios. Se borra al cerrar sesión o si Google la rechaza. */
    var key: String
        get() = p.getString("key", "") ?: ""
        set(v) { p.edit().putString("key", v).apply() }

    var lastCheckAt: Long
        get() = p.getLong("lastCheckAt", 0L)
        set(v) { p.edit().putLong("lastCheckAt", v).apply() }

    var lastOkAt: Long
        get() = p.getLong("lastOkAt", 0L)
        set(v) { p.edit().putLong("lastOkAt", v).apply() }

    fun save(email: String, key: String, now: Long) {
        p.edit()
            .putString("email", email.trim())
            .putString("key", AccessPolicy.normalizeKey(key))
            .putLong("lastCheckAt", now)
            .putLong("lastOkAt", now)
            .apply()
    }

    companion object {
        @Volatile
        private var instance: AccessPrefs? = null

        fun get(ctx: Context): AccessPrefs =
            instance ?: synchronized(this) {
                instance ?: AccessPrefs(ctx.applicationContext).also { instance = it }
            }
    }
}
