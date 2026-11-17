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
    val mode:ControlMode=ControlMode.EEG,val speed:Int=30,val emergencySequence:Long=0,
    val remoteFault:Boolean=false,val transitioning:Boolean=false,val operationId:Long=0,
    val confirmed:ControlCommand?=null,val rejectedSinceMs:Long?=null,val graceUntilMs:Long?=null,
    val lastSupportMs:Long?=null,val lastManualMs:Long?=null,val lastManualAction:String?=null,
    val feedbackRequired:Boolean=false,val feedbackReceivedNs:Long?=null,val feedbackHealthy:Boolean=false,
    val feedbackStationary:Boolean=false,val feedbackRecoveryRequired:Boolean=false,
    val feedbackReason:String="等待Unity状态",val storageReady:Boolean=true,val storageReason:String="",
    val transportFault:String?=null,val feedbackEpoch:Long=0,val feedbackFaultReason:String?=null,
    val feedbackSafetyFault:Boolean=false)
data class ControlEvent(val sequence:Long,val atMs:Long,val kind:String,val state:FinalControl)

/** One serialized output authority. It never acquires engine/session locks or caches a send task.
 * Deadlines belong to CURRENT state; replacing a manual command cancels its old deadline without
 * leaving a captured timer that could stop/restart a newer action. */
class ControlAuthority(private val clock:()->Long={System.nanoTime()/1_000_000},
    private val nanos:()->Long={System.nanoTime()},emergencyInitiallyLocked:Boolean=false,
    policy:ControlPolicy=ControlPolicy(),initialMode:ControlMode=ControlMode.EEG,
    feedbackRequired:Boolean=false,storageReady:Boolean=true) {
    private var state=FinalControl(command=if(emergencyInitiallyLocked)ControlCommand.ESTOP else ControlCommand.STOP,
        emergencyLocked=emergencyInitiallyLocked,changedNs=nanos(),emergencySequence=if(emergencyInitiallyLocked)1 else 0,mode=initialMode,
        feedbackRequired=feedbackRequired,storageReady=storageReady)
    private var policy=policy
    private var manualHold=false
    private var observedAnalysis=0L
    private var handledAnalysis=-1L
    private var minimumAnalysis=-1L
    private var minimumWindowEnd=0L
    private var minimumInputMs=Long.MIN_VALUE
    private var applyingConfigurations=0
    private val events=ArrayDeque<ControlEvent>()
    private var eventSequence=0L
    var onChanged:()->Unit={}
    /** Must only enqueue invalidation; never acquire engine locks or perform IO here. */
    var onSafetyInvalidated:(String,Long)->Unit={_,_->}
    private fun event(kind:String){
        if(events.size>=128)events.removeFirst()
        events.addLast(ControlEvent(++eventSequence,clock(),kind,state))
    }
    @Synchronized fun events():List<ControlEvent> = events.toList()
    private fun clearConfirmed(){state=state.copy(confirmed=null,rejectedSinceMs=null,graceUntilMs=null,lastSupportMs=null)}
    private fun nextOperation():Long {
        state=state.copy(operationId=state.operationId+1,transitioning=applyingConfigurations>0)
        return state.operationId
    }
    private fun feedbackFresh()=state.feedbackReceivedNs?.let{nanos()-it in 0 until SafetyTiming.FEEDBACK_NS}==true
    private fun blocked():String?=when {
        !state.storageReady->"安全存储未确认：${state.storageReason}"
        state.transportFault!=null->"发送故障锁定：${state.transportFault}；需重启服务"
        state.feedbackRequired && (!feedbackFresh() || !state.feedbackHealthy)->"Unity反馈未满足许可：${state.feedbackReason}"
        state.feedbackRecoveryRequired->"反馈故障已锁定；需新鲜静止状态及明确重新授权"
        state.remoteFault->"Unity报告故障，禁止运动"
        state.transitioning || applyingConfigurations>0->"控制配置切换中，禁止运动"
        state.speed==0->"速度为0，安全停止"
        else->null
    }
    private fun authorizeFeedbackRecovery():Boolean {
        if(state.feedbackRecoveryRequired && state.feedbackHealthy && feedbackFresh() && state.feedbackStationary &&
            state.storageReady && state.transportFault==null && !state.emergencyLocked && !state.remoteFault){
            state=state.copy(feedbackRecoveryRequired=false,feedbackFaultReason=null,feedbackSafetyFault=false);event("FEEDBACK_REAUTHORIZED")
        }
        return blocked()==null
    }
    private fun feedbackStop(reason:String,hardFault:Boolean=false){
        if(state.feedbackRecoveryRequired && (!hardFault || state.feedbackSafetyFault))return
        nextOperation();clearConfirmed();manualHold=true
        state=state.copy(feedbackRecoveryRequired=true,feedbackFaultReason=reason,
            feedbackSafetyFault=state.feedbackSafetyFault || hardFault)
        set(ControlCommand.STOP,reason,force=true);event("FEEDBACK_FAULT")
        onSafetyInvalidated(reason,state.operationId)
    }
    @Synchronized fun expectFeedback(epoch:Long){
        state=state.copy(feedbackEpoch=epoch,feedbackReceivedNs=null,feedbackHealthy=false)
        if(state.feedbackRequired)feedbackStop("Unity地址变更，必须重新确认新对端反馈")
        else safetyStop("Unity地址变更，等待新的控制输入")
    }
    /** Toggling timeout protection never clears remote faults, ESTOP, storage or TX faults. */
    @Synchronized fun configureFeedbackProtection(enabled:Boolean){
        if(enabled==state.feedbackRequired)return
        nextOperation();clearConfirmed();manualHold=true
        state=state.copy(feedbackRequired=enabled)
        if(!enabled && !state.feedbackSafetyFault)state=state.copy(feedbackRecoveryRequired=false,feedbackFaultReason=null)
        set(ControlCommand.STOP,"9002超时策略变更，需重新选择EEG或手动操作",force=true)
        if(enabled)feedbackStop("已启用9002严格保护，需新鲜静止反馈及明确授权")
        onSafetyInvalidated("9002超时策略变更，旧判决作废",state.operationId)
        event("FEEDBACK_POLICY: $enabled")
    }
    @Synchronized fun feedback(value:UnityState,receivedNs:Long,restart:Boolean=false,epoch:Long=0) {
        if(epoch!=state.feedbackEpoch || receivedNs>nanos())return
        // Unity STOP is a smooth deceleration: action=05 and moving=true are compatible.
        // It remains NON-stationary until measured velocities and the moving bit are zero.
        val healthy=!value.emergency && !value.fault && value.action in 1..5
        val issue=when{
            value.emergency || value.action==6->"Unity报告急停"
            value.fault->"Unity报告故障(bit1)"
            value.action !in 1..5->"Unity动作未知/未就绪"
            restart->"Unity重启/时间戳回退"
            else->null
        }
        val reason=if(issue!=null)"$issue：flags=0x${value.flags.toString(16).padStart(2,'0')} action=0x${value.action.toString(16).padStart(2,'0')} linear=${value.linearMmS} angular=${value.angularMilliRadS} remoteMs=${value.timestampMs}"
            else if(value.action==5 && !value.stationary)"Unity普通STOP减速中（非故障）" else "有效9002状态"
        state=state.copy(feedbackReceivedNs=receivedNs,feedbackHealthy=healthy,feedbackStationary=value.stationary,
            remoteFault=value.fault,feedbackReason=reason)
        if(issue!=null)feedbackStop(reason,hardFault=true)
        // Healthy feedback renews freshness, NEVER a prior motion or a recovery authorization.
    }
    @Synchronized fun storageState(ready:Boolean,reason:String="") {
        state=state.copy(storageReady=ready,storageReason=reason)
        if(!ready){clearConfirmed();manualHold=true;set(ControlCommand.STOP,"安全存储待确认",force=true)}
    }
    @Synchronized fun transportFailed(reason:String){
        if(state.transportFault!=null)return
        nextOperation();state=state.copy(transportFault=reason);clearConfirmed();manualHold=true
        set(ControlCommand.STOP,"发送故障，禁止恢复运动",force=true);event("TRANSPORT_FAULT")
        onSafetyInvalidated(reason,state.operationId)
    }
    private fun set(command:ControlCommand,reason:String,expires:Long?=null,force:Boolean=false) {
        val permitted=when {
            state.emergencyLocked->ControlCommand.ESTOP
            command.moving && blocked()!=null->ControlCommand.STOP
            else->command
        }
        val actualReason=when {
            state.emergencyLocked->"急停锁定；需Unity Z解除及B端人工确认"
            command.moving && blocked()!=null->blocked()!!
            else->reason
        }
        val until=if(permitted.moving)expires else null
        if(state.command==permitted && state.reason==actualReason && state.expiresMs==until && !force)return
        val notify=force || state.command!=permitted
        val log=notify || state.reason!=actualReason
        state=state.copy(command=permitted,reason=actualReason,expiresMs=until,
            revision=state.revision+1,changedNs=nanos())
        if(log)event("CONTROL")
        if(notify)onChanged()
    }
    @Synchronized fun configurePolicy(value:ControlPolicy) {
        policy=value;clearConfirmed();manualHold=true
        set(ControlCommand.STOP,"宽限参数变更，需重新选择EEG或手动操作",force=true)
    }
    @Synchronized fun policy()=policy
    @Synchronized fun boundary(generation:Long,streamId:Long,reason:String) {
        val newStream=streamId!=state.streamId
        state=state.copy(generation=generation,streamId=streamId)
        clearConfirmed();handledAnalysis=observedAnalysis;minimumWindowEnd=0
        event("STREAM_BOUNDARY: $reason")
        if(state.mode==ControlMode.EEG) {
            if(newStream && !state.feedbackRecoveryRequired && state.transportFault==null && state.storageReady)manualHold=false
            set(ControlCommand.STOP,reason,force=true) // hard boundary: never grace an INFO/STOP/gap/CRC/timeout.
        }
    }
    /** Every delivered guard callback checks freshness; only a NEW analysis can renew support. */
    @Synchronized fun recognition(result:RecognitionSnapshot) {
        observedAnalysis=maxOf(observedAnalysis,result.analysisCount)
        expire()
        if(state.emergencyLocked || state.mode!=ControlMode.EEG || result.generation!=state.generation)return
        if(blocked()!=null){clearConfirmed();set(ControlCommand.STOP,blocked()!!);return}
        if(manualHold){set(ControlCommand.STOP,"等待明确的EEG恢复/新控制输入");return}
        val now=clock();val input=result.inputMs;val decided=result.decidedMs
        val valid=result.scores.size==4 && result.scores.all{it.isFinite() && it in 0.0..1.0} &&
            result.windowEnd>0 && input!=null && decided!=null &&
            now-input in 0 until ControlPolicy.FRESHNESS_MS && now-decided in 0 until ControlPolicy.FRESHNESS_MS
        if(!valid){clearConfirmed();set(ControlCommand.STOP,"窗口/输入/判决失效：${result.reason}");return}
        if(result.analysisCount<=minimumAnalysis || result.windowEnd<=minimumWindowEnd || input!!<minimumInputMs)return
        if(result.analysisCount<=handledAnalysis)return // watchdog/UI polling must not stretch the grace period.
        handledAnalysis=result.analysisCount
        val c=result.control
        val stable=result.raw!=Direction.STOP && result.stable==result.raw && c.output==result.stable &&
            c.expiresMs?.let{now<it}==true
        val freshUntil=minOf(input+ControlPolicy.FRESHNESS_MS,decided!!+ControlPolicy.FRESHNESS_MS)
        if(stable) {
            val direction=ControlCommand.from(result.stable)
            state=state.copy(confirmed=direction,rejectedSinceMs=null,graceUntilMs=null,lastSupportMs=decided)
            set(direction,"新鲜稳定判决确认方向",freshUntil);return
        }
        val confirmed=state.confirmed
        // Only a fully STABLE verdict above renews support. A single same-direction gate pass
        // must not restart the grace clock: alternating weak/strong windows could otherwise
        // keep an old command forever without ever rebuilding the required three votes.
        if(confirmed==null){set(ControlCommand.STOP,"首次/新方向尚未可靠稳定：${result.reason}");return}
        val since=state.rejectedSinceMs ?: decided
        val until=since+policy.rejectionGraceMs
        state=state.copy(rejectedSinceMs=since,graceUntilMs=until)
        if(now>=until){clearConfirmed();set(ControlCommand.STOP,"连续拒识超过${policy.rejectionGraceMs}ms");return}
        set(confirmed,"短暂拒识宽限：${result.reason}",minOf(until,freshUntil))
    }
    /** User buttons atomically revoke EEG, any older transition and the previous manual deadline. */
    @Synchronized fun manual(command:ControlCommand):Boolean {
        expire()
        nextOperation();state=state.copy(mode=ControlMode.MANUAL,lastManualMs=clock(),lastManualAction=command.name)
        manualHold=true;clearConfirmed()
        event("MANUAL_TAKEOVER: ${command.name}")
        when(command) {
            ControlCommand.ESTOP->{latchEmergency();return true}
            ControlCommand.STOP->{set(ControlCommand.STOP,"人工STOP接管",force=true);return true}
            else->{authorizeFeedbackRecovery();set(command,"手动接管，最长4秒",clock()+ControlPolicy.MANUAL_LIMIT_MS,force=true);return state.command.moving}
        }
    }
    @Synchronized fun ordinaryStop(){manual(ControlCommand.STOP)}
    @Synchronized fun emergencyStop(){manual(ControlCommand.ESTOP)}
    private fun latchEmergency() {
        clearConfirmed();manualHold=true
        state=state.copy(emergencyLocked=true,emergencySequence=state.emergencySequence+1)
        set(ControlCommand.ESTOP,"急停锁定",force=true)
    }
    @Synchronized fun remoteEmergencyStop(){nextOperation();latchEmergency()}
    @Synchronized fun safetyStop(reason:String) {
        nextOperation();clearConfirmed();manualHold=true;set(ControlCommand.STOP,reason,force=true)
    }
    @Synchronized fun switchMode(mode:ControlMode) {
        nextOperation();state=state.copy(mode=mode);clearConfirmed();manualHold=true
        event("MODE: ${mode.name}")
        set(ControlCommand.STOP,"模式切换，等待新鲜判决/新手动操作",force=true)
    }
    @Synchronized fun prepareEeg():Long {switchMode(ControlMode.EEG);return state.operationId}
    /** Called under the engine decision lock AFTER its token/votes were reset. No session call here. */
    @Synchronized fun activateEeg(token:Long,analysisCount:Long,cursor:Long,cursorGeneration:Long) {
        if(token!=state.operationId || state.mode!=ControlMode.EEG)return
        minimumAnalysis=maxOf(observedAnalysis,analysisCount);handledAnalysis=minimumAnalysis
        minimumWindowEnd=if(cursorGeneration==state.generation)cursor else 0
        minimumInputMs=clock();manualHold=!authorizeFeedbackRecovery()
        set(ControlCommand.STOP,"EEG已选择，等待新采样与重新稳定判决",force=true)
    }
    @Synchronized fun beginTransition():Long {
        val token=nextOperation();state=state.copy(transitioning=true);manualHold=true;clearConfirmed()
        set(ControlCommand.STOP,"控制配置切换中",force=true);return token
    }
    fun runTransition(token:Long,change:()->Unit):Boolean {
        synchronized(this){
            if(token!=state.operationId || !state.transitioning)return false
            applyingConfigurations++
        }
        // Even a misbehaving configuration callback cannot hold the emergency/deadline lock.
        try{change();return true}
        finally{synchronized(this){
            applyingConfigurations--;clearConfirmed();manualHold=true
            if(token!=state.operationId)state=state.copy(transitioning=applyingConfigurations>0)
            set(ControlCommand.STOP,"配置应用结束，等待新输入",force=true)
        }}
    }
    @Synchronized fun endTransition(token:Long) {
        if(token!=state.operationId)return // a later manual action owns output now.
        state=state.copy(transitioning=false);manualHold=true;clearConfirmed()
        set(ControlCommand.STOP,"配置切换完成，等待新的控制输入",force=true)
    }
    @Synchronized fun setSpeed(speed:Int) {
        require(speed in 0..100);state=state.copy(speed=speed)
        if(speed==0){clearConfirmed();manualHold=true;set(ControlCommand.STOP,"速度为0，安全停止",force=true)}
        else {state=state.copy(revision=state.revision+1);onChanged()}
    }
    @Synchronized fun remoteFault(fault:Boolean) {
        if(fault==state.remoteFault)return
        nextOperation();state=state.copy(remoteFault=fault);clearConfirmed();manualHold=true
        set(ControlCommand.STOP,if(fault)"Unity报告故障" else "Unity故障已清除，等待新输入",force=true)
    }
    @Synchronized fun releaseEmergency(receiverStationary:Boolean,linkFresh:Boolean,releaseReceiver:()->Boolean={true}):Boolean {
        if(!state.emergencyLocked || !state.storageReady || !receiverStationary || !linkFresh || !releaseReceiver())return false
        nextOperation();state=state.copy(emergencyLocked=false)
        // A verified, explicit Z/B acknowledgement may clear its secondary feedback latch,
        // only with the SAME healthy/fresh/stationary requirements. Never replay movement.
        authorizeFeedbackRecovery();manualHold=true;clearConfirmed()
        set(ControlCommand.STOP,"急停已人工确认解除，等待新输入",force=true);return true
    }
    @Synchronized fun completeEmergencyRelease(operation:Long,sequence:Long,approved:()->Boolean):Boolean {
        if(operation!=state.operationId || sequence!=state.emergencySequence)return false
        return releaseEmergency(true,true,approved)
    }
    private fun expire() {
        if(state.feedbackRequired && state.feedbackReceivedNs!=null && !feedbackFresh())feedbackStop("9002反馈超过${SafetyTiming.FEEDBACK_MS}ms未更新")
        val until=state.expiresMs ?: return
        if(!state.command.moving || clock()<until)return
        val reason=when {
            state.mode==ControlMode.MANUAL->"手动4秒到期，主动STOP"
            state.graceUntilMs?.let{clock()>=it}==true->"连续拒识超过${policy.rejectionGraceMs}ms，STOP"
            else->"EEG输入/判决超过1秒有效期，STOP"
        }
        clearConfirmed();set(ControlCommand.STOP,reason,force=true)
    }
    @Synchronized fun enforceDeadlines(){expire()}
    @Synchronized fun snapshot():FinalControl {expire();return state}
    fun deliver(send:(FinalControl)->Unit) {send(snapshot())} // NEVER hold the arbitration lock across IO.
}
