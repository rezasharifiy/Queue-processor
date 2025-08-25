package com.example.queue.repo

import com.example.queue.model.QueueState


data class QueuedEntity(
    val id: String,
    var state: QueueState,
    var retryCount: Int,
    var timeout: Long?,
    var payload: String,
    val priority: Int
)
