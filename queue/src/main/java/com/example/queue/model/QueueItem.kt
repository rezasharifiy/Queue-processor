package com.example.queue.model


interface QueueItem<T> {
    val id: String
    var state: QueueState?
    var retryCount: Int
    val timeoutMillis: Long?
    var payload:String
    val priority: Int
}
