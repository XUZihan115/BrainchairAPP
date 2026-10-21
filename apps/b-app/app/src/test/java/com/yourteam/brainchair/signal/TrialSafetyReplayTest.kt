package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Portable before/after harness. No fitting, labels never reach the production engine. */
class TrialSafetyReplayTest {
    data class Seg(val id:Int,val split:String,val truth:String,val offset:Int)
    private val fixtures=requireNotNull(File(requireNotNull(System.getProperty("brainchair.fixtures"))).parentFile)
    private val dir=fixtures.resolve("stage26")
    private lateinit var data:FloatArray
    private lateinit var names:List<String>
    private lateinit var log:java.io.BufferedWriter
    private fun finalOutput(r:RecognitionSnapshot):String = try {
        val c=r.javaClass.getMethod("getControl").invoke(r);c.javaClass.getMethod("getOutput").invoke(c).toString()
    }catch(_:NoSuchMethodException){r.stable.name}
    private inner class Run(val episode:String,val profile:String,val cfg:RecognitionConfig) {
        var now=0L;var index=0L;var seq=2
        val session=StreamSession({now});val engine=SignalEngine(session,cfg,clock={now})
        init {session.accept(Fixtures.info(names=names),"A");engine.tick()}
        fun feed(s:Seg){
            repeat(800){off->
                val p=Fixtures.le(1260).putLong(index).putLong(1_000_000+index*1000).putShort(5).putShort(62)
                repeat(310){k->p.putFloat(data[(s.offset+off*5)*62+k])};session.accept(Fixtures.packet(2,seq++,p.array()),"A")
                index+=5;now+=5
                if(index%200==0L){engine.tick();record(s,"window")}
            }
        }
        fun record(s:Seg,event:String){val r=engine.snapshot();val scores=(0..3).map{r.scores.getOrNull(it)?.toString()?:""}
            val winner=if(r.scores.isEmpty())"NONE" else Direction.values()[r.scores.indices.maxByOrNull{r.scores[it]}!!].name
            log.write((listOf(episode,profile,s.id,s.split,s.truth,now,event,r.windowEnd)+scores+listOf(winner,r.raw,r.stable,finalOutput(r),r.reason.replace(',',';'))).joinToString(",")+"\n")
        }
        fun close(s:Seg){session.accept(Fixtures.stop(seq,index),"A");record(s,"STOP");assertEquals("STOP",finalOutput(engine.snapshot()));engine.close()}
    }
    @Test fun originalTrialUnitsAndBoundaryScenarios() {
        assumeTrue(dir.exists());val src=fixtures.resolve("stage25")
        names=src.resolve("names.txt").readText().trim().split(',')
        val b=ByteBuffer.wrap(src.resolve("segments.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN);data=FloatArray(b.remaining()/4){b.float}
        val segs=src.resolve("plan.tsv").readLines().map{val x=it.split('\t');Seg(x[0].toInt(),x[1],x[2],x[4].toInt())}
        log=dir.resolve("trial-windows.csv").bufferedWriter()
        log.use {
            it.write("episode,profile,trial,split,truth,ms,event,windowEnd,rhoForward,rhoLeft,rhoRight,rhoBackward,winner,raw,stable,output,reason\n")
            for((profile,cfg) in listOf("default" to RecognitionConfig(),"candidate" to RecognitionConfig(.55,.05))){
                for(s in segs){
                    val single=Run("single-${s.id}",profile,cfg);single.feed(s);single.close(s)
                    val repeated=Run("repeat-${s.id}",profile,cfg);repeat(3){repeated.feed(s)};repeated.close(s)
                }
                for(label in listOf("forward","left","right","backward","stop")){
                    val group=segs.filter{it.truth==label};val mixed=Run("mixed-$label",profile,cfg)
                    repeat(2){group.forEach{mixed.feed(it)}};mixed.close(group.last())
                }
                val eval=segs.filter{it.split=="evaluation"}
                val switched=Run("switch-without-stop",profile,cfg)
                for(label in listOf("forward","left","right","backward","stop")){
                    val group=eval.filter{it.truth==label};repeat(if(label=="stop")2 else 1){group.forEach{switched.feed(it)}}
                    val normal=Run("switch-with-stop-$label",profile,cfg)
                    repeat(if(label=="stop")2 else 1){group.forEach{normal.feed(it)}};normal.close(group.last())
                };switched.close(eval.last())
                val resting=Run("active-to-rest",profile,cfg)
                eval.filter{it.truth=="forward"}.forEach{resting.feed(it)}
                repeat(2){resting.feed(eval.last())};resting.close(eval.last())
            }
        }
        // Filter-state diagnostic: same physical trial repeated, initial aligned vs later aligned windows.
        dir.resolve("aligned-filter.csv").bufferedWriter().use { w->
            w.write("trial,truth,windowEnd,rhoForward,rhoLeft,rhoRight,rhoBackward\n")
            val picks=SignalEngine.CHANNELS.map{names.indexOf(it)}
            for(s in segs){val f=Bandpass(1000);val x=Array(9){DoubleArray(4000)}
                repeat(3){cycle->repeat(4000){n->repeat(9){c->x[c][n]=f.sample(data[(s.offset+n)*62+picks[c]].toDouble(),c)}}
                    w.write("${s.id},${s.truth},${(cycle+1)*4000},${Cca(1000).scores(x).joinToString(",")}\n")}
            }
        }
        dir.resolve("filter-sos.csv").writeText(Bandpass(1000).sos.joinToString("\n"){it.joinToString(",")})
    }
}
