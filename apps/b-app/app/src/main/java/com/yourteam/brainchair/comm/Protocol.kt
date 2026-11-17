package com.yourteam.brainchair.comm

data class Frame(val cmd: Int, val arg: Int)

/** Teaching protocol v1.0. Real wheelchair compatibility must be confirmed separately. */
object Protocol {
    const val STOP = 5
    const val LIMIT = 6
    const val ESTOP = 7
    const val HEARTBEAT = 8
    const val RESET = 9
    const val BATTERY = 0x81
    const val ACK = 0x82
    const val FAULT = 0x83
    const val SPEED = 0x84
    const val STATE = 0x85
    fun build(cmd: Int, arg: Int): ByteArray {
        require(cmd in 0..255 && arg in 0..255)
        return byteArrayOf(0xAA.toByte(), 0x55, cmd.toByte(), arg.toByte(), (cmd xor arg).toByte(), 13, 10)
    }
}

/** Incremental, bounded parser: handles noise, fragmentation, concatenation and bad checksums. */
class ProtocolParser(private val onFrame: (Frame) -> Unit) {
    private val buffer = ArrayList<Int>(7)
    var invalidFrames = 0; private set
    fun feed(bytes: ByteArray) {
        for (b in bytes) {
            buffer.add(b.toInt() and 255)
            while (buffer.isNotEmpty()) {
                if (buffer[0] != 0xAA) { buffer.removeAt(0); continue }
                if (buffer.size < 2) break
                if (buffer[1] != 0x55) { buffer.removeAt(0); continue }
                if (buffer.size < 7) break
                if (buffer[4] == (buffer[2] xor buffer[3]) && buffer[5] == 13 && buffer[6] == 10) {
                    val frame = Frame(buffer[2], buffer[3])
                    buffer.subList(0, 7).clear()
                    onFrame(frame)
                } else { invalidFrames++; buffer.removeAt(0) }
            }
        }
    }
}
