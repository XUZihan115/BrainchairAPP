using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace WheelchairSim
{
    /// <summary>支持的报文形态。Auto = 根据首个有效字节自动判定。</summary>
    public enum ProtocolMode { Auto, Binary, Ascii }

    /// <summary>
    /// 轮椅控制协议。
    ///
    /// 一、二进制帧（推荐真机使用）
    ///   +------+------+-----+-----+-----+---------+------+
    ///   | 0xAA | 0x55 | LEN | SEQ | CMD | ARG x2  | CRC8 |
    ///   +------+------+-----+-----+-----+---------+------+
    ///   LEN  : SEQ + CMD + ARG 的总字节数，指令帧固定为 4
    ///   ARG  : int16 小端，实际物理量 = ARG / 100.0
    ///   CRC8 : 多项式 0x07 / 初值 0x00，覆盖 [LEN .. ARG] 共 LEN+1 字节
    ///
    ///   状态回传帧 CMD = 0x80，LEN = 10：
    ///     SEQ(1) CMD(1) 电量(1) 线速度(2) 角速度(2) 航向(2) 标志(1)
    ///
    /// 二、ASCII 行（方便用串口助手 / 网络调试助手 / 蓝牙串口 App 手工测试）
    ///   以 \n 或 \r\n 结尾，大小写不敏感，斜杠后为等价写法：
    ///     F / FORWARD          前进
    ///     B / BACKWARD         后退
    ///     L / LEFT             左转（行进中）
    ///     R / RIGHT            右转（行进中）
    ///     RL / ROTATELEFT      原地左旋
    ///     RR / ROTATERIGHT     原地右旋
    ///     S / STOP             停车
    ///     X / ESTOP            急停
    ///     REL / RELEASE        解除急停
    ///     SPD:0.60             设定速度 m/s
    ///     LIMIT:1.00           设定限速 m/s
    ///     TURN:0.80            设定角速度 rad/s
    ///     HB / PING            心跳
    ///     Q / STATUS / ?       请求回传状态
    /// </summary>
    public static class WheelchairProtocol
    {
        public const byte Header1 = 0xAA;
        public const byte Header2 = 0x55;
        public const int  FixedPayloadLength = 4;   // SEQ + CMD + ARG(2)
        public const float ArgScale = 100f;
        public const byte StatusCommandId = 0x80;
        public const int  MaxPayloadLength = 64;

        // ---------------------------------------------------------------- CRC

        public static byte Crc8(byte[] data, int offset, int count)
        {
            byte crc = 0x00;
            for (int i = 0; i < count; i++)
            {
                crc ^= data[offset + i];
                for (int bit = 0; bit < 8; bit++)
                    crc = (byte)((crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1);
            }
            return crc;
        }

        // -------------------------------------------------------------- 编码

        public static byte[] EncodeFrame(WheelchairCommand command, float arg = 0f, byte seq = 0)
        {
            byte[] frame = new byte[2 + 1 + FixedPayloadLength + 1];
            frame[0] = Header1;
            frame[1] = Header2;
            frame[2] = FixedPayloadLength;
            frame[3] = seq;
            frame[4] = (byte)command;
            short raw = ToScaledInt16(arg);
            frame[5] = (byte)(raw & 0xFF);
            frame[6] = (byte)((raw >> 8) & 0xFF);
            frame[7] = Crc8(frame, 2, FixedPayloadLength + 1);
            return frame;
        }

        /// <summary>状态回传帧。仿真里由 WheelchairHub 发送给上位机。</summary>
        public static byte[] EncodeStateFrame(WheelchairState state, byte seq = 0)
        {
            const int payloadLen = 1 + 1 + 8;      // SEQ + CMD + 8 字节负载
            byte[] frame = new byte[2 + 1 + payloadLen + 1];

            frame[0] = Header1;
            frame[1] = Header2;
            frame[2] = (byte)payloadLen;
            frame[3] = seq;
            frame[4] = StatusCommandId;

            int p = 5;
            frame[p++] = (byte)Clamp(state.BatteryPercent, 0f, 100f);

            short lin = ToScaledInt16(state.LinearSpeed);
            frame[p++] = (byte)(lin & 0xFF);
            frame[p++] = (byte)((lin >> 8) & 0xFF);

            short ang = ToScaledInt16(state.AngularSpeed);
            frame[p++] = (byte)(ang & 0xFF);
            frame[p++] = (byte)((ang >> 8) & 0xFF);

            ushort heading = (ushort)(NormalizeAngle(state.Heading) * 10f);
            frame[p++] = (byte)(heading & 0xFF);
            frame[p++] = (byte)((heading >> 8) & 0xFF);

            byte flags = 0;
            if (state.EmergencyStop) flags |= 0x01;
            if (state.Fault)         flags |= 0x02;
            frame[p++] = flags;

            frame[p] = Crc8(frame, 2, payloadLen + 1);
            return frame;
        }

        // -------------------------------------------------------- ASCII 解析

        public static bool TryParseAscii(string line, out CommandMessage message)
        {
            message = default(CommandMessage);
            if (string.IsNullOrEmpty(line)) return false;

            string text = line.Trim();
            if (text.Length == 0) return false;

            string upper = text.ToUpperInvariant();
            string key = upper;
            float arg = 0f;

            int colon = upper.IndexOf(':');
            if (colon >= 0)
            {
                key = upper.Substring(0, colon).Trim();
                string argText = upper.Substring(colon + 1).Trim();
                float.TryParse(argText, NumberStyles.Float, CultureInfo.InvariantCulture, out arg);
            }

            switch (key)
            {
                case "F":  case "FORWARD": case "UP":
                    message = new CommandMessage(WheelchairCommand.Forward); return true;
                case "B":  case "BACK":    case "BACKWARD": case "DOWN":
                    message = new CommandMessage(WheelchairCommand.Backward); return true;
                case "L":  case "LEFT":
                    message = new CommandMessage(WheelchairCommand.SteerLeft); return true;
                case "R":  case "RIGHT":
                    message = new CommandMessage(WheelchairCommand.SteerRight); return true;
                case "RL": case "ROTATELEFT": case "TURNLEFT":
                    message = new CommandMessage(WheelchairCommand.RotateLeft); return true;
                case "RR": case "ROTATERIGHT": case "TURNRIGHT":
                    message = new CommandMessage(WheelchairCommand.RotateRight); return true;
                case "S":  case "STOP":
                    message = new CommandMessage(WheelchairCommand.Stop); return true;
                case "X":  case "ESTOP": case "EMERGENCY":
                    message = new CommandMessage(WheelchairCommand.EmergencyStop); return true;
                case "REL": case "RELEASE":
                    message = new CommandMessage(WheelchairCommand.ReleaseEmergency); return true;
                case "SPD":   case "SPEED":
                    message = new CommandMessage(WheelchairCommand.SetSpeed, arg); return true;
                case "TURN":  case "TURNRATE":
                    message = new CommandMessage(WheelchairCommand.SetTurnRate, arg); return true;
                case "LIMIT": case "SPEEDLIMIT":
                    message = new CommandMessage(WheelchairCommand.SetSpeedLimit, arg); return true;
                case "HB":    case "PING": case "KEEPALIVE":
                    message = new CommandMessage(WheelchairCommand.Heartbeat); return true;
                case "Q":     case "STATUS": case "?":
                    message = new CommandMessage(WheelchairCommand.RequestStatus); return true;
            }

            return false;
        }

        // ------------------------------------------------------------ 工具

        private static short ToScaledInt16(float value)
        {
            int raw = (int)System.Math.Round(value * ArgScale);
            if (raw > short.MaxValue) raw = short.MaxValue;
            if (raw < short.MinValue) raw = short.MinValue;
            return (short)raw;
        }

        private static byte Clamp(float value, float min, float max)
        {
            if (value < min) value = min;
            if (value > max) value = max;
            return (byte)value;
        }

        private static float NormalizeAngle(float degrees)
        {
            degrees %= 360f;
            if (degrees < 0f) degrees += 360f;
            return degrees;
        }
    }

    /// <summary>二进制帧解析器，遇到脏数据会自动重新同步。</summary>
    public sealed class WheelchairFrameParser
    {
        private readonly byte[] _buffer = new byte[1024];
        private int _length;

        public void Reset() { _length = 0; }

        public void Append(byte[] data, int count)
        {
            if (data == null || count <= 0) return;
            if (count > _buffer.Length) count = _buffer.Length;

            if (_length + count > _buffer.Length)
            {
                int drop = _length + count - _buffer.Length;
                if (drop >= _length) _length = 0;
                else
                {
                    System.Array.Copy(_buffer, drop, _buffer, 0, _length - drop);
                    _length -= drop;
                }
            }

            int copy = System.Math.Min(count, _buffer.Length - _length);
            System.Array.Copy(data, 0, _buffer, _length, copy);
            _length += copy;
        }

        public bool TryRead(out CommandMessage message)
        {
            message = default(CommandMessage);

            while (_length >= 3)
            {
                if (_buffer[0] != WheelchairProtocol.Header1 || _buffer[1] != WheelchairProtocol.Header2)
                {
                    Shift(1);
                    continue;
                }

                int payloadLen = _buffer[2];
                if (payloadLen < WheelchairProtocol.FixedPayloadLength ||
                    payloadLen > WheelchairProtocol.MaxPayloadLength)
                {
                    Shift(1);
                    continue;
                }

                int total = 3 + payloadLen + 1;
                if (_length < total) return false;

                if (WheelchairProtocol.Crc8(_buffer, 2, payloadLen + 1) != _buffer[3 + payloadLen])
                {
                    Shift(1);
                    continue;
                }

                byte seq = _buffer[3];
                byte cmd = _buffer[4];

                // 状态帧（CMD 高位置 1，如 0x80）是仿真端自己发出去的回传帧，
                // 万一被回环回来，必须整帧消费掉而不能当成指令执行，否则会误触发动作。
                if (cmd >= WheelchairProtocol.StatusCommandId)
                {
                    Shift(total);
                    continue;
                }

                short raw = (short)(_buffer[5] | (_buffer[6] << 8));

                Shift(total);
                message = new CommandMessage((WheelchairCommand)cmd, raw / WheelchairProtocol.ArgScale, seq);
                return true;
            }

            return false;
        }

        private void Shift(int n)
        {
            if (n >= _length) { _length = 0; return; }
            System.Array.Copy(_buffer, n, _buffer, 0, _length - n);
            _length -= n;
        }
    }

    /// <summary>
    /// 把一串原始字节流翻译成指令队列。同时兼容二进制帧与 ASCII 文本行，
    /// 所以无论对方发的是哪种格式，仿真端都不用改代码。
    /// </summary>
    public sealed class CommandStreamDecoder
    {
        private readonly WheelchairFrameParser _binary = new WheelchairFrameParser();
        private readonly StringBuilder _text = new StringBuilder(128);
        private readonly Queue<CommandMessage> _queue = new Queue<CommandMessage>();

        public CommandStreamDecoder(ProtocolMode mode = ProtocolMode.Auto)
        {
            Mode = mode;
        }

        public ProtocolMode Mode { get; set; }
        public int PendingCount { get { return _queue.Count; } }

        public void Append(byte[] data, int count)
        {
            if (data == null || count <= 0) return;

            if (Mode == ProtocolMode.Auto)
            {
                for (int i = 0; i < count; i++)
                {
                    byte b = data[i];
                    if (b == WheelchairProtocol.Header1) { Mode = ProtocolMode.Binary; break; }
                    if (b == 0x20 || b == 0x09 || b == 0x0D || b == 0x0A) continue;
                    if (b >= 0x21 && b <= 0x7E) { Mode = ProtocolMode.Ascii; break; }
                }
                if (Mode == ProtocolMode.Auto) return;   // 全是无效字节，直接丢弃
            }

            if (Mode == ProtocolMode.Binary)
            {
                _binary.Append(data, count);
                CommandMessage msg;
                while (_binary.TryRead(out msg)) _queue.Enqueue(msg);
            }
            else
            {
                for (int i = 0; i < count; i++)
                {
                    char c = (char)data[i];
                    if (c == '\n' || c == '\r')
                    {
                        if (_text.Length > 0)
                        {
                            CommandMessage msg;
                            if (WheelchairProtocol.TryParseAscii(_text.ToString(), out msg)) _queue.Enqueue(msg);
                            _text.Length = 0;
                        }
                    }
                    else if (c >= ' ' && c <= '~' && _text.Length < 128)
                    {
                        _text.Append(c);
                    }
                }
            }
        }

        public bool TryDequeue(out CommandMessage message)
        {
            if (_queue.Count == 0) { message = default(CommandMessage); return false; }
            message = _queue.Dequeue();
            return true;
        }

        public void Reset()
        {
            _binary.Reset();
            _text.Length = 0;
            _queue.Clear();
            Mode = ProtocolMode.Auto;
        }
    }
}
