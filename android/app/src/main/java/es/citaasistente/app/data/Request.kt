package es.citaasistente.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Status(val label: String) {
    PENDIENTE("Pendiente"),
    EN_PROCESO("En proceso"),
    ESPERANDO_HUMANO("Esperando captcha / SMS"),
    CON_CITA("Con cita"),
    FALLIDA("Fallida"),
    CANCELADA("Cancelada"),
}

data class HistoryItem(val t: Long, val kind: String, val text: String)

/** Una solicitud de cita en la cola. */
data class Request(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val profile: Profile,
    val status: Status = Status.PENDIENTE,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val slot: String = "",          // cita elegida (fecha y hora)
    val result: String = "",        // texto de confirmación de la sede
    val reference: String = "",     // código/justificante si se pudo extraer
    val failReason: String = "",
    val history: List<HistoryItem> = emptyList(),
    val sourceChat: String = "",    // chat de Telegram del cliente que envió la solicitud (vacío = la creó el master o la app)
    val archivedAt: Long = 0L,      // 0 = solicitud activa; >0 = en el historial desde esa fecha (se borra a los 30 días)
) {
    /** Solicitudes del mismo grupo comparten disponibilidad: si no hay citas para una, no las hay para las demás. */
    fun groupKey(): String = "${profile.provinceCode}|${profile.procedureId}"

    fun displayName(): String = profile.fullName.ifBlank { profile.docNumber }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("createdAt", createdAt).put("profile", profile.toJson())
        .put("status", status.name).put("attempts", attempts).put("updatedAt", updatedAt)
        .put("slot", slot).put("result", result).put("reference", reference).put("failReason", failReason)
        .put("archivedAt", archivedAt)
        .put("sourceChat", sourceChat)
        .put("history", JSONArray().also { arr ->
            history.forEach { arr.put(JSONObject().put("t", it.t).put("k", it.kind).put("m", it.text)) }
        })

    companion object {
        fun fromJson(o: JSONObject): Request {
            val h = o.optJSONArray("history")
            return Request(
                id = o.getString("id"),
                createdAt = o.optLong("createdAt"),
                profile = Profile.fromJson(o.getJSONObject("profile")),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PENDIENTE),
                attempts = o.optInt("attempts"),
                updatedAt = o.optLong("updatedAt"),
                slot = o.optString("slot"), result = o.optString("result"),
                reference = o.optString("reference"), failReason = o.optString("failReason"),
                archivedAt = o.optLong("archivedAt"),
                sourceChat = o.optString("sourceChat"),
                history = (0 until (h?.length() ?: 0)).map {
                    val x = h!!.getJSONObject(it)
                    HistoryItem(x.optLong("t"), x.optString("k"), x.optString("m"))
                },
            )
        }
    }
}
