package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test

/** Run this identical fixture against 6064d5f and the fix. It does not assert acceptance of
 * the baseline; writes actual outbound UDP to expose its active STOP after one gate rejection. */
class ControlJitterProbeTest {
    @Test fun oneRejectedWindowActualWireTimeline() {
        var now=0L
        val a=ControlAuthority({now},{now*1_000_000});a.boundary(1,1,"INFO")
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlDispatcher(a,link,{now*1_000_000}).use { delivery->
                val gate=DecisionGate(RecognitionConfig());val local=RecognitionControl()
                val rows=StringBuilder("atMs,top,gap,raw,stable,final,actualUdpCode,reason\n")
                repeat(9){index->
                    now=index*250L
                    val scores=if(index==3)doubleArrayOf(.3,.2,.1,.1) else doubleArrayOf(.9,.1,.1,.1)
                    val g=gate.evaluate(scores,now);local.accept(g.raw,g.stable,now,now)
                    a.recognition(RecognitionSnapshot(scores.toList(),g.raw,g.stable,g.reason,generation=1,
                        windowEnd=3000+index*250L,decidedMs=now,analysisCount=index+1L,inputMs=now,control=local.state))
                    delivery.flush();Stage3Peer.await("probe UDP"){peer.records.size>index}
                    rows.append("$now,${scores.maxOrNull()},${scores.sortedDescending().let{it[0]-it[1]}},${g.raw},${g.stable},${a.snapshot().command},${peer.records.last().code},${a.snapshot().reason}\n")
                }
                a.boundary(2,1,"STREAM_STOP");delivery.flush()
                Stage3Peer.await("probe final STOP"){peer.records.last().code==5}
                assertEquals(5,peer.records.last().code)
                Stage3Peer.evidence("single-reject-probe.csv",rows.toString())
            }
        }
    }
}
