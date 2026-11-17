package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.StreamSession
import com.yourteam.brainchair.signal.*

/** Composition root used unchanged by the Android service and JVM end-to-end tests. */
class ControlRuntime(private val session:StreamSession,config:RecognitionConfig=RecognitionConfig(),
                     private val clock:()->Long={System.nanoTime()/1_000_000},private val nanos:()->Long={System.nanoTime()},
                     cpuClock:()->Long={0L},emergencyInitiallyLocked:Boolean=false,
                     persistEmergency:(Boolean)->Boolean={true},
                     scoreFunction:(Cca,Array<DoubleArray>)->DoubleArray={c,x->c.scores(x)},
                     val unityLink:UnityUdpLink?=null,
                     windowSeconds:Double=SignalTiming.WINDOW_SECONDS,
                     updateMs:Long=SignalTiming.UPDATE_MS,
                     policy:ControlPolicy=ControlPolicy(),initialMode:ControlMode=ControlMode.EEG,
                     private val persistMode:(ControlMode)->Unit={},
                     durableEmergency:EmergencyPersistence?=null,
                     isolatedTestStorage:Boolean=false,
                     startupEmergencyLock:Boolean=true,feedbackTimeoutStop:Boolean=true) : AutoCloseable {
    private val ownsPersistence=durableEmergency==null
    val persistence=durableEmergency ?: EmergencyPersistence(CallbackEmergencyStore(emergencyInitiallyLocked,persistEmergency),true,clock)
    val authority=ControlAuthority(clock,nanos,emergencyInitiallyLocked || (!ownsPersistence && startupEmergencyLock),policy,initialMode,
        feedbackRequired=unityLink!=null && feedbackTimeoutStop,storageReady=ownsPersistence)
    val simulatedUnity=SimulatedUnityReceiver(nanos)
    val dispatcher=ControlDispatcher(authority,unityLink ?: SimulatedUnityTransport(simulatedUnity),nanos)
    @Volatile private var remoteLatchSeen=false
    @Volatile private var closed=false
    private data class Invalidation(val reason:String,val operation:Long)
    private val invalidator=LatestWorker<Invalidation>("control-invalidation",{r->
        if(!closed && authority.snapshot().operationId==r.operation)engine.resetDecision(r.reason)
    },{e->authority.transportFailed("判决清理失败：${e.message}")})
    // Registered before SignalEngine: revoke final motion before resetting analysis/UI state.
    private val boundary:(String)->Unit={reason->val h=session.health();authority.boundary(h.generation,h.streamId,reason)}
    val engine:SignalEngine
    init {
        require(unityLink==null || durableEmergency!=null || isolatedTestStorage){
            "真实UDP运行时必须提供安全持久化；临时内存存储只允许显式隔离测试"
        }
        session.addInvalidationListener(boundary)
        val h=session.health();authority.boundary(h.generation,h.streamId,h.status)
        engine=SignalEngine(session,config,clock,cpuClock,scoreFunction,authority::recognition,windowSeconds,updateMs)
        authority.onSafetyInvalidated={reason,operation->invalidator.offer(Invalidation(reason,operation))}
        unityLink?.onFeedback=feedback@{state,restart,received,epoch->
            if(closed)return@feedback
            if(epoch==authority.snapshot().feedbackEpoch && (state.emergency || state.action==6)) {
                if(!authority.snapshot().emergencyLocked){
                    authority.remoteEmergencyStop();saveLocked(true);invalidateAsync("Unity急停锁定")
                }
                remoteLatchSeen=true
                dispatcher.emergencyObserved(authority.snapshot().emergencySequence)
            }
            authority.feedback(state,received,restart,epoch)
        }
        if(!ownsPersistence){
            if(startupEmergencyLock || emergencyInitiallyLocked)saveLocked(true,bootstrap=true)
            else persistence.restore{health->if(!closed){
                if(health.locked && !authority.snapshot().emergencyLocked)authority.remoteEmergencyStop()
                authority.storageState(health.ready,if(health.ready)"已恢复并校验保存状态" else health.lastError?:"安全记录不可确认")
                if(!health.ready)invalidateAsync("安全记录不可确认，保持急停")
            }}
        }
    }
    private fun invalidateAsync(reason:String){invalidator.offer(Invalidation(reason,authority.snapshot().operationId))}
    private fun saveLocked(locked:Boolean,bootstrap:Boolean=false){
        authority.storageState(false,"正在保存/校验")
        persistence.request(locked,bootstrap){health->if(!closed){
            authority.storageState(health.ready,if(health.ready)"已保存并校验" else health.lastError?:"保存失败")
            if(!health.ready)invalidateAsync("急停持久化失败，禁止运动")
        }}
    }
    fun start(){unityLink?.start();dispatcher.start();engine.start()}
    fun nowMs()=clock()
    fun ordinaryStop(){authority.ordinaryStop();persistMode(ControlMode.MANUAL);engine.resetDecision("人工普通停止")}
    fun switchMode(mode:ControlMode){
        if(mode==ControlMode.MANUAL){authority.switchMode(mode);persistMode(mode);engine.resetDecision("手动模式，EEG无控制权");return}
        val token=authority.prepareEeg()
        persistMode(mode)
        // Snapshot outside the engine lock: UDP invalidation takes session -> engine locks.
        val h=session.health()
        engine.resetDecision("重新选择EEG，旧计算及投票作废") { r->
            authority.activateEeg(token,r.analysisCount,h.nextIndex,h.generation)
        }
    }
    fun manual(command:ControlCommand):Boolean {
        if(command==ControlCommand.ESTOP){emergencyStop();return true}
        if(command==ControlCommand.STOP){ordinaryStop();return true}
        val accepted=authority.manual(command)
        persistMode(ControlMode.MANUAL)
        engine.resetDecision("手动方向接管，EEG旧计算及投票作废")
        return accepted
    }
    fun configurePolicy(policy:ControlPolicy){authority.configurePolicy(policy);engine.resetDecision("宽限参数变更")}
    fun configureFeedbackProtection(enabled:Boolean){authority.configureFeedbackProtection(enabled)}
    fun configureUnity(ip:String,speed:Int) {
        require(speed in 0..100);if(ip.isNotBlank())UnitySum8Codec.ipv4(ip)
        dispatcher.reconfigure {
            unityLink?.configure(ip);authority.setSpeed(speed)
            unityLink?.snapshot()?.let{authority.expectFeedback(it.epoch)}
            if(authority.snapshot().emergencyLocked){remoteLatchSeen=false;authority.remoteEmergencyStop()}
        }
        engine.resetDecision("Unity配置变更，需新流或新的手动操作")
    }
    fun emergencyStop(){
        remoteLatchSeen=false;authority.emergencyStop() // authoritative lock + wake priority sender first.
        saveLocked(true);invalidateAsync("人工急停锁定");persistMode(ControlMode.MANUAL)
    }
    fun retrySafetyStorage(){
        authority.remoteEmergencyStop();remoteLatchSeen=false;saveLocked(true,bootstrap=true)
        invalidateAsync("重新校验安全存储，保持急停")
    }
    private fun canReleaseActual():Boolean {
        val local=authority.snapshot();val link=unityLink?.snapshot() ?: return false
        val state=link.feedback ?: return false;val received=link.receivedNs ?: return false
        return remoteLatchSeen && dispatcher.emergencyBurstComplete(local.emergencySequence) &&
            nanos()-received in 0 until SafetyTiming.UNLOCK_FRESH_NS && received>dispatcher.lastEmergencySentNs &&
            state.stationary && !state.emergency && state.action!=6 && !state.fault && link.epoch==local.feedbackEpoch
    }
    fun releaseEmergency():Boolean {
        val before=authority.snapshot()
        fun approved()=if(unityLink==null)!simulatedUnity.current().moving && simulatedUnity.fresh(SafetyTiming.UNLOCK_FRESH_NS) else canReleaseActual()
        if(!before.emergencyLocked || !persistence.snapshot().ready || !approved())return false
        authority.storageState(false,"正在保存解除请求；本地锁尚未解除")
        persistence.request(false){health->if(!closed){
            authority.storageState(health.ready,if(health.ready)"保存成功，复核远端条件" else health.lastError?:"解除保存失败")
            val released=health.ready && authority.completeEmergencyRelease(before.operationId,before.emergencySequence){
                approved() && (unityLink!=null || simulatedUnity.releaseEmergency())
            }
            if(released){remoteLatchSeen=false;invalidateAsync("急停已确认解除，等待新控制输入")}
            else if(health.ready)saveLocked(true) // conditions changed while disk was writing; never unlock.
        }}
        return true // request accepted only; UI must observe emergencyLocked=false before claiming release.
    }
    override fun close(){
        closed=true;authority.onSafetyInvalidated={_,_->}
        authority.safetyStop("控制运行时关闭");dispatcher.close()
        session.stop("控制运行时关闭");engine.close();invalidator.close();session.removeInvalidationListener(boundary)
        if(ownsPersistence)persistence.close()
    }
}
