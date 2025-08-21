package com.rqueue.queueprocessor.model


open class BaseQueueItem<T>(
    override val id: String,
    override var state: QueueState? = QueueState.PENDING,
    override var retryCount: Int = 0,
    override val timeoutMillis: Long? = 0,
    override var payload: String = "",
    override val priority: Int = 1
) : QueueItem<T>
