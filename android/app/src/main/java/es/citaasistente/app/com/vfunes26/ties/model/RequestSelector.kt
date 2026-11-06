// android/app/src/main/java/com/vfunes26/ties/model/RequestSelector.kt
package com.vfunes26.ties.model

import kotlin.random.Random

enum class SelectionMode {
    ALL_PENDING,
    MANUAL_SELECTION,
    FIRST_FIVE,
    RANDOM_FIVE
}

class RequestSelector {

    /**
     * Filtra la lista completa de solicitudes según el modo seleccionado.
     * @param allRequests Lista completa de solicitudes en la app.
     * @param selectedIds Lista de IDs marcados con checkbox por el usuario (solo para MANUAL_SELECTION).
     * @param maxWorkers Límite máximo de workers simultáneos (ej. 5).
     */
    fun getRequestsToProcess(
        allRequests: List<Request>,
        mode: SelectionMode,
        selectedIds: List<String> = emptyList(),
        maxWorkers: Int = 5
    ): List<Request> {
        val pendingRequests = allRequests.filter { it.estado == RequestState.PENDIENTE }

        return when (mode) {
            SelectionMode.ALL_PENDING -> {
                pendingRequests.take(maxWorkers) // El orquestador las irá tomando de a maxWorkers
            }
            SelectionMode.MANUAL_SELECTION -> {
                pendingRequests.filter { it.id in selectedIds }.take(maxWorkers)
            }
            SelectionMode.FIRST_FIVE -> {
                pendingRequests.take(5)
            }
            SelectionMode.RANDOM_FIVE -> {
                pendingRequests.shuffled(Random).take(5)
            }
        }
    }
}

// Asegúrate de que tu clase Request tenga este estado (o adáptalo al tuyo)
enum class RequestState {
    PENDIENTE, EN_PROCESO, ESPERANDO_CAPTCHA, CON_CITA, FALLIDA, CANCELADA, DESCARTADA
}