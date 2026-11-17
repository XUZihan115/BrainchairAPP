package com.yourteam.brainchair.signal

import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.yourteam.brainchair.receiver.StreamSession
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.yourteam.brainchair.control.ControlRuntime

/** Bounded local debug evidence, never used as classifier input. Disk IO has its own worker. */
class RecognitionTrace(directory:File, private val session:StreamSession, private val engine:SignalEngine,
                       private val controls:ControlRuntime?=null,private val diagnosticsEnabled:()->Boolean={true},
                       storage:TraceStorage?=null) {
    private val latest=AtomicFile(File(directory,"stage2-status.json"))
    val health=TraceHealth({SystemClock.elapsedRealtime()}){message->Log.w("BrainChairTrace",message)}
    private val sink=storage ?: FileTraceStorage(directory,{json->
        val output=latest.startWrite()
        try{output.write(json.toByteArray(Charsets.UTF_8));latest.finishWrite(output)}catch(e:Exception){latest.failWrite(output);throw e}
    })
    private val writer=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"eeg-evidence").apply{isDaemon=true} }
    private var rows=0
    private var lastSummaryMs=0L;private var lastReceived=0L;private var lastBlocks=0L
    private var lastSent=0L;private var lastEvent=0L;private var lastAnalysis=-1L
    private var receivePps=0.0;private var blockPps=0.0;private var sendPps=0.0
    private fun diagnostic(json:JSONObject){
        health.phase("诊断写入/轮换");sink.diagnostic(json.toString())
    }
    private val header="elapsedMs,analysisCount,windowEnd,raw,stable,reason,rho0,rho1,rho2,rho3,computeMs,analysisCpuMs,periodMs,processCpuMs,heapBytes,received,accepted,missingPackets,winner,controlOutput,controlReason,inputMs,expiresMs,controlRevision\n"
    fun start(){ writer.scheduleWithFixedDelay({health.protect{health.phase("采集诊断状态");record()}},0,200,TimeUnit.MILLISECONDS) }
    private fun record() {
        val s=session.snapshot();val r=engine.snapshot();val now=SystemClock.elapsedRealtime()
        val monotonicMs=controls?.nowMs() ?: System.nanoTime()/1_000_000;val h=session.health();val progress=engine.progress()
        val summaryDue=now-lastSummaryMs>=1000
        val sentCount=controls?.dispatcher?.status()?.total ?: 0
        if(summaryDue){
            val duration=(now-lastSummaryMs).coerceAtLeast(1)
            if(lastSummaryMs>0){receivePps=(s.received-lastReceived)*1000.0/duration;blockPps=(s.blockPackets-lastBlocks)*1000.0/duration;sendPps=(sentCount-lastSent)*1000.0/duration}
            lastSummaryMs=now;lastReceived=s.received;lastBlocks=s.blockPackets;lastSent=sentCount
        }
        val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val json=JSONObject().put("elapsedMs",now).put("raw",r.raw.name).put("stable",r.stable.name).put("reason",r.reason)
            .put("windowSeconds",engine.windowSeconds).put("updateMs",engine.updateMs)
            .put("logHealth",JSONObject().put("state",health.snapshot().state).put("failures",health.snapshot().failures)
                .put("lastError",health.snapshot().lastError).put("lastSuccessMs",health.snapshot().lastSuccessMs))
            .put("monotonicMs",monotonicMs).put("receivePps",receivePps).put("blockPps",blockPps).put("unitySendPps",sendPps)
            .put("lastValidInputMs",h.lastDataMs).put("inputAgeMs",h.lastDataMs?.let{monotonicMs-it})
            .put("windowSamples",progress.samples).put("windowRequired",progress.required).put("warmupRemaining",progress.warmupRemaining)
            .put("streamStartedNs",s.streamStartedNs).put("decidedMs",r.decidedMs)
            .put("analysisCount",r.analysisCount).put("windowEnd",r.windowEnd).put("generation",r.generation)
            .put("scores",org.json.JSONArray(r.scores)).put("computeMs",r.computeMs).put("analysisCpuMs",r.cpuMs).put("periodMs",r.periodMs)
            .put("processCpuMs",Process.getElapsedCpuTime()).put("heapBytes",heap).put("received",s.received).put("accepted",s.accepted)
            .put("missingPackets",s.missingPackets).put("rejected",JSONObject(s.rejected)).put("absolute",r.config.absolute).put("difference",r.config.difference)
            .put("winner",r.winner.name).put("controlOutput",r.control.output.name).put("controlReason",r.control.reason)
            .put("topScore",r.scores.maxOrNull()).put("secondScore",r.scores.sortedDescending().getOrNull(1))
            .put("scoreGap",if(r.scores.size==4)r.scores.sortedDescending().let{it[0]-it[1]} else null)
            .put("voteCount",r.voteCount).put("requiredVotes",r.config.votes)
            .put("inputMs",r.inputMs).put("expiresMs",r.control.expiresMs).put("controlRevision",r.control.revision)
            .put("infoPackets",s.infoPackets).put("blockPackets",s.blockPackets).put("stopPackets",s.stopPackets)
            .put("receiverStatus",s.status).put("source",s.source).put("sampleRate",s.info?.rate).put("channelCount",s.info?.channels)
            .put("nextIndex",s.nextIndex).put("buffered",s.buffered).put("logs",org.json.JSONArray(s.logs))
            .put("stopAudits",org.json.JSONArray(s.stopAudits.map { a->JSONObject().put("seq",a.seq).put("nextIndex",a.nextIndex)
                .put("reason",a.reason).put("source",a.source).put("acceptStartedNs",a.acceptStartedNs)
                .put("invalidationCompletedNs",a.invalidationCompletedNs).put("listenersNotified",a.listenersNotified).put("processingMs",a.processingMs)
                .put("socketReceivedNs",a.socketReceivedNs).put("receiveToInvalidationMs",a.receiveToInvalidationMs) }))
        controls?.let { c->
            val final=c.authority.snapshot();val transport=c.dispatcher.status();val link=c.unityLink?.snapshot()
            json.put("streamId",s.streamId).put("finalCommand",final.command.name).put("finalRevision",final.revision)
                .put("finalReason",final.reason).put("finalChangedNs",final.changedNs).put("emergencyLocked",final.emergencyLocked)
                .put("controlMode",final.mode.name).put("speed",final.speed).put("remoteFault",final.remoteFault)
                .put("transitioning",final.transitioning)
                .put("motionFeedbackHealthy",final.feedbackHealthy).put("feedbackRecoveryRequired",final.feedbackRecoveryRequired)
                .put("feedbackTimeoutStop",final.feedbackRequired).put("feedbackSafetyFault",final.feedbackSafetyFault)
                .put("feedbackFaultReason",final.feedbackFaultReason).put("storageReady",final.storageReady).put("storageReason",final.storageReason)
                .put("transportFault",final.transportFault).put("transportFailures",transport.failures).put("sendTimeouts",transport.timeouts)
                .put("lateSendCompletions",transport.lateCompletions).put("remoteEmergencyObserved",c.dispatcher.remoteEmergencyObserved(final.emergencySequence))
                .put("persistenceFailures",c.persistence.snapshot().failures).put("persistencePending",c.persistence.snapshot().pending)
                .put("confirmedDirection",final.confirmed?.name).put("rejectedSinceMs",final.rejectedSinceMs)
                .put("graceUntilMs",final.graceUntilMs).put("graceRemainingMs",final.graceUntilMs?.let{(it-monotonicMs).coerceAtLeast(0)})
                .put("rejectionGraceMs",c.authority.policy().rejectionGraceMs).put("finalExpiresMs",final.expiresMs)
                .put("manualRemainingMs",if(final.mode==com.yourteam.brainchair.control.ControlMode.MANUAL)final.expiresMs?.let{(it-monotonicMs).coerceAtLeast(0)} else null)
                .put("lastManualMs",final.lastManualMs).put("lastManualAction",final.lastManualAction)
                .put("transportMode",transport.mode).put("transportError",transport.error).put("sentCommand",transport.sent?.command?.name)
                .put("sentNs",transport.sent?.sentNs).put("sentSerial",transport.sent?.serial)
                .put("motionSendCount",transport.motion).put("stopSendCount",transport.stops).put("estopSendCount",transport.emergencies)
                .put("maxContinuousMotionSendGapMs",transport.maxMotionGapMs).put("lastMotionSentNs",transport.lastMotionNs)
                .put("controlFixRealDeviceRetested",false)
            if(link!=null){
                json.put("unityTarget",link.target).put("unitySentPackets",link.sentPackets).put("unityLastSentHex",link.lastSentHex)
                    .put("unitySenderThread",link.senderThread)
                    .put("unityFeedbackOnline",link.feedbackOnline).put("unityFeedbackReceivedNs",link.receivedNs)
                    .put("unityValidStatusPackets",link.validPackets).put("unityRejectedStatus",JSONObject(link.rejected)).put("unityLinkError",link.error)
                link.feedback?.let { u->json.put("unityFeedback",JSONObject().put("flags",u.flags).put("action",u.action)
                    .put("linearMmS",u.linearMmS).put("angularMilliRadS",u.angularMilliRadS).put("xCm",u.xCm).put("zCm",u.zCm)
                    .put("speedPercent",u.speedPercent).put("timestampMs",u.timestampMs).put("emergency",u.emergency).put("fault",u.fault)) }
            }else json.put("mockApplied",c.simulatedUnity.current().name)
            if(diagnosticsEnabled())for(e in c.authority.events().filter{it.sequence>lastEvent}){
                diagnostic(JSONObject().put("kind","EVENT").put("event",e.kind).put("atMs",e.atMs)
                    .put("sequence",e.sequence).put("mode",e.state.mode.name).put("command",e.state.command.name)
                    .put("reason",e.state.reason).put("streamId",e.state.streamId).put("generation",e.state.generation))
                lastEvent=e.sequence
            }else lastEvent=c.authority.events().lastOrNull()?.sequence ?: lastEvent
        }
        if(diagnosticsEnabled()){
            if(r.analysisCount!=lastAnalysis){diagnostic(JSONObject(json.toString()).put("kind","CCA"));lastAnalysis=r.analysisCount}
            if(summaryDue)diagnostic(JSONObject(json.toString()).put("kind","SUMMARY"))
        }else lastAnalysis=r.analysisCount
        health.phase("最新状态写入");sink.latest(json.toString())
        if(rows<6000){
            val fields=listOf(now,r.analysisCount,r.windowEnd,r.raw.name,r.stable.name,r.reason.replace(',',';'))+
                (0..3).map{r.scores.getOrNull(it)?:""}+listOf(r.computeMs,r.cpuMs,r.periodMs,Process.getElapsedCpuTime(),heap,s.received,s.accepted,s.missingPackets,
                    r.winner.name,r.control.output.name,r.control.reason.replace(',',';'),r.inputMs?:"",r.control.expiresMs?:"",r.control.revision)
            health.phase("CSV写入");sink.csv(header,fields.joinToString(","));rows++
        }
    }
    fun close(){writer.execute{health.protect{health.phase("关闭日志");sink.close()}};writer.shutdown()} // no wait for IO.
}
