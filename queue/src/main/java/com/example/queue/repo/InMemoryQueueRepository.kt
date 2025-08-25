package com.rqueue.queueprocessor.repo

import android.util.Log
import com.rqueue.queue.model.QueueItem
import com.rqueue.queue.model.QueueState
import com.rqueue.queueprocessor.model.QueueItem
import java.util.concurrent.ConcurrentHashMap

/**
 * A simple in‑memory cache of entities.
 */
class InMemoryQueueRepository<T : QueueItem<T>> : QueueRepository<T> {

    companion object {
        private const val TAG = "InMemoryQueueRepo"
    }


    private val cache = ConcurrentHashMap<String, QueueItem<T>>()

    init {
        Log.d(TAG, "InMemoryQueueRepository initialized.")
    }

    override suspend fun add(entity: QueueItem<T>) {
        Log.d(TAG, "add: id=${entity.id}, state=${entity.state}")
        cache[entity.id] = entity
    }

    override suspend fun add(entities: List<QueueItem<T>>) {
        entities.forEach { entity ->
            Log.d(TAG, "add: id=${entity.id}, state=${entity.state}")
            cache[entity.id] = entity
        }
    }

    override suspend fun deleteById(id: String) {
        Log.d(TAG, "deleteById: id=$id")
        cache.remove(id)
    }

    override suspend fun deleteByState(state: QueueState) {
        val ids = cache.filter { it.value.state == state }.map { it.key }
        ids.forEach {
            cache.remove(it)
        }
    }

    override suspend fun deleteByPayload(payload: String) {
        getQueueEntityWithPayload(payload)?.id?.let { id ->
            deleteById(id)
        }
    }

    override suspend fun fetchDue(): List<QueueItem<T>> {
        Log.d(TAG, "fetchDue: cache size=${cache.size}")
        return cache.values.toList().sortedByDescending { it.priority }
    }

    override suspend fun getQueueEntityWithId(id: String): QueueItem<T>? {
        return cache[id]
    }

    override suspend fun updatePayload(id: String, payload: String) {
        Log.d(TAG, "updateState: id=$id, new_payload=$payload")
        cache[id]?.payload = payload
    }

    override suspend fun getQueueEntityWithPayload(payload: String): QueueItem<T>? {
        return cache.values.firstOrNull { it.payload == payload }
    }

    override suspend fun updateState(id: String, state: QueueState) {
        Log.d(TAG, "updateState: id=$id, new_state=$state")
        cache[id]?.state = state
    }

    override suspend fun updateRetry(id: String, retryCount: Int, nextAttempt: Long) {
        Log.d(TAG, "updateRetry: id=$id, retryCount=$retryCount, nextAttempt=$nextAttempt")
        cache[id]?.let {
            it.retryCount = retryCount
        }
    }

    override suspend fun fetchState(state: QueueState?): List<QueueItem<T>> {
        Log.d(TAG, "fetchState: state=$state")
        val items = if (state == null) {
            cache.values
        } else {
            cache.values.filter { it.state == state }
        }
        Log.d(TAG, "Found ${items.size} items in state $state")
        return items.sortedByDescending { it.priority }
    }


    override suspend fun clear() {
        Log.d(TAG, "clear repository")
        cache.clear()
    }

    override suspend fun update(updateSubscribeModel: QueueItem<T>) {
        cache[updateSubscribeModel.id] = updateSubscribeModel
    }
}
