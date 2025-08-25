package com.rqueue.queueprocessor.model

interface BackoffStrategy {
    fun nextDelay(retryCount: Int): Long
}
