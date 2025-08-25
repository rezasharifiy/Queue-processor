package com.rqueue.queueprocessor.model

interface RetryStrategy {
    val strategy: Strategy
    enum class Strategy {
        RETRY_ON_FAILED, RETRY_ON_TIME_OUT, RETRY_WHEN_UN_SUCCESS,NO_RETRY
    }
}
