package com.yourteam.brainchair.control

import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** One running task + one replaceable value; no caller waits for its consumer. */
class LatestWorker<T:Any>(name:String,private val consume:(T)->Unit,
                         private val failed:(Exception)->Unit):AutoCloseable {
    private val value=AtomicReference<T?>();private val queued=AtomicBoolean()
    @Volatile private var closed=false
    private val executor=Executors.newSingleThreadExecutor{r->Thread(r,name).apply{isDaemon=true}}
    fun offer(next:T){if(closed)return;value.set(next);schedule()}
    private fun schedule(){
        if(closed || !queued.compareAndSet(false,true))return
        try{executor.execute{
            try{while(!closed){val item=value.getAndSet(null) ?: break;try{consume(item)}catch(e:Exception){failed(e)}}}
            finally{queued.set(false);if(value.get()!=null && !closed)schedule()}
        }}catch(_:RejectedExecutionException){queued.set(false)}
    }
    override fun close(){closed=true;value.set(null);executor.shutdownNow()}
}
