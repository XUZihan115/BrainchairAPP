package com.yourteam.brainchair.control

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnityFrameError(val reason:String):IllegalArgumentException(reason)
data class UnityState(val flags:Int,val action:Int,val linearMmS:Int,val angularMilliRadS:Int,
                      val xCm:Int,val zCm:Int,val speedPercent:Int,val timestampMs:Long) {
    val emergency:Boolean get()=flags and 1!=0
    val fault:Boolean get()=flags and 2!=0
    val stepping:Boolean get()=flags and 4!=0
    val moving:Boolean get()=flags and 8!=0
    val stationary:Boolean get()=!moving && linearMmS==0 && angularMilliRadS==0
}
/** The October 21 Unity implementation: data-length LEN, Sum8(TYPE+LEN+DATA).
 * Exact datagram length is used for feedback: CRLF may legally occur inside binary DATA.
 */
object UnitySum8Codec:UnityCodec {
    const val CONTROL_PORT=9001
    const val STATUS_PORT=9002
    override val confirmed=true
    fun command(command:ControlCommand,speed:Int?=null):ByteArray {
        require(speed==null || speed in 0..100)
        val data=if(speed==null)byteArrayOf(command.code.toByte()) else byteArrayOf(command.code.toByte(),speed.toByte())
        val body=byteArrayOf(1,data.size.toByte())+data
        return byteArrayOf(0xaa.toByte(),0x55)+body+byteArrayOf((body.sumOf{it.toInt() and 255} and 255).toByte(),13,10)
    }
    override fun encode(command:UnityCommand)=this.command(command.command,if(command.command.moving)command.speed else null)
    fun state(raw:ByteArray):UnityState {
        if(raw.size!=22)throw UnityFrameError("length")
        fun u(n:Int)=raw[n].toInt() and 255
        if(u(0)!=0xaa || u(1)!=0x55 || u(20)!=13 || u(21)!=10)throw UnityFrameError("delimiter")
        if(u(2)!=3 || u(3)!=15)throw UnityFrameError("type/length")
        if(((2..18).sumOf{u(it)} and 255)!=u(19))throw UnityFrameError("sum8")
        val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);b.position(4)
        val flags=b.get().toInt() and 255;val action=b.get().toInt() and 255
        val linear=b.short.toInt();val angular=b.short.toInt();val x=b.short.toInt();val z=b.short.toInt()
        val speed=b.get().toInt() and 255;val time=b.int.toLong() and 0xffffffffL
        if(flags and 0xf0!=0 || action !in 0..6 || speed !in 0..100)throw UnityFrameError("fields")
        return UnityState(flags,action,linear,angular,x,z,speed,time)
    }
    fun ipv4(value:String):InetAddress {
        val parts=value.trim().split('.')
        require(parts.size==4 && parts.all{it.isNotEmpty() && it.length<=3 && it.all{c->c in '0'..'9'} && it.toIntOrNull()?.let{n->n in 0..255}==true}){"请输入Unity电脑IPv4地址"}
        val numbers=parts.map{it.toInt()}
        require(numbers[0] in 1..223 && numbers[3]!=255){"不接受未指定、多播或广播地址"}
        return InetAddress.getByAddress(numbers.map{it.toByte()}.toByteArray())
    }
    fun hex(bytes:ByteArray)=bytes.joinToString(" "){"%02X".format(it.toInt() and 255)}
}
