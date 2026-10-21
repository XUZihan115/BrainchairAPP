package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.Direction
import com.yourteam.brainchair.signal.RecognitionSnapshot

/** Final commands deliberately separate from the four CCA candidates and the legacy chair codec. */
enum class ControlCommand(val code:Int) {
    FORWARD(0x01), BACKWARD(0x02), LEFT(0x03), RIGHT(0x04), STOP(0x05), ESTOP(0x06);
    val moving:Boolean get()=this!=STOP && this!=ESTOP
    companion object {
        fun from(direction:Direction)=when(direction) {
            Direction.FORWARD->FORWARD;Direction.BACKWARD->BACKWARD
            Direction.LEFT->LEFT;Direction.RIGHT->RIGHT;Direction.STOP->STOP
        }
    }
}
enum class ControlMode { EEG, MANUAL }
data class FinalControl(val command:ControlCommand=ControlCommand.STOP,val reason:String="等待有效信号",
                        val emergencyLocked:Boolean=false,val generation:Long=0,val streamId:Long=0,
                        val revision:Long=0,val changedNs:Long=0,val expiresMs:Long?=null,
                        val mode:ControlMode=ControlMode.EEG,val speed:Int=30,
                        val emergencySequence:Long=0,val remoteFault:Boolean=false,val transitioning:Boolean=false)

/** One serialized authority for final output. No session/engine/UI locks are acquired here.
 * The delivery operation must be short and bounded; a STOP boundary cannot be followed by an
 * older queued motion because every delivery reads the CURRENT state under this same lock.
 */
class ControlAuthority(private val clock:()->Long={System.nanoTime()/1_000_000},
                       private val nanos:()->Long={System.nanoTime()},emergencyInitiallyLocked:Boolean=false) {
    private var state=FinalControl(command=if(emergencyInitiallyLocked)ControlCommand.ESTOP else ControlCommand.STOP,
        emergencyLocked=emergencyInitiallyLocked,changedNs=nanos(),emergencySequence=if(emergencyInitiallyLocked)1 else 0)
    private var manualHold=false
    private var transitions=0
    var onChanged:()->Unit={}
    private fun set(command:ControlCommand,reason:String,expires:Long?=null,force:Boolean=false) {
        val permitted=when {state.emergencyLocked->ControlCommand.ESTOP
            command.moving && (transitions>0 || state.remoteFault || state.speed==0)->ControlCommand.STOP
            else->command}
        val actualReason=when {command.moving && !state.emergencyLocked && transitions>0->"控制配置切换中，禁止运动"
            command.moving && !state.emergencyLocked && state.remoteFault->"Unity报告故障，禁止运动"
            command.moving && !state.emergencyLocked && state.speed==0->"速度为0，安全停止（不发送沿用旧速度的运动帧）"
            else->reason}
        if(state.command==permitted && state.reason==actualReason && state.expiresMs==expires && !force)return
        val send=force || state.command!=permitted
        state=state.copy(command=permitted,reason=actualReason,expiresMs=if(permitted.moving)expires else null,
            revision=state.revision+1,changedNs=nanos())
        if(send)onChanged()
    }
    @Synchronized fun boundary(generation:Long,streamId:Long,reason:String) {
        if(streamId!=state.streamId && state.mode==ControlMode.EEG)manualHold=false
        state=state.copy(generation=generation,streamId=streamId)
        if(state.mode==ControlMode.EEG)set(ControlCommand.STOP,reason,force=true)
    }
    @Synchronized fun recognition(result:RecognitionSnapshot) {
        if(state.emergencyLocked || state.mode!=ControlMode.EEG)return
        if(result.generation!=state.generation)return // late result from a retired analysis epoch
        if(manualHold){set(ControlCommand.STOP,"人工停止，等待下一次新流");return}
        val c=result.control
        if(result.raw==Direction.STOP || result.stable==Direction.STOP || result.raw!=result.stable ||
            c.output!=result.stable || c.expiresMs==null || clock()>=c.expiresMs) {
            set(ControlCommand.STOP,c.reason);return
        }
        set(ControlCommand.from(c.output),c.reason,c.expiresMs)
    }
    @Synchronized fun ordinaryStop() { manualHold=true;set(ControlCommand.STOP,"人工停止，等待新流或新的手动操作",force=true) }
    @Synchronized fun switchMode(mode:ControlMode) {
        if(mode==state.mode)return
        state=state.copy(mode=mode);manualHold=true
        set(ControlCommand.STOP,"模式已切换，等待新的控制输入",force=true)
    }
    @Synchronized fun manual(command:ControlCommand):Boolean {
        if(command==ControlCommand.ESTOP){emergencyStop();return true}
        if(command==ControlCommand.STOP){ordinaryStop();return true}
        if(state.mode!=ControlMode.MANUAL || state.emergencyLocked || state.remoteFault || transitions>0)return false
        manualHold=false;set(command,"手动Latch持续控制",force=true);return state.command.moving
    }
    @Synchronized fun beginTransition(){transitions++;state=state.copy(transitioning=true);manualHold=true;set(ControlCommand.STOP,"控制配置切换中",force=true)}
    @Synchronized fun endTransition(){transitions=(transitions-1).coerceAtLeast(0);state=state.copy(transitioning=transitions>0);manualHold=true;set(ControlCommand.STOP,"配置切换完成，等待新的控制输入",force=true)}
    @Synchronized fun setSpeed(speed:Int) {
        require(speed in 0..100)
        state=state.copy(speed=speed)
        if(speed==0){manualHold=true;set(ControlCommand.STOP,"速度为0，安全停止",force=true)}
        else {state=state.copy(revision=state.revision+1);onChanged()}
    }
    @Synchronized fun remoteFault(fault:Boolean) {
        if(fault==state.remoteFault)return
        state=state.copy(remoteFault=fault);manualHold=true
        set(ControlCommand.STOP,if(fault)"Unity报告故障" else "Unity故障已清除，等待新输入",force=true)
    }
    @Synchronized fun emergencyStop() {
        state=state.copy(emergencyLocked=true,emergencySequence=state.emergencySequence+1);manualHold=true
        set(ControlCommand.ESTOP,"人工急停锁定；Unity按Z解除后需在B确认",force=true)
    }
    /** Explicit manual action, confirmed stationary receiver and live transport required.
     * Releasing never resumes an old direction; another STREAM_INFO is required.
     */
    @Synchronized fun releaseEmergency(receiverStationary:Boolean,linkFresh:Boolean,releaseReceiver:()->Boolean={true}):Boolean {
        if(!state.emergencyLocked || !receiverStationary || !linkFresh)return false
        if(!releaseReceiver())return false
        state=state.copy(emergencyLocked=false);manualHold=true
        set(ControlCommand.STOP,"急停已人工确认解除，等待新输入",force=true);return true
    }
    private fun expire() {
        if(state.mode==ControlMode.EEG && state.command.moving && (state.expiresMs==null || clock()>=state.expiresMs!!))set(ControlCommand.STOP,"最终输出有效期超时")
    }
    @Synchronized fun snapshot():FinalControl {expire();return state}
    /** Used only by the serialized transport worker. No cached motion/decision is passed in. */
    @Synchronized fun deliver(send:(FinalControl)->Unit) {expire();send(state)}
}
