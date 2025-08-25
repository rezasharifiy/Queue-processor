package com.rqueue.queueprocessor.model

class DefaultRetryStrategy(override val strategy: RetryStrategy.Strategy = RetryStrategy.Strategy.RETRY_WHEN_UN_SUCCESS) :
    RetryStrategy
