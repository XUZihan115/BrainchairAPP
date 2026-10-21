package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.TimeUnit

class Stage3IntegrationTest {
    @Test fun pacedAtoBRealCcaToUdpPeerContinuousRenewalSwitchAndStop() {
        Stage3Peer().use { peer->
            val session=StreamSession();val link=UnityUdpLink("127.0.0.1",peer.port,0)
            val runtime=ControlRuntime(session,unityLink=link);val udp=UdpReceiver(session,0,0)
            val timeline=StringBuilder("event,offsetMs,detail\n");val start=System.nanoTime()
            fun event(name:String,detail:Any?=""){timeline.append("$name,${(System.nanoTime()-start)/1e6},$detail\n")}
            fun pace(due:Long){while(System.nanoTime()<due)TimeUnit.NANOSECONDS.sleep(minOf(1_000_000L,due-System.nanoTime()).coerceAtLeast(1))}
            try {
                udp.start();runtime.start();peer.feedbackPort=link.boundFeedbackPort
                DatagramSocket().use { a->
                    fun send(bytes:ByteArray){a.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),udp.boundDataPort))}
                    send(Fixtures.info(names=Stage27IntegrationTest.names));event("INFO")
                    var firstRaw=false;var firstStable=false
                    val flowStart=System.nanoTime()
                    repeat(1200){k->
                        pace(flowStart+k*5_000_000L);send(Stage3Signals.wave(k+2L,k*5L,1_800_000_000_000_000L))
                        if(!firstRaw && runtime.engine.eventSnapshot().raw==Direction.FORWARD){firstRaw=true;event("first gated CCA")}
                        if(!firstStable && peer.records.any{it.code==1}){firstStable=true;event("peer first FORWARD")}
                    }
                    assertTrue(firstRaw);assertTrue(firstStable)
                    val moving=peer.records.filter{it.code==1}
                    assertTrue("continuous motion heartbeats",moving.size>=7)
                    assertTrue("no full re-wait after first decision",moving.zipWithNext{a1,b->(b.ns-a1.ns)/1e6}.all{it<500})
                    val cut=peer.records.size;val stopNs=System.nanoTime();send(Fixtures.stop(1202,6000));event("A STOP sent")
                    Stage3Peer.await("STOP after stream stop"){peer.records.drop(cut).any{it.code==5}}
                    val receipt=peer.records.drop(cut).first{it.code==5};val audit=session.snapshot().stopAudits.last()
                    event("STOP received by UDP peer","A-to-peer ms=${(receipt.ns-stopNs)/1e6}; B invalidation ms=${audit.receiveToInvalidationMs}")
                    assertEquals(ControlCommand.STOP,runtime.authority.snapshot().command)
                    assertTrue("B stop emission prompt",(receipt.ns-stopNs)<300_000_000)
                    // A starts a fresh direction, no old window or old async result is reusable.
                    send(Fixtures.info(names=Stage27IntegrationTest.names));event("new LEFT INFO")
                    val second=System.nanoTime()
                    repeat(750){k->pace(second+k*5_000_000L);send(Stage3Signals.wave(k+2L,k*5L,1_800_000_010_000_000L,8.57))}
                    event("LEFT diagnostic",runtime.engine.eventSnapshot().toString().replace(',',';')+" / "+session.snapshot().toString().replace(',',';'))
                    Stage3Peer.await("new LEFT recognized ${runtime.engine.eventSnapshot()} / ${session.snapshot()}"){peer.records.drop(cut).any{it.code==3}}
                    assertTrue(peer.records.drop(cut).none{it.code==1})
                    event("peer LEFT after fresh warmup")
                    val timeoutStart=System.nanoTime();val before=peer.records.size
                    Stage3Peer.await("loss stops real UDP",2000){peer.records.drop(before).any{it.code==5}}
                    event("data timeout STOP","silence ms=${(System.nanoTime()-timeoutStart)/1e6}")
                    assertEquals(ControlCommand.STOP,runtime.authority.snapshot().command)
                    assertTrue(session.snapshot().blockPackets>=1950)
                    Stage3Peer.evidence("paced-command-datagrams.csv","command,receivedNs,hex\n"+peer.records.joinToString("\n"){"${it.code},${it.ns},${it.hex}"}+"\n")
                }
            }finally{event("end");Stage3Peer.evidence("paced-udp-timeline.csv",timeline.toString());udp.close();runtime.close()}
        }
    }
}
