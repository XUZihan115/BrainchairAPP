package com.yourteam.brainchair.control

import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Two bounded send lanes; a separate watchdog owns deadlines, never IO. Wakeups do not carry
 * cached movement. Production transport is nonblocking and rechecks current at its IO boundary.
 * A send-budget failure locks movement until service restart; no replacement threads accumulate. */
class ControlDispatcher(private val authority:ControlAuthority,private val transport:UnityTransport,
                        private val nanos:()->Long={System.nanoTime()}):AutoCloseable {
    private fun lane(name:String)=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1),
        ThreadFactory{r->Thread(r,name).apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    private val normal=lane("control-delivery")
    private val priority=lane("control-priority")
    private val deadline=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"control-deadline").apply{isDaemon=true}}
    private val cancel=lane("control-cancel")
    private val normalQueued=AtomicBoolean();private val priorityQueued=AtomicBoolean()
    private val serial=AtomicLong()
    private data class Flight(val started:Long,val moving:Boolean,val serial:Long)
    private val normalFlight=AtomicReference<Flight?>();private val priorityFlight=AtomicReference<Flight?>()
    private val timedOut=ConcurrentHashMap.newKeySet<Long>()
    private val statsLock=Any()
    @Volatile private var started=false
    @Volatile private var closed=false
    @Volatile private var closing=false
    @Volatile private var error:String?=null
    private var last:UnityCommand?=null;private var motion=0L;private var stops=0L;private var emergencies=0L
    private var lastMotionNs:Long?=null;private var maxMotionGapMs=0.0
    private var failures=0L;private var timeouts=0L;private var canceled=0L;private var late=0L
    @Volatile private var burstSequence=-1L
    @Volatile private var burstCount=0
    @Volatile private var acknowledgedSequence=-1L
    @Volatile private var nextEmergencyNs=0L
    @Volatile var lastEmergencySentNs:Long=0;private set
    init{authority.onChanged={request()}}
    fun start(){
        if(started || closed)return;started=true
        deadline.scheduleWithFixedDelay({safetyTick()},0,SafetyTiming.DEADLINE_POLL_MS,TimeUnit.MILLISECONDS)
        deadline.scheduleWithFixedDelay({request()},0,SafetyTiming.HEARTBEAT_MS,TimeUnit.MILLISECONDS)
    }
    private fun offer(urgent:Boolean){
        if(closed || (closing && !urgent))return
        val flag=if(urgent)priorityQueued else normalQueued
        if(!flag.compareAndSet(false,true))return
        try{(if(urgent)priority else normal).execute{flag.set(false);sendCurrent(urgent)}}
        catch(_:RejectedExecutionException){flag.set(false)} // one-slot queue, next tick reads current state.
    }
    private fun request(){if(started && !closed){val s=authority.snapshot();offer(!s.command.moving)}}
    private fun safetyTick(){
        if(closed)return
        authority.enforceDeadlines()
        for(f in listOfNotNull(normalFlight.get(),priorityFlight.get())){
            if(nanos()-f.started>=SafetyTiming.SEND_BUDGET_NS && timedOut.add(f.serial)){
                synchronized(statsLock){timeouts++}
                fail("发送超过100ms预算；串号${f.serial}")
                if(f.moving)try{cancel.execute{transport.abortMotion()}}catch(_:RejectedExecutionException){}
            }
        }
        val s=authority.snapshot()
        if(s.emergencyLocked && nanos()>=nextEmergencyNs &&
            (burstCount<3 || acknowledgedSequence!=s.emergencySequence))offer(true)
    }
    private fun fail(message:String){
        error=message;synchronized(statsLock){failures++}
        authority.transportFailed(message)
    }
    private fun matches(s:FinalControl,wire:ControlCommand):Boolean {
        if(closed)return false
        val current=authority.snapshot()
        return when {
            wire==ControlCommand.ESTOP->current.emergencyLocked && current.emergencySequence==s.emergencySequence
            current.emergencyLocked->wire==ControlCommand.STOP && current.emergencySequence==s.emergencySequence && acknowledgedSequence==s.emergencySequence && burstCount>=3
            else->current.operationId==s.operationId && current.generation==s.generation &&
                current.command==wire && current.speed==s.speed && (!closing || !wire.moving)
        }
    }
    private fun sendCurrent(urgent:Boolean){
        if(closed)return
        val s=authority.snapshot();val now=nanos()
        if(s.command.moving==urgent)return // stale wakeup; use the other lane, never an old frame.
        var wire=s.command
        if(s.emergencyLocked){
            synchronized(statsLock){
                if(burstSequence!=s.emergencySequence){burstSequence=s.emergencySequence;burstCount=0;nextEmergencyNs=0}
                if(burstCount<3 || acknowledgedSequence!=s.emergencySequence){
                    if(now<nextEmergencyNs)return
                    wire=ControlCommand.ESTOP
                }else wire=ControlCommand.STOP
            }
        }
        val message=UnityCommand(wire,if(wire.moving)s.speed else 0,s.revision,serial.incrementAndGet(),now)
        val ref=if(urgent)priorityFlight else normalFlight
        // One writer per lane; deterministic flush is only supported before start().
        ref.set(Flight(now,wire.moving,message.serial))
        try{
            val ready=if(urgent)transport.priorityReady else transport.ready
            if(!ready){error="发送通道未配置或不可用";if(wire.moving)fail(error!!);return}
            if(!transport.sendGuarded(message,{matches(s,wire)},urgent)){
                synchronized(statsLock){canceled++};return
            }
            val stillCurrent=matches(s,wire)
            if(authority.snapshot().transportFault==null)error=null
            synchronized(statsLock){
                last=message
                if(!stillCurrent)late++
                if(wire.moving){
                    lastMotionNs?.let{maxMotionGapMs=maxOf(maxMotionGapMs,(now-it)/1e6)};lastMotionNs=now;motion++
                }else {lastMotionNs=null;if(wire==ControlCommand.STOP)stops++ else emergencies++}
                if(wire==ControlCommand.ESTOP && burstSequence==s.emergencySequence){
                    lastEmergencySentNs=now;burstCount++
                    nextEmergencyNs=now+if(burstCount<3)SafetyTiming.ESTOP_BURST_NS else SafetyTiming.ESTOP_RETRY_NS
                }
            }
            if(!stillCurrent){
                // A fast syscall may finish just before STOP but return just after it. Its
                // completion timestamp is not a network ordering guarantee. Over-budget IO is
                // a latched fault; any revocation also wakes the current priority state.
                if(nanos()-now>=SafetyTiming.SEND_BUDGET_NS)fail("旧发送超预算后才返回；不能证明线上无迟到包")
                offer(true)
            }
        }catch(e:Exception){fail("发送异常：${e.javaClass.simpleName}: ${e.message}")}
        finally{ref.set(null);timedOut.remove(message.serial)}
    }
    /** Direct synchronous hook for unstarted isolated JVM rigs. Production always starts lanes. */
    fun flush(){if(started)request() else {authority.enforceDeadlines();sendCurrent(!authority.snapshot().command.moving)}}
    fun emergencyObserved(sequence:Long){acknowledgedSequence=sequence}
    fun emergencyBurstComplete(sequence:Long)=burstSequence==sequence && burstCount>=3
    fun remoteEmergencyObserved(sequence:Long)=acknowledgedSequence==sequence
    /** A routine safety wakeup can already occupy the one queue slot. This is normal
     * contention, not a transport failure. Admission AND completion share one finite
     * budget, outside the arbiter; a genuinely stuck safety lane still fails closed. */
    private fun awaitSafetyAttempt(budgetMs:Long):Boolean {
        val until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val attempted=CountDownLatch(1)
        val task=Runnable{try{sendCurrent(true)}finally{attempted.countDown()}}
        var admitted=false
        while(!admitted && !closed){
            try{priority.execute(task);admitted=true}
            catch(_:RejectedExecutionException){
                if(priority.isShutdown || System.nanoTime()>=until)return false
                java.util.concurrent.locks.LockSupport.parkNanos(500_000)
                if(Thread.currentThread().isInterrupted)return false
            }
        }
        val remaining=until-System.nanoTime()
        return admitted && (attempted.count==0L || remaining>0 && attempted.await(remaining,TimeUnit.NANOSECONDS))
    }
    fun transition(change:()->Unit){
        if(closed || closing)return
        val token=authority.beginTransition()
        val task=Runnable{
            try{
                // The change callback is metadata-only. No send occurs inside the authority lock.
                if(started){
                    check(awaitSafetyAttempt(SafetyTiming.SEND_BUDGET_MS)){"旧地址STOP发送等待超时，配置未切换"}
                }else sendCurrent(true)
                authority.runTransition(token,change)
            }catch(e:Exception){fail("配置切换失败：${e.message}")}
            finally{authority.endTransition(token);if(started)offer(true) else sendCurrent(true)}
        }
        if(started)try{normal.execute(task)}catch(_:RejectedExecutionException){authority.endTransition(token);error="配置队列忙，切换未执行"}
        else task.run()
    }
    fun reconfigure(change:()->Unit)=transition(change)
    fun status():TransportStatus=synchronized(statsLock){TransportStatus(transport.mode,last,error,motion+stops+emergencies,
        motion,stops,emergencies,lastMotionNs,maxMotionGapMs,failures,timeouts,canceled,late,
        normalFlight.get()?.started,priorityFlight.get()?.started)}
    override fun close(){
        if(closed || closing)return;closing=true
        authority.safetyStop("控制服务关闭");authority.onChanged={}
        try{if(!awaitSafetyAttempt(150))error="关闭时安全发送未能在150ms内完成"}
        catch(_:InterruptedException){Thread.currentThread().interrupt();error="关闭时安全发送等待被中断"}
        closed=true;deadline.shutdownNow();normal.shutdownNow();priority.shutdownNow()
        try{cancel.execute{transport.close()}}catch(_:RejectedExecutionException){}
        cancel.shutdown() // no wait for provider IO or close.
    }
}
