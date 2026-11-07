package es.citaasistente.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Almacén local. Solicitudes y ajustes se guardan cifrados
 * con Android Keystore.
 */
class Store private constructor(context: Context) {

    private val secure: SharedPreferences = run {
        val encrypted = try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "citaasistente_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "No se pudo inicializar el almacenamiento cifrado. " +
                    "No se usarán datos sin cifrar.",
                e
            )
        }

        val legacy = context.getSharedPreferences(
            "citaasistente_fallback",
            Context.MODE_PRIVATE
        )

        if (legacy.all.isNotEmpty()) {
            val editor = encrypted.edit()

            legacy.all.forEach { (name, value) ->
                if (!encrypted.contains(name)) {
                    when (value) {
                        is String -> editor.putString(name, value)
                        is Boolean -> editor.putBoolean(name, value)
                        is Int -> editor.putInt(name, value)
                        is Long -> editor.putLong(name, value)
                        is Float -> editor.putFloat(name, value)
                        is Set<*> -> editor.putStringSet(
                            name,
                            value.filterIsInstance<String>().toSet()
                        )
                    }
                }
            }

            check(editor.commit()) {
                "No se pudieron migrar los datos al almacenamiento cifrado."
            }

            check(legacy.edit().clear().commit()) {
                "No se pudo limpiar el almacenamiento antiguo."
            }
        }

        encrypted
    }

    // Migración del gestor antiguo de proxies a almacenamiento cifrado.
    init {
        val legacyProxy = context.getSharedPreferences(
            "cita_prefs",
            Context.MODE_PRIVATE
        )

        if (
            legacyProxy.contains("proxy_enabled") ||
            legacyProxy.contains("proxy_list_json")
        ) {
            val raw = legacyProxy.getString("proxy_list_json", null)

            val oldArray = if (raw == null) {
                JSONArray()
            } else {
                runCatching { JSONArray(raw) }.getOrNull()
            }

            if (oldArray != null) {
                val migrated = JSONArray()
                var valid = true

                for (i in 0 until oldArray.length()) {
                    val item = oldArray.optJSONObject(i)

                    if (item == null) {
                        valid = false
                        break
                    }

                    val parsed = ProxyParser.parseLine(
                        item.optString("server")
                    )

                    if (parsed == null) {
                        valid = false
                        break
                    }

                    migrated.put(
                        parsed.copy(
                            user = item.optString("username"),
                            pass = item.optString("password")
                        ).toJson()
                    )
                }

                if (valid) {
                    val editor = secure.edit()

                    if (!secure.contains("proxies") && raw != null) {
                        editor.putString("proxies", migrated.toString())
                    }

                    if (legacyProxy.getBoolean("proxy_enabled", false)) {
                        val current = secure.getString("settings2", null)
                            ?.let {
                                runCatching {
                                    Settings.fromJson(JSONObject(it))
                                }.getOrNull()
                            } ?: Settings()

                        editor.putString(
                            "settings2",
                            current.copy(proxyEnabled = true)
                                .toJson()
                                .toString()
                        )
                    }

                    check(editor.commit()) {
                        "No se pudo migrar la configuración de proxies."
                    }

                    check(
                        legacyProxy.edit()
                            .remove("proxy_enabled")
                            .remove("proxy_list_json")
                            .commit()
                    ) {
                        "No se pudo limpiar la configuración antigua de proxies."
                    }
                }
            }
        }
    }

    // Estado operativo no sensible.
    private val plain: SharedPreferences = context.getSharedPreferences(
        "citaasistente_state",
        Context.MODE_PRIVATE
    )

    private val lock = Any()

    init {
        migrateEventsToSecure()
    }

    /**
     * Migra eventos históricos al almacenamiento cifrado.
     * Solo elimina el original después de confirmar la escritura.
     */
    private fun migrateEventsToSecure() {
        val legacyRaw = plain.getString("events", null) ?: return

        val legacyEvents = runCatching {
            JSONArray(legacyRaw)
        }.getOrElse {
            throw IllegalStateException(
                "El registro antiguo de eventos no es válido. " +
                    "No se eliminarán los datos originales.",
                it
            )
        }

        val secureRaw = secure.getString("events", null)

        val secureEvents = if (secureRaw.isNullOrBlank()) {
            JSONArray()
        } else {
            runCatching {
                JSONArray(secureRaw)
            }.getOrElse {
                throw IllegalStateException(
                    "El registro cifrado de eventos no es válido. " +
                        "No se eliminarán los datos originales.",
                    it
                )
            }
        }

        val merged = JSONArray()
        val seen = mutableSetOf<String>()

        fun appendUnique(source: JSONArray) {
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                val key = item.toString()

                if (seen.add(key) && merged.length() < 100) {
                    merged.put(item)
                }
            }
        }

        // Priorizar los eventos cifrados existentes.
        appendUnique(secureEvents)

        // Incorporar los históricos sin duplicarlos.
        appendUnique(legacyEvents)

        check(
            secure.edit()
                .putString("events", merged.toString())
                .commit()
        ) {
            "No se pudo migrar el registro de eventos al almacenamiento cifrado."
        }

        check(
            plain.edit()
                .remove("events")
                .commit()
        ) {
            "No se pudo limpiar el registro antiguo de eventos."
        }
    }

    // ---------- Ajustes ----------

    var settings: Settings
        get() = secure.getString("settings2", null)
            ?.let {
                runCatching {
                    Settings.fromJson(JSONObject(it))
                }.getOrNull()
            } ?: Settings()

        set(v) {
            secure.edit()
                .putString("settings2", v.toJson().toString())
                .apply()
        }

    // ---------- Credenciales y estado de funciones ----------

    var authUser: String
        get() = secure.getString("authUser", "") ?: ""
        set(v) {
            secure.edit().putString("authUser", v).apply()
        }

    var authHash: String
        get() = secure.getString("authHash", "") ?: ""
        set(v) {
            secure.edit().putString("authHash", v).apply()
        }

    var intakeOffset: Long
        get() = secure.getLong("intakeOffset", 0L)
        set(v) {
            secure.edit().putLong("intakeOffset", v).apply()
        }

    var proxyCursor: Int
        get() = secure.getInt("proxyCursor", 0)
        set(v) {
            secure.edit().putInt("proxyCursor", v.coerceAtLeast(0)).apply()
        }

    // ---------- Gestión de proxies ----------

    fun proxies(): List<ProxyEntry> = synchronized(lock) {
        val raw = secure.getString("proxies", null)
            ?: return@synchronized emptyList()

        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return@synchronized emptyList()

        val result = ArrayList<ProxyEntry>()

        for (i in 0 until arr.length()) {
            runCatching {
                result.add(ProxyEntry.fromJson(arr.getJSONObject(i)))
            }
        }

        result
    }

    fun saveProxies(list: List<ProxyEntry>) = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }

        secure.edit()
            .putString("proxies", arr.toString())
            .apply()
    }

    fun updateProxy(
        id: String,
        change: (ProxyEntry) -> ProxyEntry
    ): ProxyEntry? = synchronized(lock) {
        val list = proxies().toMutableList()
        val index = list.indexOfFirst { it.id == id }

        if (index < 0) return@synchronized null

        val updated = change(list[index])
        list[index] = updated

        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }

        secure.edit()
            .putString("proxies", arr.toString())
            .apply()

        updated
    }

    // ---------- Selección secuencial de proxies ----------


fun nextUsableProxy(): ProxyEntry? = synchronized(lock) {
    val usable = proxies().filter {
        it.enabled &&
            it.type.lowercase() in setOf("http", "https") &&
            it.user.isBlank() &&
            it.pass.isBlank()
    }

    if (usable.isEmpty()) return@synchronized null

    val index = proxyCursor % usable.size
    proxyCursor = (index + 1) % usable.size

    usable[index]
}


    // ---------- Cola de solicitudes ----------

    fun requests(): List<Request> = synchronized(lock) {
        readRequests()
    }

    private fun readRequests(): List<Request> {
        val raw = secure.getString("requests", null)
            ?: return emptyList()

        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return emptyList()

        val out = ArrayList<Request>(arr.length())

        for (i in 0 until arr.length()) {
            runCatching {
                out.add(Request.fromJson(arr.getJSONObject(i)))
            }
        }

        return out
    }

    private fun writeRequests(list: List<Request>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }

        secure.edit()
            .putString("requests", arr.toString())
            .apply()
    }

    fun request(id: String): Request? =
        requests().firstOrNull { it.id == id }

    fun addRequest(r: Request) = synchronized(lock) {
        writeRequests(readRequests() + r)
    }

    /** Modifica una solicitud de forma atómica y devuelve la versión actualizada. */
    fun updateRequest(
        id: String,
        change: (Request) -> Request
    ): Request? = synchronized(lock) {
        val list = readRequests().toMutableList()
        val i = list.indexOfFirst { it.id == id }

        if (i < 0) return null

        val updated = change(list[i])
            .copy(updatedAt = System.currentTimeMillis())

        list[i] = updated
        writeRequests(list)

        updated
    }

    fun removeRequest(id: String) = synchronized(lock) {
        writeRequests(readRequests().filter { it.id != id })
    }

    fun addHistory(
        id: String,
        kind: String,
        text: String
    ): Request? = updateRequest(id) {
        val h = (
            listOf(
                HistoryItem(
                    System.currentTimeMillis(),
                    kind,
                    text.take(600)
                )
            ) + it.history
        ).take(80)

        it.copy(history = h)
    }

    // ---------- Estado de alertas / salud ----------

    var lastAlertAt: Long
        get() = plain.getLong("lastAlertAt", 0L)
        set(v) {
            plain.edit().putLong("lastAlertAt", v).apply()
        }

    var lastHealthIssues: String
        get() = plain.getString("lastHealthIssues", "") ?: ""
        set(v) {
            plain.edit().putString("lastHealthIssues", v).apply()
        }

    var listenerConnectedAt: Long
        get() = plain.getLong("listenerConnectedAt", 0L)
        set(v) {
            plain.edit().putLong("listenerConnectedAt", v).apply()
        }

    // ---------- Registro de eventos cifrado ----------

    fun appendEvent(kind: String, text: String) = synchronized(lock) {
        val arr = runCatching {
            JSONArray(secure.getString("events", "[]"))
        }.getOrDefault(JSONArray())

        val next = JSONArray()

        next.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("k", kind)
                .put("m", text.take(400))
        )

        for (i in 0 until minOf(arr.length(), 99)) {
            next.put(arr.get(i))
        }

        secure.edit()
            .putString("events", next.toString())
            .apply()
    }

    fun events(): List<Triple<Long, String, String>> = synchronized(lock) {
        val arr = runCatching {
            JSONArray(secure.getString("events", "[]"))
        }.getOrDefault(JSONArray())

        (0 until arr.length()).mapNotNull { index ->
            runCatching {
                val item = arr.getJSONObject(index)

                Triple(
                    item.optLong("t"),
                    item.optString("k"),
                    item.optString("m")
                )
            }.getOrNull()
        }
    }

    fun clearEvents() {
        synchronized(lock) {
            secure.edit().remove("events").apply()
            plain.edit().remove("events").apply()
        }
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext)
                    .also { instance = it }
            }
    }
}