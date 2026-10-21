package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.StreamSession
import com.yourteam.brainchair.signal.*

/** Composition root used unchanged by the Android service and JVM end-to-end tests. */
class ControlRuntime(private val session:StreamSession,config:RecognitionConfig=RecognitionConfig(),
                     clock:()->Long={System.nanoTime()/1_000_000},private val nanos:()->Long={System.nanoTime()},
                     cpuClock:()->Long={0L},emergencyInitiallyLocked:Boolean=false,
                     private val persistEmergency:(Boolean)->Unit={},
                     scoreFunction:(Cca,Array<DoubleArray>)->DoubleArray={c,x->c.scores(x)},
                     val unityLink:UnityUdpLink?=null,
                     windowSeconds:Double=SignalTiming.WINDOW_SECONDS,
                     updateMs:Long=SignalTiming.UPDATE_MS) : AutoCloseable {
    val authority=ControlAuthority(clock,nanos,emergencyInitiallyLocked)
    val simulatedUnity=SimulatedUnityReceiver(nanos)
    val dispatcher=ControlDispatcher(authority,unityLink ?: SimulatedUnityTransport(simulatedUnity),nanos)
    @Volatile private var remoteLatchSeen=false
    // Registered before SignalEngine: revoke final motion before resetting analysis/UI state.
    private val boundary:(String)->Unit={reason->val h=session.health();authority.boundary(h.generation,h.streamId,reason)}
    val engine:SignalEngine
    init {
        session.addInvalidationListener(boundary)
        val h=session.health();authority.boundary(h.generation,h.streamId,h.status)
        engine=SignalEngine(session,config,clock,cpuClock,scoreFunction,authority::recognition,windowSeconds,updateMs)
        unityLink?.onState={state,restart->
            if(state.emergency) {
                if(!authority.snapshot().emergencyLocked)emergencyStop()
                remoteLatchSeen=true
            }
            authority.remoteFault(state.fault)
            if(restart){ordinaryStop();engine.resetDecision("Unity重启/时钟回退，需重新控制")}
        }
    }
    fun start(){unityLink?.start();dispatcher.start();engine.start()}
    fun ordinaryStop(){authority.ordinaryStop();engine.resetDecision("人工普通停止")}
    fun switchMode(mode:ControlMode){dispatcher.transition{authority.switchMode(mode)};engine.resetDecision("模式切换，等待新输入")}
    fun manual(command:ControlCommand):Boolean {
        if(command==ControlCommand.ESTOP){emergencyStop();return true}
        if(command==ControlCommand.STOP){ordinaryStop();return true}
        return authority.manual(command)
    }
    fun configureUnity(ip:String,speed:Int) {
        require(speed in 0..100);if(ip.isNotBlank())UnitySum8Codec.ipv4(ip)
        dispatcher.reconfigure {
            unityLink?.configure(ip);authority.setSpeed(speed)
            if(authority.snapshot().emergencyLocked){remoteLatchSeen=false;authority.emergencyStop()}
        }
        engine.resetDecision("Unity配置变更，需新流或新的手动操作")
    }
    fun emergencyStop(){remoteLatchSeen=false;authority.emergencyStop();persistEmergency(true);engine.resetDecision("人工急停锁定")}
    private fun canReleaseActual():Boolean {
        val local=authority.snapshot();val link=unityLink?.snapshot() ?: return false
        val state=link.feedback ?: return false;val received=link.receivedNs ?: return false
        return remoteLatchSeen && dispatcher.emergencyBurstComplete(local.emergencySequence) &&
            nanos()-received in 0 until 500_000_000L && received>dispatcher.lastEmergencySentNs &&
            state.stationary && !state.emergency && !state.fault
    }
    fun releaseEmergency():Boolean {
        val released=if(unityLink==null)
            authority.releaseEmergency(!simulatedUnity.current().moving,simulatedUnity.fresh(500_000_000L),simulatedUnity::releaseEmergency)
        else authority.releaseEmergency(true,true,::canReleaseActual)
        if(released) {
            persistEmergency(false);remoteLatchSeen=false;engine.resetDecision("急停已人工确认解除，需新流或新的手动操作")
        }
        return released
    }
    override fun close(){
        session.stop("控制运行时关闭");engine.close();dispatcher.close();session.removeInvalidationListener(boundary)
    }
}
