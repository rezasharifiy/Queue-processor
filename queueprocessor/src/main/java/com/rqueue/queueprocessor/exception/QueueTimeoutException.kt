package com.rqueue.queueprocessor.exception

class QueueTimeoutException(id:String) :Exception("Time out process with id :  $id.")
