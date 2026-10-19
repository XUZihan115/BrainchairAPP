package com.fifteen.eegsender.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 五个运动/停止指令的编码值，与 OpenBMI SSVEP 数据集的方向标签一一对应。
 *
 * 频率与方向的对应关系由本机对 sess01_subj01 的**全部 100 个真实试次**做 CCA
 * 分类实测确定（100/100 命中）。注意早期交接文档里 left/right 的频率曾被写反，
 * 这里以实测结果为准：
 *
 * | 指令 | 方向标签 | 刺激频率 |
 * |------|----------|----------|
 * | 1    | forward  | 12.00 Hz |
 * | 2    | left     |  8.57 Hz |
 * | 3    | right    |  6.67 Hz |
 * | 4    | backward |  5.45 Hz |
 * | 0    | stop     | 静息无刺激 |
 */
object EegCommands {
    const val STOP = 0
    const val FORWARD = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val BACKWARD = 4

    /** 指令总数。 */
    const val COUNT = 5

    fun name(cmd: Int): String = when (cmd) {
        FORWARD -> "前进"
        LEFT -> "左转"
        RIGHT -> "右转"
        BACKWARD -> "后退"
        STOP -> "停"
        else -> "未知($cmd)"
    }

    /** 指令在数据集中的方向标签，用于在 assets 索引里定位真实波形。 */
    fun label(cmd: Int): String = when (cmd) {
        FORWARD -> "forward"
        LEFT -> "left"
        RIGHT -> "right"
        BACKWARD -> "backward"
        STOP -> "stop"
        else -> throw IllegalArgumentException("未知指令: $cmd")
    }

    /** 指令对应的 SSVEP 刺激频率（Hz）；「停」为静息段，返回 null。 */
    fun stimulusHz(cmd: Int): Double? = when (cmd) {
        FORWARD -> 12.00
        LEFT -> 8.57
        RIGHT -> 6.67
        BACKWARD -> 5.45
        else -> null
    }
}

/**
 * 模拟真实脑电采集设备的 UDP 报文协议（版本 2）。
 *
 * 设计目标：让发送端发出的字节流在结构上等价于「一台脑电采集设备正在把数据
 * 实时推送给信息处理设备」。接收端只需要按下面这几张表解析即可，完全不需要
 * 了解本 App 的内部结构。所有多字节整数均为**小端序（little-endian）**。
 *
 * ## 通用包头（16 字节）
 *
 * | 偏移 | 长度 | 字段       | 说明                                             |
 * |------|------|------------|--------------------------------------------------|
 * | 0    | 2    | magic      | 固定 0x47EE（线上字节为 EE 47）                  |
 * | 2    | 1    | version    | 协议版本，当前 0x02                              |
 * | 3    | 1    | msgType    | 0x01 流描述 / 0x02 采样块 / 0x03 流结束          |
 * | 4    | 4    | deviceId   | 设备编号，本 App 固定 0x45454731（ASCII "EEG1"） |
 * | 8    | 4    | seq        | 报文序号，从 1 起单调递增，可用于统计丢包         |
 * | 12   | 4    | payloadLen | 负载字节数 N                                     |
 * | 16   | N    | payload    | 负载                                             |
 * | 16+N | 4    | crc32      | 对 [0, 16+N) 计算的 CRC-32                       |
 *
 * ## 负载格式
 *
 * **STREAM_INFO（一次推流开始时发一次）**
 *
 * | 偏移 | 长度 | 字段         | 说明                                |
 * |------|------|--------------|-------------------------------------|
 * | 0    | 4    | sampleRate   | 采样率 Hz，本 App 为 1000           |
 * | 4    | 2    | channelCount | 通道数，本 App 为 62                |
 * | 6    | 1    | dataType     | 0x01 float32 小端 / 0x02 int16 小端 |
 * | 7    | 1    | reserved     | 保留，恒为 0                        |
 * | 8    | 2    | namesLen     | 通道名字节数                        |
 * | 10   | n    | channelNames | UTF-8 编码、逗号分隔的通道名        |
 *
 * **SAMPLE_BLOCK（每个报文携带若干采样点，本 App 为 5 个）**
 *
 * | 偏移 | 长度 | 字段             | 说明                               |
 * |------|------|------------------|------------------------------------|
 * | 0    | 8    | firstSampleIndex | 本块首个采样点序号，从流开始计数    |
 * | 8    | 8    | deviceTimeUs     | 该采样点对应的设备时间戳，Unix 微秒 |
 * | 16   | 2    | sampleCount      | 本块采样点数                       |
 * | 18   | 2    | channelCount     | 通道数                             |
 * | 20   | n    | samples          | float32 采样，**时间主序**         |
 *
 * **STREAM_STOP（松手时发一次）**
 *
 * | 偏移 | 长度 | 字段            | 说明           |
 * |------|------|-----------------|----------------|
 * | 0    | 8    | lastSampleIndex | 本次共发出多少采样点 |
 * | 8    | 1    | reason          | 结束原因，见下方常量 |
 *
 * `reason` 取值：0x01 松手结束 / 0x02 发送端异常 / 0x03 切换方向（预留）。
 *
 * ## 采样数据布局
 *
 * 采用**时间主序（time-major）**：同一个采样点的 62 个通道值连续存放，再排下一个
 * 采样点，即 `sample[i][ch] = data[i * channelCount + ch]`。这与 LSL 及多数采集设备
 * 的约定一致，接收端可直接按此取值，无需重排。
 */
object EegFrame {
    /** 包头前两字节的数值形式；0x47EE 在小端写出即为 EE 47。 */
    const val MAGIC = 0x47EE

    const val VERSION = 2

    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4

    /** 本 App 作为「采集设备」的编号：ASCII "EEG1"。 */
    const val DEVICE_ID = 0x45454731

    const val MSG_STREAM_INFO = 0x01
    const val MSG_SAMPLE_BLOCK = 0x02
    const val MSG_STREAM_STOP = 0x03

    const val DATA_FLOAT32 = 0x01
    const val DATA_INT16 = 0x02

    /** 用户松手，本次想象正常结束。 */
    const val STOP_REASON_RELEASED = 0x01

    /** 发送端异常中止（例如网络不可达）。 */
    const val STOP_REASON_ERROR = 0x02

    /** 预留：未松手直接从当前方向切到另一个方向。 */
    const val STOP_REASON_SWITCHED = 0x03

    /** 单个 UDP 报文的字节上限：以太网 MTU 1500 - IP 头 20 - UDP 头 8。 */
    const val MAX_DATAGRAM = 1472

    /** SAMPLE_BLOCK 负载中除采样数据外的固定开销：8 + 8 + 2 + 2。 */
    const val SAMPLE_BLOCK_OVERHEAD = 20
}

/**
 * 复用同一块缓冲区的报文编码器。
 *
 * 1000 Hz 推流时每秒要发 200 个报文，若每个报文都新建 ByteArray 会造成持续的
 * GC 压力，因此这里固定分配一块缓冲反复使用；调用方通过 [array] 取后备数组，
 * 配合各 `encodeXxx` 返回的报文长度使用。
 */
class PacketEncoder(capacity: Int = EegFrame.MAX_DATAGRAM) {

    private val buffer: ByteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
    private val crc = CRC32()

    /** 编码结果所在的后备数组。 */
    val array: ByteArray get() = buffer.array()

    private fun begin(msgType: Int, seq: Int, payloadLen: Int) {
        buffer.clear()
        buffer.putShort(EegFrame.MAGIC.toShort())
        buffer.put(EegFrame.VERSION.toByte())
        buffer.put(msgType.toByte())
        buffer.putInt(EegFrame.DEVICE_ID)
        buffer.putInt(seq)
        buffer.putInt(payloadLen)
    }

    /** 在 length 处写入 CRC-32，返回报文总长度。 */
    private fun finish(length: Int): Int {
        crc.reset()
        crc.update(buffer.array(), 0, length)
        buffer.putInt(length, crc.value.toInt())
        return length + EegFrame.CRC_SIZE
    }

    /** 编码 STREAM_INFO，返回报文总长度。 */
    fun encodeStreamInfo(
        seq: Int,
        sampleRate: Int,
        channelCount: Int,
        channelNames: List<String>
    ): Int {
        val nameBytes = channelNames.joinToString(",").toByteArray(Charsets.UTF_8)
        val payloadLen = 10 + nameBytes.size
        begin(EegFrame.MSG_STREAM_INFO, seq, payloadLen)
        buffer.putInt(sampleRate)
        buffer.putShort(channelCount.toShort())
        buffer.put(EegFrame.DATA_FLOAT32.toByte())
        buffer.put(0.toByte())
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        return finish(EegFrame.HEADER_SIZE + payloadLen)
    }

    /** 编码 SAMPLE_BLOCK，采样字节从 [samples] 的 [samplesOffset] 处拷入。 */
    fun encodeSampleBlock(
        seq: Int,
        firstSampleIndex: Long,
        deviceTimeUs: Long,
        sampleCount: Int,
        channelCount: Int,
        samples: ByteArray,
        samplesOffset: Int,
        samplesLength: Int
    ): Int {
        val payloadLen = EegFrame.SAMPLE_BLOCK_OVERHEAD + samplesLength
        begin(EegFrame.MSG_SAMPLE_BLOCK, seq, payloadLen)
        buffer.putLong(firstSampleIndex)
        buffer.putLong(deviceTimeUs)
        buffer.putShort(sampleCount.toShort())
        buffer.putShort(channelCount.toShort())
        buffer.put(samples, samplesOffset, samplesLength)
        return finish(EegFrame.HEADER_SIZE + payloadLen)
    }

    /** 编码 STREAM_STOP，返回报文总长度。 */
    fun encodeStreamStop(seq: Int, lastSampleIndex: Long, reason: Int): Int {
        val payloadLen = 9
        begin(EegFrame.MSG_STREAM_STOP, seq, payloadLen)
        buffer.putLong(lastSampleIndex)
        buffer.put(reason.toByte())
        return finish(EegFrame.HEADER_SIZE + payloadLen)
    }
}
