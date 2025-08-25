package com.example.queue.exception

class MaxRetryException(maxRetry:Int,id:String) :Exception("Process failed after $maxRetry retry. id : $id")
