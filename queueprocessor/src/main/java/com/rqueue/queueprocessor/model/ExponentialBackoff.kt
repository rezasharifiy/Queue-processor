package com.rqueue.queueprocessor.model

class ExponentialBackoff(private val baseMillis: Long = 1000) : BackoffStrategy {
    override fun nextDelay(retryCount: Int) = baseMillis * (1 shl retryCount)
}
