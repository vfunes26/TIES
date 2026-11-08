package es.citaasistente.app.data

/** Identificador corto de solicitud (el que se le devuelve al cliente por Telegram). */
object Ids {
    fun short(id: String): String = id.replace("-", "").take(6).uppercase()
}
