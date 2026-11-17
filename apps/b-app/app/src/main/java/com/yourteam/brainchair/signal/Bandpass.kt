package com.yourteam.brainchair.signal

import kotlin.math.*

/** Fourth-order Butterworth bandpass (prototype order 2), 4/45 Hz -3dB edges.
 * Prewarped analog poles -> bandpass transform -> bilinear transform -> two SOS.
 * Direct form II transposed, Double state per channel, never reset at packet/window edges.
 */
class Bandpass(val rate: Int, channels: Int = 9) {
    private data class C(val r: Double, val i: Double = 0.0) {
        operator fun plus(b: C) = C(r+b.r, i+b.i)
        operator fun minus(b: C) = C(r-b.r, i-b.i)
        operator fun times(b: C) = C(r*b.r-i*b.i, r*b.i+i*b.r)
        operator fun div(b: C): C { val d=b.r*b.r+b.i*b.i; return C((r*b.r+i*b.i)/d,(i*b.r-r*b.i)/d) }
        fun root(): C { val a=hypot(r,i); return C(sqrt(max(0.0,(a+r)/2)), (if(i<0) -1 else 1)*sqrt(max(0.0,(a-r)/2))) }
        fun abs() = hypot(r,i)
    }
    val sos: Array<DoubleArray>
    private val z1 = Array(2) { DoubleArray(channels) }
    private val z2 = Array(2) { DoubleArray(channels) }
    init {
        require(rate in 100..4000) { "识别支持100–4000Hz，Nyquist须高于45Hz" }
        val lo=2*rate*tan(PI*4/rate); val hi=2*rate*tan(PI*45/rate)
        val width=hi-lo; val w0=sqrt(lo*hi)
        val poles=mutableListOf<C>()
        for(sign in listOf(-1,1)) {
            val p=C(-width/sqrt(2.0),sign*width/sqrt(2.0))
            val discriminant=(p*p-C(4*w0*w0)).root()
            for(s in listOf((p+discriminant)/C(2.0),(p-discriminant)/C(2.0))) {
                val z=(C(2.0*rate)+s)/(C(2.0*rate)-s)
                if(z.i>0) poles.add(z)
            }
        }
        require(poles.size==2)
        sos=poles.sortedBy { it.abs() }.map { doubleArrayOf(1.0,0.0,-1.0,1.0,-2*it.r,it.r*it.r+it.i*it.i) }.toTypedArray()
        val omega=2*atan(w0/(2*rate)); val z=C(cos(omega),-sin(omega)); val zsq=z*z
        var gain=1.0
        for(s in sos) gain *= ((C(1.0)-zsq)/(C(1.0)+C(s[4])*z+C(s[5])*zsq)).abs()
        val scale=sqrt(1/gain)
        for(s in sos) { s[0]*=scale; s[2]*=scale }
    }
    fun sample(value: Double, channel: Int): Double {
        var x=value
        for(j in sos.indices) {
            val s=sos[j]; val y=s[0]*x+z1[j][channel]
            z1[j][channel]=s[1]*x-s[4]*y+z2[j][channel]
            z2[j][channel]=s[2]*x-s[5]*y; x=y
        }
        check(x.isFinite()) { "滤波数值异常" }; return x
    }
}
