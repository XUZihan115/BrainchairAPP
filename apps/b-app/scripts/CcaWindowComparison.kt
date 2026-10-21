package com.yourteam.brainchair.experiment

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import com.yourteam.brainchair.control.*
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs

/** Offline harness only. The launcher copies the production classes with explicit experiment timing.
 * Labels/segment offsets are evaluator metadata; only ordinary v2 EEG bytes reach B algorithms.
 */
object CcaWindowComparison {
    private fun le(n:Int)=ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun packet(type:Int,seq:Int,payload:ByteArray):ByteArray {
        val bytes=le(16+payload.size+4).putShort(0x47ee.toShort()).put(2).put(type.toByte())
            .putInt(0x45454731).putInt(seq).putInt(payload.size).put(payload)
        val crc=CRC32().apply{update(bytes.array(),0,bytes.position())}.value
        return bytes.putInt(crc.toInt()).array()
    }
    private fun info(names:List<String>):ByteArray {
        val n=names.joinToString(",").toByteArray()
        return packet(1,1,le(10+n.size).putInt(1000).putShort(names.size.toShort()).put(1).put(0).putShort(n.size.toShort()).put(n).array())
    }
    private fun cell(x:Any?)="\""+(x?.toString()?:"").replace("\"","\"\"")+"\""
    private fun line(vararg cells:Any?)=cells.joinToString(",",transform=::cell)+"\n"
    @JvmStatic fun main(args:Array<String>) {
        val dir=File(args[0]);val out=File(args[1]);out.mkdirs()
        val lengths=args[2].split(',').map{it.toInt()}
        val config=RecognitionConfig()
        out.resolve("config.json").writeText("""{"absolute":${config.absolute},"difference":${config.difference},"votes":${config.votes},"debounceMs":${config.debounceMs},"warmupMs":500,"stepMs":250,"deadlineMs":4000,"deadlineExclusive":true}""")
        val names=dir.resolve("names.txt").readText().trim().split(',')
        val picks=SignalEngine.CHANNELS.map{names.indexOf(it)};check(picks.all{it>=0})
        val bytes=ByteBuffer.wrap(dir.resolve("segments.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val raw=FloatArray(bytes.remaining()/4){bytes.float}
        val cpu=ManagementFactory.getThreadMXBean()
        var rows=0;var checks=0;var numericChecks=0;var maxScoreDelta=0.0
        out.resolve("events.csv").bufferedWriter().use { log->
            log.write("window_ms,trial,split,truth,source,event,time_ms,analysis_count,window_first_index,window_end_index,rho_forward,rho_left,rho_right,rho_backward,winner,raw,stable,output,reason,compute_ms,cpu_ms,stop_processing_ms\n")
            for(windowMs in lengths)for(plan in dir.resolve("plan.tsv").readLines()) {
                val p=plan.split('\t');val id=p[0].toInt();val split=p[1];val truth=p[2];val source=p[3]
                val offset=p[4].toInt();val count=p[5].toInt();check(count==4000)
                var now=0L
                val session=StreamSession({now},nanoClock={now*1_000_000})
                val authority=ControlAuthority({now},{now*1_000_000})
                val boundary:(String)->Unit={reason->val h=session.health();authority.boundary(h.generation,h.streamId,reason)}
                session.addInvalidationListener(boundary)
                val engine=SignalEngine(session,RecognitionConfig(),clock={now},
                    cpuClock={cpu.currentThreadCpuTime},onDecision=authority::recognition,windowSeconds=windowMs/1000.0,updateMs=250)
                // A direct replay through the SAME production math audits the schedule/data ordering,
                // continuous filter state and exact raw indices of every analyzed window.
                val filter=Bandpass(1000);val filtered=Array(9){DoubleArray(count)}
                repeat(count){n->repeat(9){c->filtered[c][n]=filter.sample(raw[(offset+n)*names.size+picks[c]].toDouble(),c)}}
                val oracle=Cca(1000,windowMs/1000.0)
                fun record(event:String,stopMs:Double?=null) {
                    val r=engine.eventSnapshot();val final=authority.snapshot()
                    val scored=r.scores.size==4
                    log.write(line(windowMs,id,split,truth,source,event,now,r.analysisCount,
                        if(scored)r.windowEnd-windowMs else null,if(scored)r.windowEnd else null,
                        r.scores.getOrNull(0),r.scores.getOrNull(1),r.scores.getOrNull(2),r.scores.getOrNull(3),
                        if(scored)r.winner else "NO_WINDOW",r.raw,r.stable,final.command,r.reason,r.computeMs,r.cpuMs,stopMs))
                    rows++
                }
                session.accept(info(names),"offline-A");engine.tick()
                check(authority.snapshot().command==ControlCommand.STOP);record("INFO");checks++
                var seq=2
                for(first in 0 until count step 5) {
                    now=first+5L // exclusive sample endpoint; no future samples available at a decision
                    val b=le(20+5*names.size*4).putLong(first.toLong()).putLong(1_000_000L+first*1000)
                        .putShort(5).putShort(names.size.toShort())
                    repeat(5*names.size){k->b.putFloat(raw[(offset+first)*names.size+k])}
                    session.accept(packet(2,seq++,b.array()),"offline-A")
                    // STOP has priority at exactly 4000ms. Do not compute an end-of-signal direction.
                    if(now%250==0L && now<4000) {
                        engine.tick();val r=engine.eventSnapshot()
                        if(r.scores.isNotEmpty()) {
                            check(now>=500+windowMs && r.windowEnd==now)
                            val begin=r.windowEnd.toInt()-windowMs
                            check(begin>=500 && r.windowEnd<=count)
                            val direct=oracle.scores(Array(9){c->filtered[c].copyOfRange(begin,r.windowEnd.toInt())})
                            for(c in 0..3) {
                                val delta=abs(direct[c]-r.scores[c]);maxScoreDelta=maxOf(maxScoreDelta,delta)
                                check(delta<1e-10){"continuous-engine/direct mismatch $windowMs/$id/$now/$c: $delta"}
                            }
                            numericChecks++;record("ANALYSIS")
                        }else {check(now<500+windowMs);record("NOT_READY")}
                        checks++
                    }
                }
                check(session.snapshot().accepted==801L && session.snapshot().rejected.isEmpty())
                val expected=(250..3750 step 250).count{it>=windowMs+500}
                check(engine.eventSnapshot().analysisCount==expected.toLong());checks++
                record("DEADLINE_BEFORE_STOP")
                val begin=System.nanoTime()
                session.accept(packet(3,seq,le(9).putLong(4000).put(1).array()),"offline-A")
                val stopProcessing=(System.nanoTime()-begin)/1e6
                check(authority.snapshot().command==ControlCommand.STOP)
                check(engine.eventSnapshot().raw==Direction.STOP && engine.eventSnapshot().stable==Direction.STOP)
                check(engine.eventSnapshot().scores.isEmpty() && engine.eventSnapshot().windowEnd==0L)
                check(session.health().info==null && session.snapshot().buffered==0)
                record("STREAM_STOP",stopProcessing);checks++
                now+=250;engine.tick();check(authority.snapshot().command==ControlCommand.STOP)
                record("AFTER_STOP");checks++
                engine.close();session.removeInvalidationListener(boundary)
            }
        }
        out.resolve("harness-checks.json").writeText("""{"checks":$checks,"passed":$checks,"eventRows":$rows,"numericWindowComparisons":$numericChecks,"maxScoreDelta":$maxScoreDelta,"syntheticEegUsed":false,"asynchronousNetworkTest":false}""")
        println("Offline real EEG: $rows events, $numericChecks direct/engine comparisons, $checks checks passed")
    }
}
