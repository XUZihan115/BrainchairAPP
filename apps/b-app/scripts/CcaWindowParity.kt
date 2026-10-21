package com.yourteam.brainchair.experiment

import com.yourteam.brainchair.signal.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Numeric compatibility test only, NOT a four-second interaction accuracy result.
 * Run identically against production Cca and the copied experiment class, both explicitly at 4s.
 */
object CcaWindowParity {
    @JvmStatic fun main(args:Array<String>) {
        val dir=File(args[0]);val target=File(args[1])
        val names=dir.resolve("names.txt").readText().trim().split(',')
        val picks=listOf("Pz","O1","Oz","O2","P1","P2","POz","PO3","PO4").map{names.indexOf(it)}
        val b=ByteBuffer.wrap(dir.resolve("segments.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val data=FloatArray(b.remaining()/4){b.float}
        target.bufferedWriter().use { w->
            w.write("trial,rho_forward,rho_left,rho_right,rho_backward\n")
            for(plan in dir.resolve("plan.tsv").readLines()) {
                val p=plan.split('\t');val offset=p[4].toInt();val f=Bandpass(1000)
                val x=Array(9){DoubleArray(4000)}
                repeat(4000){n->repeat(9){c->x[c][n]=f.sample(data[(offset+n)*names.size+picks[c]].toDouble(),c)}}
                w.write(p[0]+","+Cca(1000,4.0).scores(x).joinToString(",")+"\n")
            }
        }
    }
}
