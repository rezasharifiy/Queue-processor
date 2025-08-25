package com.example.queue.model

interface BackoffStrategy {
    fun nextDelay(retryCount: Int): Long
}
