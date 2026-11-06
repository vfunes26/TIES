// android/app/src/main/java/es/citaasistente/app/com/vfunes26/ties/model/Request.kt
package com.vfunes26.ties.model

import java.util.UUID

data class Request(
    val id: String = UUID.randomUUID().toString(),
    val nie: String,
    val nombre: String,
    val telefono: String,
    val email: String,
    val paisNac: String,
    val provincia: String,
    val tramite: String,
    val sede: String,
    val minCitaDate: String,
    var estado: RequestState = RequestState.PENDIENTE,
    var intentosWorker: Int = 0,
    var ultimoWorkerId: Int? = null,
    var descartadaPor: String? = null
)

enum class RequestState {
    PENDIENTE,
    EN_PROCESO,
    ESPERANDO_CAPTCHA,
    CON_CITA,
    FALLIDA,
    CANCELADA,
    DESCARTADA
}