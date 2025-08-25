package com.example.queue


import android.util.Log
import com.example.queue.repo.InMemoryQueueRepository
import com.example.queue.exception.MaxRetryException
import com.example.queue.exception.QueueException
import com.example.queue.exception.QueueTimeoutException
import com.example.queue.model.BackoffStrategy
import com.example.queue.model.DefaultRetryStrategy
import com.example.queue.model.FixedTimeout
import com.example.queue.model.QueueItem
import com.example.queue.model.QueueRunner
import com.example.queue.model.QueueState
import com.example.queue.model.RetryStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class QueueProcessor<T : QueueItem<T>>(
    private val runner: QueueRunner<T>,
    private val backoff: BackoffStrategy,
    private val defaultTimeout: FixedTimeout? = null,
    private val retryStrategy: RetryStrategy = DefaultRetryStrategy(),
    private val maxRetries: Int = 3,
    override val coroutineContext: CoroutineContext,
) : CoroutineScope {

    private val repository = InMemoryQueueRepository<T>()

    companion object {
        private const val TAG = "QueueProcessor"
    }

    // This flag ensures only one processing loop runs at a time.
    private val isProcessing = AtomicBoolean(false)

    init {
        Log.d(TAG, "QueueProcessor initialized.")
    }


    suspend fun addToQueue(item: T) {
        Log.d(TAG, "Adding item to repository: ${item.id}")

        // Add the basic entity to the repository for state tracking.
        repository.add(
            item
        )
    }

    suspend fun removeProcess(state: QueueState) {
        repository.deleteByState(state)
    }

    suspend fun removeProcess(id: String) {
        repository.deleteById(id)
    }

    suspend fun addToQueue(items: List<T>) {
        Log.d(TAG, "Adding ${items.size} item to repository.")
        // Add the basic entity to the repository for state tracking.
        repository.add(
            items
        )
    }

    suspend fun enqueue(items: List<T>) {
        Log.d(TAG, "Enqueuing ${items.size} new items. ")
        this.addToQueue(items)
        // Instead of processing directly, we trigger the central loop.
        triggerProcessing()
    }

    suspend fun enqueue(item: T) {
        Log.d(TAG, "Enqueuing new item: ${item.id}")
        this.addToQueue(item)
        // Instead of processing directly, we trigger the central loop.
        triggerProcessing()
    }


    suspend fun queueIsEmpty() = getQueue().isEmpty()
    suspend fun getQueueModelWithId(id: String) = repository.getQueueEntityWithId(id)
    suspend fun updatePayload(id: String, payload: String) = repository.updatePayload(id, payload)
    suspend fun getQueueModelWithPayload(payload: String) =
        repository.getQueueEntityWithPayload(payload)

    suspend fun getQueue(state: QueueState? = null): List<T> {
        Log.d(TAG, "Fetching items from queue with state: $state")
        return repository.fetchState(state) as List<T>
    }

    private suspend fun triggerProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            // Explicitly use 'this' class's scope for the launch.
            coroutineScope {
                launch {
                    try {
                        while (true) {
                            // 1. Get the highest-priority pending entity from the repository.
                            val nextItemEntity =
                                repository.fetchState(QueueState.PENDING).firstOrNull()
                                    ?: break // Exit loop if queue is empty.

                            Log.d(TAG, "Loop is processing item: ${nextItemEntity.id}")
                            process(nextItemEntity as T)
                        }
                    } finally {
                        isProcessing.set(false)
                    }
                }
            }
        }
    }

    suspend fun process() {
        val item = repository.fetchState(QueueState.PENDING).firstOrNull()
        if (item != null) {
            process(item as T)
        }
    }

    /**
     * Processes a single item, handling its entire lifecycle including timeouts and retries.
     */
    private suspend fun process(item: T) {
        var successful = false
        // The loop condition now includes the initial attempt (retryCount starts at 0)
        try {

            while (item.retryCount <= maxRetries && !successful) {
                var attempt = item.retryCount
                Log.i(TAG, "Starting attempt ${(attempt)}/$maxRetries for item ${item.id}")
                attempt += 1
                repository.updateState(item.id, QueueState.SENDING)

                try {
                    val timeout = item.timeoutMillis ?: defaultTimeout?.timeoutMillis ?: 0

                    // Use withTimeoutOrNull to race the task against a timeout.
                    val result = if (timeout > 0) {
                        withTimeoutOrNull(timeout) {
                            runner.run(item)
                        }
                    } else {
                        runner.run(item)
                    }

                    // Check timeout
                    if (result == null) {
                        throw QueueTimeoutException(id = item.id)
                    } else if (result) {
                        successful = true
                        Log.i(TAG, "Item ${item.id} sent successfully.")
                        repository.deleteById(item.id)
                    } else {
                        // result is false (explicit failure) or null (timeout)
                        val reason = "failed"
                        throw QueueException(id = item.id, message = "Task execution $reason")
                    }
                } catch (e: Exception) {
                    when (retryStrategy.strategy) {
                        RetryStrategy.Strategy.RETRY_ON_FAILED -> {
                            if (e !is QueueException) {
                                return
                            }
                        }

                        RetryStrategy.Strategy.RETRY_ON_TIME_OUT -> {
                            if (e !is QueueTimeoutException) {
                                return
                            }
                        }

                        RetryStrategy.Strategy.RETRY_WHEN_UN_SUCCESS -> {

                        }

                        RetryStrategy.Strategy.NO_RETRY -> return
                    }

                    Log.w(TAG, "Attempt ${attempt - 1} for item ${item.id} failed: ${e.message}")
                    item.retryCount++ // Increment retry count only on failure

                    if (item.retryCount <= maxRetries) {
                        val delayMillis = backoff.nextDelay(item.retryCount)
                        Log.d(TAG, "Waiting ${delayMillis}ms for the next attempt.")
                        repository.updateState(item.id, QueueState.PENDING)
                        delay(delayMillis)
                    } else {
                        throw MaxRetryException(maxRetries, id = item.id)
                    }
                }
            }
        } catch (e: MaxRetryException) {
            Log.e(
                TAG,
                "Max retries reached for item ${item.id} , Exception : $e , Deleting from repository."
            )
            runner.onError(item, e, retryStrategy)
            repository.deleteById(item.id)
        } catch (e: QueueTimeoutException) {
            Log.e(TAG, "Time out for item ${item.id}.$e , Deleting from repository.")
            runner.onError(item, e, retryStrategy)
            repository.deleteById(item.id)
        } catch (e: QueueException) {
            Log.e(TAG, "Process failed for item ${item.id}. $e , Deleting from repository.")
            runner.onError(item, e, retryStrategy)
            repository.deleteById(item.id)
        } catch (e: Exception) {
            Log.e(TAG, "Exception for item ${item.id}. $e , Deleting from repository.")
            runner.onError(item, e, retryStrategy)
            repository.deleteById(item.id)
        } finally {
//            // If the loop finishes and the task was still not successful, it means we've maxed out retries.
//            if (!successful) {
//                Log.e(TAG,"Max retries reached for item ${item.id}. Deleting from repository.")
//                runner.onError(item, MaxRetryException(maxRetries, id = item.id), retryStrategy)
//                repository.deleteById(item.id)
//            }
        }

    }


    suspend fun clear() {
        repository.clear()
        isProcessing.set(false)
        coroutineContext.cancelChildren()
    }

    fun isProcessing() = isProcessing.get()


    suspend fun update(updateSubscribeModel: T) {
        repository.update(updateSubscribeModel)
    }

}
