package es.citaasistente.app.data

import android.content.Context

/** Ajustes NO sensibles del respaldo (dónde se guarda y cuándo fue la última vez). La frase vive cifrada en Store. */
class BackupPrefs private constructor(ctx: Context) {

    private val p = ctx.getSharedPreferences("citaasistente_backup", Context.MODE_PRIVATE)

    /** Archivo elegido en Drive (o donde sea) con el selector de archivos de Android. */
    var uri: String
        get() = p.getString("uri", "") ?: ""
        set(v) { p.edit().putString("uri", v).apply() }

    var telegram: Boolean
        get() = p.getBoolean("telegram", true)
        set(v) { p.edit().putBoolean("telegram", v).apply() }

    var lastOkAt: Long
        get() = p.getLong("lastOkAt", 0L)
        set(v) { p.edit().putLong("lastOkAt", v).apply() }

    var lastTgAt: Long
        get() = p.getLong("lastTgAt", 0L)
        set(v) { p.edit().putLong("lastTgAt", v).apply() }

    var lastTgCitas: Int
        get() = p.getInt("lastTgCitas", 0)
        set(v) { p.edit().putInt("lastTgCitas", v).apply() }

    var lastHash: String
        get() = p.getString("lastHash", "") ?: ""
        set(v) { p.edit().putString("lastHash", v).apply() }

    var lastError: String
        get() = p.getString("lastError", "") ?: ""
        set(v) { p.edit().putString("lastError", v).apply() }

    companion object {
        @Volatile
        private var instance: BackupPrefs? = null

        fun get(ctx: Context): BackupPrefs =
            instance ?: synchronized(this) {
                instance ?: BackupPrefs(ctx.applicationContext).also { instance = it }
            }
    }
}
