package com.rqueue.queueprocessor.repo

import com.rqueue.queueprocessor.model.QueueItem
import com.rqueue.queueprocessor.model.QueueState

interface QueueRepository<T : QueueItem<T>> {
    suspend fun add(entity: QueueItem<T>)
    suspend fun add(entities: List<QueueItem<T>>)
    suspend fun deleteById(id: String)
    suspend fun deleteByState(state: QueueState)
    suspend fun deleteByPayload(payload: String)
    suspend fun fetchDue(): List<QueueItem<T>>
    suspend fun getQueueEntityWithId(id: String): QueueItem<T>?
    suspend fun updatePayload(id: String, payload: String)
    suspend fun getQueueEntityWithPayload(payload: String): QueueItem<T>?
    suspend fun update(updateSubscribeModel: QueueItem<T>)
    suspend fun updateState(id: String, state: QueueState)
    suspend fun updateRetry(id: String, retryCount: Int, nextAttempt: Long)
    suspend fun fetchState(state: QueueState?): List<QueueItem<T>>
    suspend fun clear()
}
