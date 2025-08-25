package com.rqueue.queueprocessor.exception

class QueueException(id:String,message:String) :Exception("$message , with id:$id")
