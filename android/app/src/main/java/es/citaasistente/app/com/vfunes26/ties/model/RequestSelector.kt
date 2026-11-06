package com.vfunes26.ties.model

import kotlin.random.Random

enum class SelectionMode {
    ALL_PENDING,
    MANUAL_SELECTION,
    FIRST_FIVE,
    RANDOM_FIVE
}

class RequestSelector {

    fun getRequestsToProcess(
        allRequests: List<Request>,
        mode: SelectionMode,
        selectedIds: List<String> = emptyList(),
        maxWorkers: Int = 5
    ): List<Request> {
        val pendingRequests = allRequests.filter { it.estado == RequestState.PENDIENTE }

        return when (mode) {
            SelectionMode.ALL_PENDING -> {
                pendingRequests.take(maxWorkers)
            }
            SelectionMode.MANUAL_SELECTION -> {
                pendingRequests.filter { req -> req.id in selectedIds }.take(maxWorkers)
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