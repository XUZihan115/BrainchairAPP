package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Regression against the already exposed 18 real clips. NOT a new accuracy acceptance set. */
class Stage3ReplayTest {
    private fun csv(line:String):List<String> {
        val out=mutableListOf<String>();val cell=StringBuilder();var quote=false;var i=0
        while(i<line.length){val c=line[i++];when{
            c=='"' && quote && i<line.length && line[i]=='"'->{cell.append('"');i++}
            c=='"'->quote=!quote
            c==',' && !quote->{out.add(cell.toString());cell.setLength(0)}
            else->cell.append(c)
        }};out.add(cell.toString());return out
    }
    @Test fun officialTwoPointFiveSecondsMatchesEveryPriorRealWindowAndStops() {
        val dir=File(requireNotNull(System.getProperty("brainchair.stage3.data")));check(dir.resolve("segments.bin").isFile){"Real EEG data unavailable: $dir"}
        val expectedFile=File(requireNotNull(System.getProperty("brainchair.stage3.expected")));check(expectedFile.isFile)
        val lines=expectedFile.readLines();val keys=csv(lines.first())
        val expected=lines.drop(1).map{keys.zip(csv(it)).toMap()}.filter{it["window_ms"]=="2500" && it["event"]=="ANALYSIS"}
        assertEquals(72,expected.size)
        val names=dir.resolve("names.txt").readText().trim().split(',')
        val raw=ByteBuffer.wrap(dir.resolve("segments.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val samples=FloatArray(raw.remaining()/4){raw.float}
        val plan=dir.resolve("plan.tsv").readLines().map{it.split('\t')}
        val log=StringBuilder("trial,truth,timeMs,rhoForward,rhoLeft,rhoRight,rhoBack,raw,stable,final,udpCode,computeMs\n")
        var successful=0;var wrong=0;var falseRest=0;var maxDelta=0.0;var checks=0
        val latencies=mutableListOf<Long>();val begin=System.nanoTime()
        for(p in plan) {
            var now=0L;val id=p[0];val truth=p[2].uppercase();val offset=p[4].toInt();val count=p[5].toInt();assertEquals(4000,count)
            Stage3Peer().use { peer->
                val session=StreamSession({now});val link=UnityUdpLink("127.0.0.1",peer.port,0)
                ControlRuntime(session,clock={now},unityLink=link).use { r->
                    link.start();peer.feedbackEnabled=false
                    assertEquals(2.5,r.engine.windowSeconds,0.0);assertEquals(250L,r.engine.updateMs)
                    assertEquals(.65,r.engine.eventSnapshot().config.absolute,0.0);assertEquals(.15,r.engine.eventSnapshot().config.difference,0.0)
                    session.accept(Fixtures.info(names=names),"offline-A");r.engine.tick()
                    var seq=2;var firstCorrect:Long?=null
                    for(first in 0 until 4000 step 5) {
                        now=first+5L
                        val b=Fixtures.le(20+5*names.size*4).putLong(first.toLong()).putLong(1_000_000L+first*1000).putShort(5).putShort(names.size.toShort())
                        repeat(5*names.size){k->b.putFloat(samples[(offset+first)*names.size+k])}
                        session.accept(Fixtures.packet(2,seq++,b.array()),"offline-A")
                        if(now%250==0L && now<4000) {
                            r.engine.tick();val result=r.engine.eventSnapshot()
                            if(result.scores.isEmpty())continue
                            val old=expected.single{it["trial"]==id && it["time_ms"]==now.toString()}
                            val scoreKeys=listOf("rho_forward","rho_left","rho_right","rho_backward")
                            for(i in 0..3){val delta=kotlin.math.abs(result.scores[i]-old[scoreKeys[i]]!!.toDouble());maxDelta=maxOf(maxDelta,delta);assertTrue("score parity $id/$now/$i $delta",delta<1e-10)}
                            assertEquals(old["raw"],result.raw.name);assertEquals(old["stable"],result.stable.name)
                            val output=r.authority.snapshot().command
                            assertEquals(old["output"],output.name)
                            val before=peer.records.size;r.dispatcher.flush()
                            Stage3Peer.await("real-data decision reaches UDP peer"){peer.records.size>before}
                            assertEquals(output.code,peer.records.last().code);checks++
                            if(truth!="STOP" && output.name==truth && firstCorrect==null)firstCorrect=now
                            if(output.moving && output.name!=truth){wrong++;if(truth=="STOP")falseRest++}
                            log.append("$id,$truth,$now,${result.scores.joinToString(",")},${result.raw},${result.stable},$output,${peer.records.last().code},${result.computeMs}\n")
                        }
                    }
                    if(truth!="STOP" && firstCorrect!=null){successful++;latencies.add(firstCorrect!!)}
                    val before=peer.records.size;session.accept(Fixtures.stop(seq,4000),"offline-A");r.dispatcher.flush()
                    Stage3Peer.await("real-data final STOP datagram"){peer.records.size>before}
                    assertEquals(5,peer.records.last().code);assertEquals(Direction.STOP,r.engine.eventSnapshot().stable)
                }
            }
        }
        assertEquals(15,successful);assertEquals(0,wrong);assertEquals(0,falseRest);assertEquals(72,checks)
        Stage3Peer.evidence("real-2p5-regression.csv",log.toString())
        Stage3Peer.evidence("real-2p5-summary.txt","Previously exposed OpenBMI clips: 16 motion trials + 2 rest clips; no new calibration/independent acceptance.\n72 analysis windows match prior 2.5s scores, raw, stable, final; max score delta=$maxDelta\nCorrect stable motion before STOP=$successful/16; wrong output windows=$wrong; rest motion windows=$falseRest\nSuccessful trial latencies ms=$latencies; one failed motion trial remains rejected.\nWall ms=${(System.nanoTime()-begin)/1e6}; JVM unit test, not Android CPU measurement.\n")
    }
}
