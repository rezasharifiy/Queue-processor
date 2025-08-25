package com.rqueue.queueprocessor.repo

import com.rqueue.queue.model.QueueState

data class QueuedEntity(
    val id: String,
    var state: QueueState,
    var retryCount: Int,
    var timeout: Long?,
    var payload: String,
    val priority: Int
)
