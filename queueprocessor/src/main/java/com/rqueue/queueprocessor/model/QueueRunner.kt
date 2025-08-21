package com.rqueue.queueprocessor.model

import java.lang.Exception

interface QueueRunner<T>{
    /**
     * Sends the given message over the transport.
     */
    suspend fun run(model:T): Boolean
    suspend fun onError(item:T,exception: Exception,retryStrategy: RetryStrategy){}
}
