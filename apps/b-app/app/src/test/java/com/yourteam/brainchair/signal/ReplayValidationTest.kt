package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** Labels live ONLY in this test evaluator; production receives ordinary v2 bytes. */
class ReplayValidationTest {
    data class Segment(val id:Int,val split:String,val label:String,val source:String,val offset:Int,val count:Int)
    data class Row(val scenario:String,val split:String,val segment:Int,val truth:String,val ms:Long,val r:RecognitionSnapshot)
    private val dir=requireNotNull(File(requireNotNull(System.getProperty("brainchair.fixtures"))).parentFile).resolve("stage25")
    private lateinit var raw:FloatArray
    private lateinit var names:List<String>
    private lateinit var segments:List<Segment>
    private val rows=mutableListOf<Row>()
    private val checks=mutableListOf<String>()
    private fun check(name:String,value:Boolean){checks.add("$name\t$value");assertTrue(name,value)}
    private inner class Rig(val scenario:String,val split:String,val config:RecognitionConfig) {
        var now=0L;var index=0L;var seq=2
        val session=StreamSession({now})
        val engine=SignalEngine(session,config,clock={now}) // CPU measured separately on installed Android APK.
        val output=mutableListOf<Row>()
        init{session.accept(Fixtures.info(names=names),"fixture");engine.tick()}
        fun restart(){index=0;seq=2;session.accept(Fixtures.info(names=names),"fixture");engine.tick();check("$scenario restart clears",engine.snapshot().stable==Direction.STOP)}
        fun feed(s:Segment, noise:Double=0.0, zero:Boolean=false, skipAt:Int=-1){
            val rng=Random(2500L+s.id)
            for(off in 0 until s.count step 5){
                val p=Fixtures.le(20+5*62*4).putLong(index).putLong(1_000_000+index*1000).putShort(5).putShort(62)
                repeat(5*62){k->p.putFloat(if(zero)0f else (raw[(s.offset+off)*62+k]+noise*rng.nextGaussian()).toFloat())}
                if(off!=skipAt)session.accept(Fixtures.packet(2,seq,p.array()),"fixture")
                seq++;index+=5;now+=5
                if(index%200==0L){engine.tick();val row=Row(scenario,split,s.id,s.label,now,engine.snapshot());output.add(row);rows.add(row)}
            }
        }
        fun stop(){session.accept(Fixtures.stop(seq,index),"fixture");check("$scenario STREAM_STOP",engine.snapshot().stable==Direction.STOP);engine.close()}
    }
    private fun csv(file:String,data:List<Row>){
        File(dir,file).bufferedWriter().use { w->
            w.write("scenario,split,segment,truth,ms,windowEnd,rhoForward,rhoLeft,rhoRight,rhoBackward,argmax,raw,stable,reason,computeMs,cpuMs\n")
            data.forEach { q->val r=q.r;val scores=(0..3).map{r.scores.getOrNull(it)?.toString()?:""}
                val arg=if(r.scores.isEmpty())"NONE" else Direction.values()[r.scores.indices.maxByOrNull{r.scores[it]}!!].name
                w.write((listOf(q.scenario,q.split,q.segment,q.truth,q.ms,r.windowEnd)+scores+listOf(arg,r.raw,r.stable,r.reason.replace(',',';'),r.computeMs,r.cpuMs)).joinToString(",")+"\n")
            }
        }
    }
    private fun streams(split:String,config:RecognitionConfig,prefix:String):List<Row> {
        val start=rows.size
        for(label in listOf("forward","left","right","backward","stop")){
            val list=segments.filter{it.split==split && it.label==label};val rig=Rig("$prefix-$label",split,config)
            repeat(3){list.forEach{rig.feed(it)}};rig.stop()
        }
        return rows.subList(start,rows.size).toList()
    }
    private fun gateCounts(data:List<Row>,config:RecognitionConfig):IntArray {
        var gate=DecisionGate(config);var scenario="";val counts=IntArray(4) // correct motion, wrong motion, rest motion, eligible motion
        data.forEach { q->
            if(q.scenario!=scenario){gate=DecisionGate(config);scenario=q.scenario}
            if(q.r.scores.isEmpty()){gate.reset();return@forEach}
            val stable=gate.evaluate(q.r.scores.toDoubleArray(),q.ms).stable.name.lowercase()
            if(q.truth!="stop"){counts[3]++;if(stable==q.truth)counts[0]++ else if(stable!="stop")counts[1]++}
            else if(stable!="stop")counts[2]++
        };return counts
    }
    @Test fun actualProductionReplayCalibrationAndHeldOutEvaluation() {
        assumeTrue("Run scripts/validate-stage25.ps1 to supply read-only APK fixtures",File(dir,"segments.bin").exists())
        names=File(dir,"names.txt").readText().trim().split(',')
        segments=File(dir,"plan.tsv").readLines().map{val x=it.split('\t');Segment(x[0].toInt(),x[1],x[2],x[3],x[4].toInt(),x[5].toInt())}
        val b=ByteBuffer.wrap(File(dir,"segments.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN);raw=FloatArray(b.remaining()/4){b.float}
        // Diagnostic aligned windows use the same filter and CCA, but intentionally no engine warm-up/vote.
        File(dir,"aligned.csv").bufferedWriter().use { w->
            w.write("segment,split,truth,source,rhoForward,rhoLeft,rhoRight,rhoBackward\n")
            for(s in segments){val f=Bandpass(1000);val picks=SignalEngine.CHANNELS.map{names.indexOf(it)}
                val x=Array(9){DoubleArray(4000)};repeat(4000){n->repeat(9){c->x[c][n]=f.sample(raw[(s.offset+n)*62+picks[c]].toDouble(),c)}}
                w.write("${s.id},${s.split},${s.label},${s.source},${Cca(1000).scores(x).joinToString(",")}\n")
            }
        }
        // A 4s press cannot supply 0.5s warmup + 4s window. No repeats concealed as fresh data.
        segments.forEach { s->val r=Rig("single-${s.id}",s.split,RecognitionConfig());r.feed(s);check("single ${s.id} not ready",r.output.all{it.r.stable==Direction.STOP && it.r.scores.isEmpty()});r.stop() }
        val cal=streams("calibration",RecognitionConfig(),"cal-default")
        // Frozen search rule uses ONLY calibration stream scores, actual DecisionGate, unchanged votes/debounce.
        var selected=RecognitionConfig();var best=-1
        File(dir,"grid.csv").bufferedWriter().use { w->
            w.write("absolute,difference,correct,wrong,restMotion,eligible\n")
            for(ai in 45..85 step 2)for(di in 5..35 step 2){val cfg=RecognitionConfig(ai/100.0,di/100.0);val c=gateCounts(cal,cfg)
                w.write("${cfg.absolute},${cfg.difference},${c.joinToString(",")}\n")
                if(c[1]==0 && c[2]==0 && (c[0]>best || (c[0]==best && cfg.absolute+cfg.difference>selected.absolute+selected.difference))){best=c[0];selected=cfg}
            }
        }
        File(dir,"selected.json").writeText("""{"absolute":${selected.absolute},"difference":${selected.difference},"votes":3,"debounceMs":300,"calibrationCorrect":$best,"deploymentApproved":false,"rule":"zero calibration wrong-motion and rest-motion; maximize correct motion, ties prefer larger threshold sum"}""")
        // Evaluation is first accessed for tuning-independent scoring only after configuration is frozen.
        streams("evaluation",RecognitionConfig(),"eval-default")
        streams("calibration",selected,"cal-selected")
        streams("evaluation",selected,"eval-selected")
        streams("evaluation",RecognitionConfig(.57,.245),"eval-reference")
        // A-faithful 4-trial hard wrap mixes split groups: descriptive only, NOT held-out accuracy.
        for(label in listOf("forward","left","right","backward","stop")){
            val rig=Rig("a-wrap-$label","mixed-descriptive",selected);repeat(2){segments.filter{it.label==label}.forEach{rig.feed(it)}};rig.stop()
            val baseline=Rig("a-wrap-default-$label","mixed-descriptive",RecognitionConfig());repeat(2){segments.filter{it.label==label}.forEach{baseline.feed(it)}};baseline.stop()
        }
        val eval=segments.filter{it.split=="evaluation"}
        val switch=Rig("continuous-switch","evaluation",selected)
        for(label in listOf("forward","left","right","backward","stop"))eval.filter{it.label==label}.let{list->repeat(if(label=="stop")2 else 1){list.forEach{switch.feed(it)}}}
        switch.stop()
        val switchDefault=Rig("continuous-switch-default","evaluation",RecognitionConfig())
        for(label in listOf("forward","left","right","backward","stop"))eval.filter{it.label==label}.let{list->repeat(if(label=="stop")2 else 1){list.forEach{switchDefault.feed(it)}}}
        switchDefault.stop()
        // Protocol-compliant release / new INFO between intentions, unlike the unsignalled switch above.
        for(label in listOf("forward","left","right","backward","stop")){
            val r=Rig("released-switch-$label","evaluation",selected);eval.filter{it.label==label}.forEach{r.feed(it)};r.stop()
        }
        for(level in listOf(10.0,50.0,100.0)){
            val r=Rig("noise-$level","evaluation",selected);repeat(3){eval.filter{it.label=="forward"}.forEach{r.feed(it,noise=level)}};r.stop()
        }
        val bad=Rig("degraded-noise","evaluation",selected);repeat(3){eval.filter{it.label=="forward"}.forEach{bad.feed(it,noise=1000.0)}};bad.stop()
        val zero=Rig("flat-signal","evaluation",selected);repeat(2){zero.feed(eval.first(),zero=true)};check("flat stops",zero.output.all{it.r.stable==Direction.STOP});zero.stop()
        val fault=Rig("gap-timeout-restart","evaluation",selected);eval.filter{it.label=="forward"}.forEach{fault.feed(it)}
        fault.feed(eval.first(),skipAt=0);check("gap invalidates",fault.output.takeLast(20).all{it.r.stable==Direction.STOP})
        fault.now+=1001;check("timeout clears",fault.engine.snapshot().stable==Direction.STOP && fault.session.health().info==null)
        fault.restart();eval.filter{it.label=="right"}.forEach{fault.feed(it)};check("restart computes new windows",fault.engine.snapshot().scores.size==4);fault.stop()
        csv("windows.csv",rows);File(dir,"checks.tsv").writeText(checks.joinToString("\n"))
        println("REAL_REPLAY selected=$selected checks=${checks.size} windows=${rows.size}; no real-device claim")
    }
}
