package com.yourteam.brainchair.signal

data class TraceHealthSnapshot(val state:String="IDLE",val failures:Long=0,val consecutiveFailures:Long=0,
    val lastSuccessMs:Long?=null,val lastFailureMs:Long?=null,val lastError:String?=null,
    val phase:String="等待写入",val warnings:Long=0,val warningFailures:Long=0,val writingSinceMs:Long?=null)

/** State reads never touch a file. A hung writer remains visible as STALLED in the UI. */
class TraceHealth(private val clock:()->Long={System.nanoTime()/1_000_000},
                  private val warn:(String)->Unit={}) {
    private val lock=Any()
    @Volatile private var value=TraceHealthSnapshot()
    private var lastWarning:Long?=null
    fun snapshot():TraceHealthSnapshot {
        val s=value
        return if(s.writingSinceMs?.let{clock()-it>=2000}==true)s.copy(state="STALLED") else s
    }
    fun phase(name:String){synchronized(lock){value=value.copy(phase=name)}}
    fun protect(write:()->Unit):Boolean {
        synchronized(lock){value=value.copy(state="WRITING",writingSinceMs=clock())}
        try{
            write()
            synchronized(lock){value=value.copy(state="OK",lastSuccessMs=clock(),consecutiveFailures=0,writingSinceMs=null)}
            return true
        }catch(e:Exception){
            val message="${value.phase}: ${e.javaClass.simpleName}: ${e.message}";var warning=false
            synchronized(lock){
                val now=clock();warning=lastWarning?.let{now-it>=5000}!=false
                if(warning)lastWarning=now
                value=value.copy(state="FAULT",failures=value.failures+1,consecutiveFailures=value.consecutiveFailures+1,
                    lastFailureMs=now,lastError=message,writingSinceMs=null,warnings=value.warnings+if(warning)1 else 0)
            }
            if(warning)try{warn(message)}catch(_:Exception){synchronized(lock){value=value.copy(warningFailures=value.warningFailures+1)}}
            return false
        }
    }
}
