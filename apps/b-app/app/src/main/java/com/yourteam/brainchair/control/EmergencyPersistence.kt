package com.yourteam.brainchair.control

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class StoredEmergency(val locked:Boolean,val schema:Int=1)
interface EmergencyStore {
    fun read():StoredEmergency?
    /** A false return or exception MUST be treated as failure, including verification failure. */
    fun write(locked:Boolean):Boolean
}
data class PersistenceHealth(val ready:Boolean=false,val pending:Boolean=false,val operation:Long=0,
    val locked:Boolean=true,val failures:Long=0,val lastError:String?=null,val lastSuccessMs:Long?=null)

/** One daemon writer and one replaceable mailbox. No disk work on an emergency/deadline thread.
 * Strict startup writes LOCKED. Unity simulation may instead restore the saved lock; a missing
 * record initializes STOP/unlocked, but unreadable/invalid records never default unlocked. */
class EmergencyPersistence(private val store:EmergencyStore,initiallyReady:Boolean=false,
                           private val clock:()->Long={System.nanoTime()/1_000_000}):AutoCloseable {
    private data class Request(val id:Long,val locked:Boolean?,val bootstrap:Boolean,val done:(PersistenceHealth)->Unit)
    private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"emergency-storage").apply{isDaemon=true}}
    private val pending=AtomicReference<Request?>()
    private val queued=AtomicBoolean(false)
    private val ids=AtomicLong()
    private val stateLock=Any()
    @Volatile private var closed=false
    @Volatile private var health=PersistenceHealth(ready=initiallyReady,locked=!initiallyReady)
    fun snapshot()=health
    fun request(locked:Boolean,bootstrap:Boolean=false,done:(PersistenceHealth)->Unit={}):Long {
        return enqueue(locked,bootstrap,done)
    }
    /** A null desired value means restore, never an instruction to clear a saved true lock. */
    fun restore(done:(PersistenceHealth)->Unit):Long=enqueue(null,false,done)
    private fun enqueue(locked:Boolean?,bootstrap:Boolean,done:(PersistenceHealth)->Unit):Long {
        check(!closed)
        val id=synchronized(stateLock){
            val next=ids.incrementAndGet();health=health.copy(ready=false,pending=true,operation=next)
            pending.set(Request(next,locked,bootstrap,done));next
        }
        schedule();return id
    }
    private fun schedule(){
        if(closed || !queued.compareAndSet(false,true))return
        worker.execute{
            try{
                while(!closed){
                    val r=pending.getAndSet(null) ?: break
                    var issue:String?=null
                    var desired=r.locked ?: true
                    if(r.bootstrap)try{
                        val old=store.read()
                        if(old==null || old.schema!=1)issue="无已确认的安全记录；以锁定状态初始化"
                    }catch(e:Exception){issue="安全记录读取失败：${e.javaClass.simpleName}: ${e.message}"}
                    var success=false
                    try{
                        if(r.locked==null){
                            val old=store.read()
                            check(old==null || old.schema==1){"安全记录版本无效，禁止默认解锁"}
                            desired=old?.locked ?: false
                        }
                        check(store.write(desired)){"安全状态commit返回false"}
                        check(store.read()==StoredEmergency(desired)){"安全状态写后校验失败"}
                        success=true
                    }catch(e:Exception){issue="安全状态保存失败：${e.javaClass.simpleName}: ${e.message}"}
                    val current=synchronized(stateLock){
                        if(issue!=null)health=health.copy(failures=health.failures+1,lastError=issue)
                        if(r.id==ids.get())health=health.copy(ready=success,pending=false,locked=if(success)desired else true,
                            lastSuccessMs=if(success)clock() else health.lastSuccessMs)
                        health
                    }
                    if(r.id==ids.get() && !closed)r.done(current)
                }
            }finally{queued.set(false);if(pending.get()!=null && !closed)schedule()}
        }
    }
    override fun close(){closed=true;pending.set(null);worker.shutdownNow()} // never waits for a blocked store.
}

/** Explicit in-process fixture adapter. Production always supplies the Android durable store. */
internal class CallbackEmergencyStore(initial:Boolean,private val save:(Boolean)->Boolean):EmergencyStore {
    private var value=StoredEmergency(initial)
    override fun read()=value
    override fun write(locked:Boolean):Boolean {if(!save(locked))return false;value=StoredEmergency(locked);return true}
}
