package es.citaasistente.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Qué lleva el respaldo: solicitudes (activas e historial), contadores por mes, ajustes
 * (Telegram, correo, etc.) y proxies. Al restaurar se COMBINA con lo que ya haya: gana lo más reciente.
 */
object BackupData {

    const val MIN_PASS = 6
    const val VERSION = 1

    data class Summary(
        val requests: Int,
        val added: Int,
        val updated: Int,
        val months: Int,
        val hasSettings: Boolean,
        val proxies: Int,
        val createdAt: Long
    )

    /** Contenido sin fecha de creación: sirve para detectar si algo cambió desde el último respaldo. */
    fun body(store: Store): JSONObject {
        val reqs = JSONArray()
        store.requests().forEach { reqs.put(it.toJson()) }

        val prox = JSONArray()
        store.proxies().forEach { prox.put(it.toJson()) }

        val ledger = try {
            JSONObject(store.ledgerRaw())
        } catch (e: Exception) {
            JSONObject()
        }

        return JSONObject()
            .put("v", VERSION)
            .put("requests", reqs)
            .put("ledger", ledger)
            .put("settings", store.settings.toJson())
            .put("proxies", prox)
            .put("clients", try { JSONArray(store.clientsRaw()) } catch (e: Exception) { JSONArray() })
    }

    fun wrap(body: JSONObject, now: Long): JSONObject =
        JSONObject(body.toString()).put("createdAt", now)

    fun hash(body: JSONObject): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun parse(bytes: ByteArray): JSONObject {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        require(o.has("requests")) { "El archivo no contiene solicitudes" }
        return o
    }

    private fun readRequests(json: JSONObject): List<Request> {
        val arr = json.optJSONArray("requests") ?: return emptyList()
        val out = ArrayList<Request>()
        for (i in 0 until arr.length()) {
            try {
                out.add(Request.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // registro dañado: se omite
            }
        }
        return out
    }

    private fun readProxies(json: JSONObject): List<ProxyEntry> {
        val arr = json.optJSONArray("proxies") ?: return emptyList()
        val out = ArrayList<ProxyEntry>()
        for (i in 0 until arr.length()) {
            try {
                out.add(ProxyEntry.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // proxy dañado: se omite
            }
        }
        return out.take(ProxyRules.MAX_PROXIES)
    }

    /** Resumen para mostrar antes de restaurar (no modifica nada). */
    fun peek(json: JSONObject, current: List<Request>): Summary {
        val incoming = readRequests(json)
        val byId = current.associateBy { it.id }
        var added = 0
        var updated = 0
        for (r in incoming) {
            val c = byId[r.id]
            if (c == null) added++ else if (r.updatedAt > c.updatedAt) updated++
        }
        return Summary(
            requests = incoming.size,
            added = added,
            updated = updated,
            months = json.optJSONObject("ledger")?.length() ?: 0,
            hasSettings = json.optJSONObject("settings") != null,
            proxies = readProxies(json).size,
            createdAt = json.optLong("createdAt")
        )
    }

    fun restore(store: Store, json: JSONObject): Summary {
        val summary = peek(json, store.requests())

        val merged = LinkedHashMap<String, Request>()
        store.requests().forEach { merged[it.id] = it }
        for (r in readRequests(json)) {
            val c = merged[r.id]
            if (c == null || r.updatedAt > c.updatedAt) merged[r.id] = r
        }
        store.replaceRequests(merged.values.sortedBy { it.createdAt })

        json.optJSONObject("ledger")?.let { store.mergeLedger(it.toString()) }

        json.optJSONObject("settings")?.let { store.settings = Settings.fromJson(it) }

        val proxies = readProxies(json)
        if (proxies.isNotEmpty()) store.saveProxies(proxies)

        json.optJSONArray("clients")?.let { if (it.length() > 0) store.setClientsRaw(it.toString()) }

        return summary
    }
}
