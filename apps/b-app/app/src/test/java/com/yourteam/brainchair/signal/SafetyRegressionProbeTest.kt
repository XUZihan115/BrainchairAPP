package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Compiles unmodified against 1fed131 and Stage 2.6; injected scores isolate lifecycle defects. */
class SafetyRegressionProbeTest {
    private val dir=requireNotNull(File(requireNotNull(System.getProperty("brainchair.fixtures"))).parentFile).resolve("stage26")
    private fun rawState(engine:SignalEngine):RecognitionSnapshot {
        val f=engine.javaClass.getDeclaredField("result");f.isAccessible=true;return f.get(engine) as RecognitionSnapshot
    }
    private fun output(r:RecognitionSnapshot):String = try {
        val c=r.javaClass.getMethod("getControl").invoke(r);c.javaClass.getMethod("getOutput").invoke(c).toString()
    }catch(_:NoSuchMethodException){r.stable.name} // Baseline has no final-control layer; report its stable output.
    private inner class Rig(val slow:Boolean=false) {
        var now=0L;var index=0L;var seq=2;var delayed=false
        val session=StreamSession({now})
        val engine=SignalEngine(session,RecognitionConfig(votes=1,debounceMs=0),clock={now},scoreFunction={_,_->
            if(slow && !delayed){delayed=true;repeat(240){feedBlock()}} // 1200ms pass while valid UDP data keep arriving.
            doubleArrayOf(.9,.1,.1,.1)
        })
        init {session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"A");engine.tick()}
        fun bytes()=Fixtures.block(seq,index,channels=9)
        fun feedBlock(){session.accept(bytes(),"A");seq++;index+=5;now+=5}
        fun fill(){repeat(920){feedBlock();if(index%200==0L)engine.tick()}}
    }
    @Test fun probeImmediateStopCorruptionAndExpiredComputation() {
        assumeTrue(dir.exists());val fixed=File(dir,"fixed.flag").exists();val lines=mutableListOf("case,beforePoll,afterPoll,expected,fixed")
        fun record(name:String,r:Rig,expected:String="STOP"){
            val immediate=output(rawState(r.engine));val after=output(r.engine.snapshot());lines.add("$name,$immediate,$after,$expected,$fixed")
            if(fixed){assertEquals(name,expected,immediate);assertEquals(name,expected,after)}
        }
        val stop=Rig();stop.fill();assertEquals(Direction.FORWARD,stop.engine.snapshot().stable)
        stop.session.accept(Fixtures.stop(stop.seq,stop.index),"A");record("STREAM_STOP synchronous",stop);stop.engine.close()
        val bad=Rig();bad.fill();val bytes=bad.bytes().apply{this[25]=(this[25].toInt() xor 1).toByte()}
        bad.session.accept(bytes,"A");record("CRC current source",bad)
        repeat(100){bad.feedBlock();if(bad.index%200==0L)bad.engine.tick()};if(fixed)assertEquals(Direction.STOP,bad.engine.snapshot().stable)
        bad.engine.close()
        val slow=Rig(true);slow.fill();record("1200ms stale window despite fresh incoming data",slow);slow.engine.close()
        val foreign=Rig();foreign.fill();foreign.session.accept(byteArrayOf(0),"foreign");record("foreign malformed ignored",foreign,"FORWARD");foreign.engine.close()
        val gap=Rig();gap.fill();gap.index+=5;gap.seq++;gap.feedBlock();record("gap synchronous",gap);gap.engine.close()
        val info=Rig();info.fill();info.session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"A");record("new INFO synchronous",info);info.engine.close()
        val timeout=Rig();timeout.fill();timeout.now+=1001;timeout.session.checkTimeout();record("data timeout event",timeout);timeout.engine.close()
        File(dir,"safety-probe.csv").writeText(lines.joinToString("\n")+"\n")
    }
}
