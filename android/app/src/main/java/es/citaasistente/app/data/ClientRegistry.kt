package es.citaasistente.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clientes autorizados a enviar solicitudes por el bot de Telegram (cada uno por su chat privado).
 * Solo el master los autoriza (/autorizar). Se guarda como JSON: [{"chatId":"123","name":"Ana","addedAt":1}]
 */
object ClientRegistry {

    data class Client(val chatId: String, val name: String, val addedAt: Long)

    fun isChatId(s: String): Boolean = Regex("^\\d{3,20}$").matches(s.trim())

    fun parse(raw: String): List<Client> {
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }

        val out = ArrayList<Client>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("chatId")
            if (id.isNotBlank()) out.add(Client(id, o.optString("name"), o.optLong("addedAt")))
        }
        return out
    }

    fun toRaw(list: List<Client>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("chatId", it.chatId).put("name", it.name).put("addedAt", it.addedAt))
        }
        return arr.toString()
    }

    fun add(raw: String, client: Client): String =
        toRaw(parse(raw).filter { it.chatId != client.chatId } + client)

    fun remove(raw: String, chatId: String): String =
        toRaw(parse(raw).filter { it.chatId != chatId })

    fun contains(raw: String, chatId: String): Boolean =
        parse(raw).any { it.chatId == chatId }
}
