package com.example.queue.exception

class QueueException(id:String,message:String) :Exception("$message , with id:$id")
