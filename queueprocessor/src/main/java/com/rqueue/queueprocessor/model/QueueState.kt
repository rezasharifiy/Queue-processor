package com.rqueue.queueprocessor.model

enum class QueueState {
    PENDING,
    SENDING,
    FAILED,
    TIMED_OUT
}
