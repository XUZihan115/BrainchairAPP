package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.*

class SignalTest {
    private val dir=File(requireNotNull(System.getProperty("brainchair.fixtures")))
    private fun floats(name:String):FloatArray { val b=ByteBuffer.wrap(File(dir,"$name.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.remaining()/4){b.float} }
    private fun data(seq:Int, first:Long, values:FloatArray, channels:Int=9, rate:Int=1000):ByteArray {
        val p=Fixtures.le(20+values.size*4).putLong(first).putLong(1_000_000+first*1_000_000/rate).putShort((values.size/channels).toShort()).putShort(channels.toShort())
        values.forEach{p.putFloat(it)};return Fixtures.packet(2,seq,p.array())
    }
    @Test fun filterImpulseMatchesScipyAtSixSampleRates() {
        for(fs in listOf(100,250,500,1000,2000,4000)) {
            val expected=ByteBuffer.wrap(File(dir,"impulse-$fs.f64").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val filter=Bandpass(fs);var maxError=0.0
            repeat(1024){ n->maxError=max(maxError,abs(filter.sample(if(n==0)1.0 else 0.0,0)-expected.double)) }
            println("FILTER_ORACLE fs=$fs maxAbsError=$maxError");assertTrue(maxError<1e-10)
        }
    }
    @Test fun eightCasesMatchIndependentSvdAndSosOracle() {
        for(name in listOf("forward","left","right","backward","noise","weak","ambiguous","flat")) {
            val raw=floats(name);val f=Bandpass(1000);val filtered=Array(9){DoubleArray(6000)}
            // Packet boundaries deliberately irregular; single continuous state.
            var at=0
            for(chunk in generateSequence(7){if(it==7)13 else 7}) {
                for(n in at until min(6000,at+chunk))for(c in 0..8)filtered[c][n]=f.sample(raw[n*9+c].toDouble(),c)
                at+=chunk;if(at>=6000)break
            }
            val lines=File(dir,"$name.txt").readLines()
            val expected=lines[0].split(',').map{it.toDouble()}.toDoubleArray()
            val checkpoints=lines[1].split(',').map{it.toDouble()}
            var index=0;for(n in listOf(0,1,4,199,499,999,3999,5999))for(c in 0..8)assertEquals(checkpoints[index++],filtered[c][n],1e-8)
            val actual=Cca(1000).scores(Array(9){filtered[it].copyOfRange(2000,6000)})
            assertArrayEquals(expected,actual,1e-8)
            val gate=DecisionGate(RecognitionConfig());var decision=gate.evaluate(actual,0)
            for(ms in listOf(200L,400L))decision=gate.evaluate(actual,ms)
            val expectedDirection=Direction.values().find{it.name.equals(name,true)}?:Direction.STOP
            assertEquals(expectedDirection,decision.stable)
            println("CCA_ORACLE $name scores=${actual.joinToString()} stable=${decision.stable}")
        }
    }
    @Test fun rankDeficientAndFlatMatricesAreHandledWithoutInventedRank() {
        val x=Array(9){DoubleArray(4000){n->sin(2*PI*12*n/1000)}}
        val c=Cca(1000);val score=c.scores(x)
        assertTrue(score[0]>.999999);assertTrue(score.drop(1).all{it<.1})
        assertArrayEquals(DoubleArray(4),c.scores(Array(9){DoubleArray(4000){42.0}}),0.0)
    }
    @Test fun gateVotesDebounceStopsAndConfigurationBounds() {
        val g=DecisionGate(RecognitionConfig(votes=3,debounceMs=500))
        val forward=doubleArrayOf(.9,.2,.2,.2);val left=doubleArrayOf(.2,.9,.2,.2)
        assertEquals(Direction.STOP,g.evaluate(forward,0).stable)
        g.evaluate(forward,200);assertEquals(Direction.STOP,g.evaluate(forward,400).stable)
        assertEquals(Direction.FORWARD,g.evaluate(forward,600).stable)
        assertEquals(Direction.STOP,g.evaluate(left,800).stable)
        g.evaluate(left,1000);g.evaluate(left,1200);assertEquals(Direction.LEFT,g.evaluate(left,1400).stable)
        assertEquals(Direction.STOP,g.evaluate(doubleArrayOf(.1,.1,.1,.1),1401).stable)
        assertEquals(Direction.STOP,g.evaluate(doubleArrayOf(.9,.89,.1,.1),1600).stable)
        assertEquals(Direction.STOP,g.evaluate(doubleArrayOf(Double.NaN,.1,.1,.1),1800).stable)
        for(v in listOf(Double.NaN,-.1,1.1))try{RecognitionConfig(absolute=v);fail()}catch(_:IllegalArgumentException){}
    }
    private inner class Rig {
        var now=0L;val session=StreamSession({now});val engine=SignalEngine(session,clock={now})
        var first=0L;var seq=2;var names=SignalEngine.CHANNELS.reversed()
        fun start(){session.accept(Fixtures.info(names=names),"A");first=0;seq=2;engine.tick()}
        fun feed(raw:FloatArray,ticks:Boolean=true){
            for(n in 0 until raw.size/9 step 5){
                val block=FloatArray(45){k->raw[(n+k/9)*9+SignalEngine.CHANNELS.indexOf(names[k%9])]}
                session.accept(data(seq++,first,block),"A");first+=5;now+=5
                if(ticks && first%200==0L)engine.tick()
            }
        }
    }
    @Test fun streamingSelectsNamesKeepsStateStopsAndRestarts() {
        val r=Rig();r.start();r.feed(floats("left"))
        assertEquals(Direction.LEFT,r.engine.snapshot().stable)
        val before=r.engine.snapshot().analysisCount
        repeat(4){r.engine.tick()};assertEquals(before,r.engine.snapshot().analysisCount) // no voting on reused window
        r.session.accept(Fixtures.stop(r.seq++,r.first),"A");assertEquals(Direction.STOP,r.engine.snapshot().stable)
        r.start();r.feed(floats("right"));assertEquals(Direction.RIGHT,r.engine.snapshot().stable)
        r.feed(floats("noise"));assertEquals(Direction.STOP,r.engine.snapshot().stable)
        r.engine.close()
    }
    @Test fun gapTimeoutAndConfigurationChangeInvalidateDirection() {
        val r=Rig();r.start();r.feed(floats("forward"));assertEquals(Direction.FORWARD,r.engine.snapshot().stable)
        r.first+=5;r.seq++
        r.session.accept(data(r.seq++,r.first,FloatArray(45)),"A");r.first+=5
        assertEquals(Direction.STOP,r.engine.snapshot().stable);r.engine.tick()
        assertEquals(Direction.STOP,r.engine.snapshot().stable)
        r.start();r.feed(floats("backward"));assertEquals(Direction.BACKWARD,r.engine.snapshot().stable)
        r.engine.configure(RecognitionConfig(absolute=1.0));assertEquals(Direction.STOP,r.engine.snapshot().stable)
        r.now+=1001;assertEquals(Direction.STOP,r.engine.snapshot().stable);assertNull(r.session.health().info)
        r.engine.close()
    }
    @Test fun missingChannelsAndOverwrittenRawBufferStop() {
        val r=Rig();r.session.accept(Fixtures.info(names=listOf("Oz","O1")),"A");r.engine.tick()
        assertTrue(r.engine.snapshot().reason.contains("缺少"))
        r.start();r.feed(floats("forward"));assertEquals(Direction.FORWARD,r.engine.snapshot().stable)
        r.feed(floats("left"),ticks=false);r.engine.tick()
        assertEquals(Direction.STOP,r.engine.snapshot().stable) // overwritten cursor -> filter restart, not reused history
        r.engine.close()
    }
    @Test fun stalledCcaDoesNotBlockUdpAndWatchdogDropsOldDecision() {
        val session=StreamSession();val calls=AtomicInteger();val stalled=CountDownLatch(1);val release=CountDownLatch(1)
        val engine=SignalEngine(session,scoreFunction={c,x->if(calls.incrementAndGet()==4){stalled.countDown();release.await(4,TimeUnit.SECONDS)};c.scores(x)})
        val udp=UdpReceiver(session,0,0);udp.start();engine.start();val sock=DatagramSocket()
        fun send(a:ByteArray){sock.send(DatagramPacket(a,a.size,InetAddress.getLoopbackAddress(),udp.boundDataPort))}
        val raw=floats("forward");val begin=System.nanoTime();var sawStable=false;var sawWatchdog=false
        try {
            send(Fixtures.info(names=SignalEngine.CHANNELS))
            repeat(1600){k->
                val deadline=begin+k*5_000_000L;while(System.nanoTime()<deadline)LockSupport.parkNanos(deadline-System.nanoTime())
                send(data(k+2,k*5L,FloatArray(45){raw[((k*5+it/9)%6000)*9+it%9]}))
                if(engine.snapshot().stable==Direction.FORWARD)sawStable=true
                val stopped=engine.snapshot()
                if(stalled.count==0L && stopped.stable==Direction.STOP && stopped.control.output==Direction.STOP &&
                    (stopped.reason.contains("无新有效") || stopped.reason.contains("分析输入")))sawWatchdog=true
                if(k==1450)release.countDown()
            }
            Thread.sleep(100)
            assertTrue("never produced stable direction",sawStable);assertTrue("watchdog did not invalidate stalled CCA",sawWatchdog)
            assertEquals(1601L,session.snapshot().accepted);assertEquals(0L,session.snapshot().missingPackets)
            println("CONCURRENT_UDP accepted=${session.snapshot().accepted} missing=${session.snapshot().missingPackets} stalledCCA=true watchdogStopped=$sawWatchdog")
        }finally{release.countDown();engine.close();udp.close();sock.close()}
    }
}
