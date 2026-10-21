package com.yourteam.brainchair.signal

import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.yourteam.brainchair.receiver.StreamSession
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.yourteam.brainchair.control.ControlRuntime

/** Bounded local debug evidence, never used as classifier input. Disk IO has its own worker. */
class RecognitionTrace(directory:File, private val session:StreamSession, private val engine:SignalEngine,
                       private val controls:ControlRuntime?=null) {
    private val latest=AtomicFile(File(directory,"stage2-status.json"))
    private val csvFile=File(directory,"stage2-trace.csv")
    private val writer=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"eeg-evidence").apply{isDaemon=true} }
    private var csv=csvFile.bufferedWriter()
    private var rows=0
    init { csv.write("elapsedMs,analysisCount,windowEnd,raw,stable,reason,rho0,rho1,rho2,rho3,computeMs,analysisCpuMs,periodMs,processCpuMs,heapBytes,received,accepted,missingPackets,winner,controlOutput,controlReason,inputMs,expiresMs,controlRevision\n") }
    fun start(){ writer.scheduleWithFixedDelay({try{record()}catch(_:Exception){/* evidence failure must not halt receiving */}},0,200,TimeUnit.MILLISECONDS) }
    private fun record() {
        val s=session.snapshot();val r=engine.snapshot();val now=SystemClock.elapsedRealtime()
        val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val json=JSONObject().put("elapsedMs",now).put("raw",r.raw.name).put("stable",r.stable.name).put("reason",r.reason)
            .put("windowSeconds",engine.windowSeconds).put("updateMs",engine.updateMs)
            .put("streamStartedNs",s.streamStartedNs).put("decidedMs",r.decidedMs)
            .put("analysisCount",r.analysisCount).put("windowEnd",r.windowEnd).put("generation",r.generation)
            .put("scores",org.json.JSONArray(r.scores)).put("computeMs",r.computeMs).put("analysisCpuMs",r.cpuMs).put("periodMs",r.periodMs)
            .put("processCpuMs",Process.getElapsedCpuTime()).put("heapBytes",heap).put("received",s.received).put("accepted",s.accepted)
            .put("missingPackets",s.missingPackets).put("rejected",JSONObject(s.rejected)).put("absolute",r.config.absolute).put("difference",r.config.difference)
            .put("winner",r.winner.name).put("controlOutput",r.control.output.name).put("controlReason",r.control.reason)
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
                .put("transportMode",transport.mode).put("transportError",transport.error).put("sentCommand",transport.sent?.command?.name)
                .put("sentNs",transport.sent?.sentNs).put("sentSerial",transport.sent?.serial)
                .put("actualUnityBehaviorVerified",false)
            if(link!=null){
                json.put("unityTarget",link.target).put("unitySentPackets",link.sentPackets).put("unityLastSentHex",link.lastSentHex)
                    .put("unitySenderThread",link.senderThread)
                    .put("unityFeedbackOnline",link.feedbackOnline).put("unityFeedbackReceivedNs",link.receivedNs)
                    .put("unityValidStatusPackets",link.validPackets).put("unityRejectedStatus",JSONObject(link.rejected)).put("unityLinkError",link.error)
                link.feedback?.let { u->json.put("unityFeedback",JSONObject().put("flags",u.flags).put("action",u.action)
                    .put("linearMmS",u.linearMmS).put("angularMilliRadS",u.angularMilliRadS).put("xCm",u.xCm).put("zCm",u.zCm)
                    .put("speedPercent",u.speedPercent).put("timestampMs",u.timestampMs).put("emergency",u.emergency).put("fault",u.fault)) }
            }else json.put("mockApplied",c.simulatedUnity.current().name)
        }
        val output=latest.startWrite()
        try{output.write(json.toString().toByteArray());latest.finishWrite(output)}catch(e:Exception){latest.failWrite(output);throw e}
        if(rows<6000){
            val fields=listOf(now,r.analysisCount,r.windowEnd,r.raw.name,r.stable.name,r.reason.replace(',',';'))+
                (0..3).map{r.scores.getOrNull(it)?:""}+listOf(r.computeMs,r.cpuMs,r.periodMs,Process.getElapsedCpuTime(),heap,s.received,s.accepted,s.missingPackets,
                    r.winner.name,r.control.output.name,r.control.reason.replace(',',';'),r.inputMs?:"",r.control.expiresMs?:"",r.control.revision)
            csv.write(fields.joinToString(",")+"\n");csv.flush();rows++
        }
    }
    fun close(){writer.execute{try{record()}finally{csv.close()}};writer.shutdown()}
}
