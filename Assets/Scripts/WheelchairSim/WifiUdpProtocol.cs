using System;

namespace WheelchairSim
{
    /// <summary>
    /// WiFi（UDP）链路的帧类型。任务书只定义了指令帧（0x01）与状态帧（0x03），
    /// 0x02 留着占位：收到它只当作"链路还活着"，不产生任何动作。
    /// </summary>
    public enum WifiFrameType : byte
    {
        /// <summary>指令帧：前进/后退/左转/右转/停止/急停。</summary>
        Command = 0x01,
        /// <summary>保留（任务书未定义）。收到只算保活，不动作。</summary>
        Reserved = 0x02,
        /// <summary>状态帧：仿真端按 5 Hz 回传给 App。</summary>
        Status = 0x03,
    }

    /// <summary>
    /// 校验算法。Auto = 收到第一帧后自动识别并锁定（任务书没写用哪种，所以做成自适应的）。
    /// </summary>
    public enum WifiChecksum : byte
    {
        Auto = 0,
        /// <summary>累加和：所有字节相加取低 8 位。国内任务书最常见。</summary>
        Sum8 = 1,
        /// <summary>异或和：所有字节逐字节异或。</summary>
        Xor8 = 2,
        /// <summary>CRC-8：多项式 0x07 / 初值 0x00（与工程里其它链路一致）。</summary>
        Crc8 = 3,
    }

    /// <summary>校验字节覆盖的范围。任务书没写，所以两种都认。</summary>
    public enum WifiChecksumRange : byte
    {
        /// <summary>覆盖 类型 + 长度 + 数据（最常见）。</summary>
        TypeToData = 0,
        /// <summary>只覆盖数据区。</summary>
        DataOnly = 1,
    }

    /// <summary>
    /// 长度字段的语义。任务书没写清楚，解析时三种常见约定都接受，
    /// 并在诊断信息里告诉你对端到底用的是哪一种。
    /// </summary>
    public enum WifiLengthSemantics : byte
    {
        Unknown = 0,
        /// <summary>长度 = 数据区字节数（本仿真端发出的帧用这个）。</summary>
        DataLength = 1,
        /// <summary>长度 = 类型+长度+数据+校验（不含帧头帧尾）。</summary>
        BodyLength = 2,
        /// <summary>长度 = 整帧字节数（含帧头帧尾）。</summary>
        TotalLength = 3,
        /// <summary>三种都对不上：帧本身靠帧尾+校验仍然成立，但长度字段值得怀疑。</summary>
        Mismatch = 4,
    }

    /// <summary>任务书 2.2 定义的指令码。</summary>
    public enum WifiCommandCode : byte
    {
        None          = 0x00,
        Forward       = 0x01, // 前进
        Backward      = 0x02, // 后退
        TurnLeft      = 0x03, // 左转
        TurnRight     = 0x04, // 右转
        Stop          = 0x05, // 停止
        EmergencyStop = 0x06, // 急停
    }

    /// <summary>
    /// "左转/右转"到底怎么动。任务书只写了"解析转向强度 → 转向角速度"，没说是在行进中画弧还是原地旋转，
    /// 这里做成可配置：默认与 HUD 上的"左转/右转"一致（行进中转向），需要原地旋转时改配置即可。
    /// </summary>
    public enum WifiTurnMode : byte
    {
        /// <summary>左转/右转 = 边走边转（弧线），对应 SteerLeft / SteerRight。</summary>
        SteerWhileMoving = 0,
        /// <summary>左转/右转 = 原地旋转，对应 RotateLeft / RotateRight。</summary>
        RotateInPlace = 1,
    }

    /// <summary>一帧解析结果。</summary>
    public struct WifiFrame
    {
        public byte Type;
        public byte LengthField;
        public byte Checksum;
        public byte[] Data;
        public WifiChecksum Algorithm;
        public WifiChecksumRange Range;
        public WifiLengthSemantics LengthSemantics;

        public int DataLength { get { return Data == null ? 0 : Data.Length; } }
        public bool IsCommandFrame { get { return Type == (byte)WifiFrameType.Command; } }

        public override string ToString()
        {
            return string.Format("类型 0x{0:X2} 长度字段 {1} 数据 {2} B 校验 {3}",
                Type, LengthField, DataLength, Checksum);
        }
    }

    /// <summary>
    /// WiFi 状态帧（类型 0x03）的数据区。
    ///
    /// 任务书只说了"回传速度、位置(x,z)、运行状态、时间戳"，没给字段顺序，
    /// 所以这里是仿真端自定的约定（共 15 字节）：
    ///   [0]      状态标志：bit0 急停锁存 / bit1 故障 / bit2 步进中 / bit3 正在运动
    ///   [1]      当前动作码：0x01 前进 0x02 后退 0x03 左转 0x04 右转 0x05 停止 0x06 急停，0x00 = 其它
    ///   [2..3]   线速度 int16 小端，单位 mm/s（正 = 前进）
    ///   [4..5]   角速度 int16 小端，单位 0.001 rad/s（正 = 左转）
    ///   [6..7]   x 坐标 int16 小端，单位 cm
    ///   [8..9]   z 坐标 int16 小端，单位 cm
    ///   [10]     速度百分比 0~100（与 App 下发的口径一致）
    ///   [11..14] 时间戳 uint32 小端，单位 ms（仿真启动以来的单调时间）
    /// 与 B 端对齐字段顺序后，只改本结构体与 EncodeStatusData 即可。
    /// </summary>
    public struct WifiStatusData
    {
        public byte Flags;
        public byte ActionCode;
        public short LinearMillimetersPerSecond;
        public short AngularMilliRadiansPerSecond;
        public short XCentimeters;
        public short ZCentimeters;
        public byte SpeedPercent;
        public uint TimestampMilliseconds;

        public const byte FlagEmergency = 0x01;
        public const byte FlagFault     = 0x02;
        public const byte FlagStepping  = 0x04;
        public const byte FlagMoving    = 0x08;
    }

    /// <summary>
    /// WiFi(UDP) 帧解析器：帧尾 0x0D 0x0A 定界 + 帧头 0xAA 0x55 校验 + 校验字节自动识别。
    ///
    /// 为什么用帧尾定界：任务书里的"长度"字段语义没写清楚（是数据长度？整帧长度？），
    /// 靠帧尾切帧就绕开了这个坑；长度字段仍然会核对，只是三种常见约定都接受、不一致时只记数报警。
    ///
    /// 为什么校验要自动识别：任务书没写校验算法。收到帧后按 累加和/异或和/CRC8 ×
    /// （覆盖类型+长度+数据 / 只覆盖数据）六种组合去试，连续两帧都指向同一种组合才锁定；
    /// 锁定后若连续多帧对不上，会自动解锁重新识别（防止一开始就锁错算法）。
    /// </summary>
    public sealed class WifiUdpFrameParser
    {
        /// <summary>连续这么多帧对不上已锁定的算法，就认为锁错了，重新自动识别。</summary>
        public const int UnlockAfterFailures = 3;
        /// <summary>同一种算法连续命中这么多帧才锁定。</summary>
        public const int LockAfterHits = 2;

        private static readonly WifiChecksum[] DetectAlgorithms =
            { WifiChecksum.Sum8, WifiChecksum.Xor8, WifiChecksum.Crc8 };
        private static readonly WifiChecksumRange[] DetectRanges =
            { WifiChecksumRange.TypeToData, WifiChecksumRange.DataOnly };

        private readonly byte[] _buffer = new byte[WifiUdpProtocol.MaxFrameLength * 4];
        private int _length;

        private WifiChecksum _candidate = WifiChecksum.Auto;
        private WifiChecksumRange _candidateRange = WifiChecksumRange.TypeToData;
        private int _candidateHits;
        private WifiChecksum _lockedChecksum = WifiChecksum.Auto;
        private WifiChecksumRange _lockedRange = WifiChecksumRange.TypeToData;
        private int _lockedFailures;

        /// <summary>解析出的完整帧数（含状态帧等所有类型）。</summary>
        public int FramesReceived { get; private set; }
        /// <summary>其中指令帧（类型 0x01）的帧数。</summary>
        public int CommandFrames { get; private set; }
        /// <summary>因为校验全对不上而被丢弃的帧头数（丢包、脏数据、半帧都会记在这里）。</summary>
        public int ChecksumFailures { get; private set; }
        /// <summary>长度字段与三种约定都对不上的帧数（提醒用，帧本身仍然被接受）。</summary>
        public int LengthMismatches { get; private set; }
        /// <summary>被丢掉的非帧头字节数。</summary>
        public int NoiseBytes { get; private set; }

        public WifiChecksum LockedChecksum { get { return _lockedChecksum; } }
        public WifiChecksumRange LockedRange { get { return _lockedRange; } }

        /// <summary>校验识别的诊断文字，给 HUD / 日志用。</summary>
        public string DetectionText { get; private set; }

        public int BufferedBytes { get { return _length; } }

        public WifiUdpFrameParser()
        {
            DetectionText = "尚未识别（等第一帧）";
        }

        public void Reset()
        {
            _length = 0;
            _candidate = WifiChecksum.Auto;
            _candidateHits = 0;
            _lockedChecksum = WifiChecksum.Auto;
            _lockedFailures = 0;
            FramesReceived = 0;
            CommandFrames = 0;
            ChecksumFailures = 0;
            LengthMismatches = 0;
            NoiseBytes = 0;
            DetectionText = "尚未识别（等第一帧）";
        }

        // ------------------------------------------------------------ 收字节

        public void Append(byte[] data, int count)
        {
            if (data == null || count <= 0) return;
            if (count > data.Length) count = data.Length;

            if (count >= _buffer.Length)
            {
                // 一次性送来一整批（比缓冲区还大）：只保留最后一段
                Array.Copy(data, count - _buffer.Length, _buffer, 0, _buffer.Length);
                NoiseBytes += count - _buffer.Length;
                _length = _buffer.Length;
                return;
            }

            if (_length + count > _buffer.Length)
            {
                int drop = _length + count - _buffer.Length;
                Array.Copy(_buffer, drop, _buffer, 0, _length - drop);
                _length -= drop;
                NoiseBytes += drop;
            }

            Array.Copy(data, 0, _buffer, _length, count);
            _length += count;
        }

        // ------------------------------------------------------------ 取帧

        public bool TryRead(out WifiFrame frame)
        {
            frame = default(WifiFrame);

            while (true)
            {
                if (!AlignToHeader()) return false;
                if (_length < WifiUdpProtocol.MinFrameLength) return false;

                byte type = _buffer[2];
                byte lengthField = _buffer[3];
                // 两趟扫候选帧尾：第一趟只认"长度字段自洽"的候选（真帧尾几乎总在这一趟），
                // 第二趟兜底认长度语义完全对不上的候选（对端可能用三种之外的写法）。
                // 关键：数据区里的假帧尾（0x0D 0x0A）几乎不会满足长度自洽，所以它落不到第一趟，
                // 也就不会把"真帧尾还没到的半包"判死丢掉。
                bool rejectedCandidate = false;

                // 数据区里也可能出现 0x0D 0x0A（比如时间戳），所以每个候选帧尾都要用校验验证一遍。
                for (int pass = 0; pass < 2; pass++)
                for (int trailer = IndexOfTrailer(4); trailer >= 0; trailer = IndexOfTrailer(trailer + 1))
                {
                    int checksumIndex = trailer - 1;
                    if (checksumIndex < 4) continue;                  // 帧里连校验字节都放不下

                    int dataLength = checksumIndex - 4;
                    if (dataLength > WifiUdpProtocol.MaxDataLength) break;   // 数据区太长，判为脏数据

                    bool plausible = IsPlausibleLength(lengthField, dataLength);
                    if (pass == 0)
                    {
                        if (!plausible) continue;                     // 第一趟只认长度自洽的候选
                    }
                    else
                    {
                        if (plausible) continue;                      // 第一趟已经试过
                        if (rejectedCandidate) continue;              // 已有更可信的候选失败，不再兜底
                    }

                    WifiChecksum algorithm;
                    WifiChecksumRange range;
                    if (!TryMatchChecksum(checksumIndex, _buffer[checksumIndex], plausible, out algorithm, out range))
                    {
                        if (plausible) rejectedCandidate = true;       // 长度自洽却没过校验：帧头不可信
                        continue;                                     // 不是这一帧，继续往后找帧尾
                    }

                    frame.Type = type;
                    frame.LengthField = lengthField;
                    frame.Checksum = _buffer[checksumIndex];
                    frame.Algorithm = algorithm;
                    frame.Range = range;
                    frame.LengthSemantics = ClassifyLength(lengthField, dataLength);
                    frame.Data = new byte[dataLength];
                    if (dataLength > 0) Array.Copy(_buffer, 4, frame.Data, 0, dataLength);

                    Shift(trailer + 2);
                    FramesReceived++;
                    if (frame.IsCommandFrame) CommandFrames++;
                    if (frame.LengthSemantics == WifiLengthSemantics.Mismatch) LengthMismatches++;
                    return true;
                }

                if (!rejectedCandidate)
                {
                    // 还没有"长度自洽"的帧尾：大概率是这一帧还没收完，等下一包；
                    // 缓存被撑满说明是脏数据，往后挪一格重新找帧头。
                    if (_length >= WifiUdpProtocol.MaxFrameLength) { NoiseBytes++; Shift(1); continue; }
                    return false;
                }

                // 长度自洽但校验全对不上：当前这个"帧头"不可信，往后挪一格重新同步。
                ChecksumFailures++;
                Shift(1);
            }
        }

        /// <summary>
        /// 长度字段是否与候选帧尾推出的数据长度自洽。三种可能语义（长度=数据长度 / 数据长度+3 /
        /// 数据长度+7，即校验+帧尾两段）任取其一即可，因此对三种未知语义都兼容。
        /// </summary>
        private static bool IsPlausibleLength(byte lengthField, int dataLength)
        {
            int value = lengthField;
            return value == dataLength
                || value == dataLength + 3
                || value == dataLength + 7;
        }

        // ------------------------------------------------------------ 内部

        /// <summary>把缓冲区开头对齐到一个帧头上。返回 false = 目前没有帧头（缺数据）。</summary>
        private bool AlignToHeader()
        {
            for (int i = 0; i + 1 < _length; i++)
            {
                if (_buffer[i] == WifiUdpProtocol.Header1 && _buffer[i + 1] == WifiUdpProtocol.Header2)
                {
                    if (i > 0)
                    {
                        NoiseBytes += i;
                        Shift(i);
                    }
                    return true;
                }
            }

            // 没有帧头：只保留最后一个字节（可能是被拆成两半的 0xAA）
            int keep = (_length > 0 && _buffer[_length - 1] == WifiUdpProtocol.Header1) ? 1 : 0;
            NoiseBytes += _length - keep;
            if (keep == 1) _buffer[0] = WifiUdpProtocol.Header1;
            _length = keep;
            return false;
        }

        private int IndexOfTrailer(int from)
        {
            int last = _length - 2;
            if (from < 0) from = 0;
            for (int i = from; i <= last; i++)
            {
                if (_buffer[i] == WifiUdpProtocol.Trailer1 && _buffer[i + 1] == WifiUdpProtocol.Trailer2) return i;
            }
            return -1;
        }

        /// <summary>
        /// 试出这个候选帧尾用的是哪种校验。countFailure = false 时不计入"算法锁错了"的计数
        /// （兜底趟里的候选本来就不可信，不能让它把已经锁定的算法带偏）。
        /// </summary>
        private bool TryMatchChecksum(int checksumIndex, byte actual, bool countFailure, out WifiChecksum algorithm, out WifiChecksumRange range)
        {
            algorithm = WifiChecksum.Auto;
            range = WifiChecksumRange.TypeToData;

            if (_lockedChecksum != WifiChecksum.Auto)
            {
                if (Matches(_lockedChecksum, _lockedRange, checksumIndex, actual))
                {
                    _lockedFailures = 0;
                    algorithm = _lockedChecksum;
                    range = _lockedRange;
                    return true;
                }

                // 偶发丢包/脏字节会让单帧对不上，连续几帧都对不上才认定是"算法锁错了"
                if (countFailure)
                {
                    _lockedFailures++;
                    if (_lockedFailures < UnlockAfterFailures) return false;

                    _lockedChecksum = WifiChecksum.Auto;
                    _lockedFailures = 0;
                    _candidate = WifiChecksum.Auto;
                    _candidateHits = 0;
                    DetectionText = "重新识别中";
                }
                // countFailure = false：不动锁定状态，直接往下走完整识别（兜底趟用）
            }

            WifiChecksum foundAlgorithm;
            WifiChecksumRange foundRange;
            if (!TryDetect(checksumIndex, actual, out foundAlgorithm, out foundRange)) return false;

            algorithm = foundAlgorithm;
            range = foundRange;
            NoteCandidate(foundAlgorithm, foundRange);
            return true;
        }

        private bool TryDetect(int checksumIndex, byte actual, out WifiChecksum algorithm, out WifiChecksumRange range)
        {
            // 按"最常见 -> 少见"的顺序试：累加和+覆盖类型/长度/数据 排第一
            for (int i = 0; i < DetectAlgorithms.Length; i++)
            {
                for (int j = 0; j < DetectRanges.Length; j++)
                {
                    if (!Matches(DetectAlgorithms[i], DetectRanges[j], checksumIndex, actual)) continue;
                    algorithm = DetectAlgorithms[i];
                    range = DetectRanges[j];
                    return true;
                }
            }

            algorithm = WifiChecksum.Auto;
            range = WifiChecksumRange.TypeToData;
            return false;
        }

        private bool Matches(WifiChecksum algorithm, WifiChecksumRange range, int checksumIndex, byte actual)
        {
            int start = range == WifiChecksumRange.TypeToData ? 2 : 4;
            int count = checksumIndex - start;
            if (count <= 0) return false;
            return WifiUdpProtocol.Checksum(algorithm, _buffer, start, count) == actual;
        }

        private void NoteCandidate(WifiChecksum algorithm, WifiChecksumRange range)
        {
            if (_candidate == algorithm && _candidateRange == range) _candidateHits++;
            else
            {
                _candidate = algorithm;
                _candidateRange = range;
                _candidateHits = 1;
            }

            if (_candidateHits >= LockAfterHits && _lockedChecksum == WifiChecksum.Auto)
            {
                _lockedChecksum = algorithm;
                _lockedRange = range;
                DetectionText = string.Format("{0}，覆盖 {1}", WifiUdpProtocol.ChecksumName(algorithm),
                    WifiUdpProtocol.ChecksumRangeName(range));
            }
        }

        private static WifiLengthSemantics ClassifyLength(byte lengthField, int dataLength)
        {
            if (lengthField == dataLength) return WifiLengthSemantics.DataLength;
            if (lengthField == dataLength + 3) return WifiLengthSemantics.BodyLength;
            if (lengthField == dataLength + 7) return WifiLengthSemantics.TotalLength;
            return WifiLengthSemantics.Mismatch;
        }

        private void Shift(int count)
        {
            if (count <= 0) return;
            if (count >= _length) { _length = 0; return; }
            Array.Copy(_buffer, count, _buffer, 0, _length - count);
            _length -= count;
        }
    }

    /// <summary>任务书 2.2 的 WiFi 帧编解码（纯计算，不碰网络，方便单测）。</summary>
    public static class WifiUdpProtocol
    {
        public const byte Header1 = 0xAA;
        public const byte Header2 = 0x55;
        public const byte Trailer1 = 0x0D;
        public const byte Trailer2 = 0x0A;

        /// <summary>最短帧：AA 55 类型 长度 校验 0D 0A（数据区为空）。</summary>
        public const int MinFrameLength = 7;
        /// <summary>数据区上限（长度字段是 1 字节，留足余量）。</summary>
        public const int MaxDataLength = 60;
        public const int MaxFrameLength = MaxDataLength + 7;
        /// <summary>状态帧数据区长度。</summary>
        public const int StatusDataLength = 15;

        // ------------------------------------------------------------ 校验

        public static byte Checksum(WifiChecksum algorithm, byte[] data, int offset, int count)
        {
            switch (algorithm)
            {
                case WifiChecksum.Sum8:
                {
                    int sum = 0;
                    for (int i = 0; i < count; i++) sum += data[offset + i];
                    return (byte)(sum & 0xFF);
                }
                case WifiChecksum.Xor8:
                {
                    byte value = 0;
                    for (int i = 0; i < count; i++) value ^= data[offset + i];
                    return value;
                }
                case WifiChecksum.Crc8:
                    return WheelchairProtocol.Crc8(data, offset, count);
                default:
                    return 0;
            }
        }

        public static string ChecksumName(WifiChecksum algorithm)
        {
            switch (algorithm)
            {
                case WifiChecksum.Sum8: return "累加和";
                case WifiChecksum.Xor8: return "异或和";
                case WifiChecksum.Crc8: return "CRC8(0x07)";
                default: return "自动识别";
            }
        }

        public static string ChecksumRangeName(WifiChecksumRange range)
        {
            return range == WifiChecksumRange.DataOnly ? "仅数据区" : "类型+长度+数据";
        }

        public static string LengthSemanticsName(WifiLengthSemantics semantics)
        {
            switch (semantics)
            {
                case WifiLengthSemantics.DataLength: return "数据区长度";
                case WifiLengthSemantics.BodyLength: return "类型+长度+数据+校验";
                case WifiLengthSemantics.TotalLength: return "整帧长度";
                case WifiLengthSemantics.Mismatch: return "与三种约定都不符";
                default: return "未知";
            }
        }

        // ------------------------------------------------------------ 编码

        /// <summary>
        /// 组一帧：帧头 + 类型 + 长度 + 数据 + 校验 + 帧尾。
        /// 长度字段填"数据区字节数"；校验覆盖"类型+长度+数据"。
        /// </summary>
        public static byte[] EncodeFrame(WifiFrameType type, byte[] data, WifiChecksum algorithm)
        {
            int dataLength = data == null ? 0 : data.Length;
            if (dataLength > MaxDataLength)
                throw new ArgumentException("数据区超过 " + MaxDataLength + " 字节：" + dataLength);

            if (algorithm == WifiChecksum.Auto) algorithm = WifiChecksum.Sum8;

            byte[] frame = new byte[dataLength + 7];
            frame[0] = Header1;
            frame[1] = Header2;
            frame[2] = (byte)type;
            frame[3] = (byte)dataLength;
            if (dataLength > 0) Array.Copy(data, 0, frame, 4, dataLength);
            frame[4 + dataLength] = Checksum(algorithm, frame, 2, dataLength + 2);
            frame[5 + dataLength] = Trailer1;
            frame[6 + dataLength] = Trailer2;
            return frame;
        }

        public static byte[] EncodeStatusFrame(WifiStatusData data, WifiChecksum algorithm)
        {
            return EncodeFrame(WifiFrameType.Status, EncodeStatusData(data), algorithm);
        }

        public static byte[] EncodeStatusData(WifiStatusData data)
        {
            byte[] payload = new byte[StatusDataLength];
            payload[0] = data.Flags;
            payload[1] = data.ActionCode;
            WriteInt16(payload, 2, data.LinearMillimetersPerSecond);
            WriteInt16(payload, 4, data.AngularMilliRadiansPerSecond);
            WriteInt16(payload, 6, data.XCentimeters);
            WriteInt16(payload, 8, data.ZCentimeters);
            payload[10] = data.SpeedPercent;
            WriteUInt32(payload, 11, data.TimestampMilliseconds);
            return payload;
        }

        private static void WriteInt16(byte[] buffer, int offset, short value)
        {
            buffer[offset] = (byte)(value & 0xFF);
            buffer[offset + 1] = (byte)((value >> 8) & 0xFF);
        }

        private static void WriteUInt32(byte[] buffer, int offset, uint value)
        {
            buffer[offset] = (byte)(value & 0xFF);
            buffer[offset + 1] = (byte)((value >> 8) & 0xFF);
            buffer[offset + 2] = (byte)((value >> 16) & 0xFF);
            buffer[offset + 3] = (byte)((value >> 24) & 0xFF);
        }

        public static short ClampToInt16(int value)
        {
            if (value > short.MaxValue) return short.MaxValue;
            if (value < short.MinValue) return short.MinValue;
            return (short)value;
        }

        // -------------------------------------------------------- 语义映射

        /// <summary>
        /// 指令码 -> 工程内部指令。左转/右转按 turnMode 决定是"行进中转向"还是"原地旋转"。
        /// 返回 false = 任务书之外/保留的指令码，调用方应当忽略。
        /// </summary>
        public static bool TryMapCommand(WifiCommandCode code, WifiTurnMode turnMode, out WheelchairCommand command)
        {
            switch (code)
            {
                case WifiCommandCode.Forward:
                    command = WheelchairCommand.Forward; return true;
                case WifiCommandCode.Backward:
                    command = WheelchairCommand.Backward; return true;
                case WifiCommandCode.TurnLeft:
                    command = turnMode == WifiTurnMode.RotateInPlace
                        ? WheelchairCommand.RotateLeft
                        : WheelchairCommand.SteerLeft;
                    return true;
                case WifiCommandCode.TurnRight:
                    command = turnMode == WifiTurnMode.RotateInPlace
                        ? WheelchairCommand.RotateRight
                        : WheelchairCommand.SteerRight;
                    return true;
                case WifiCommandCode.Stop:
                    command = WheelchairCommand.Stop; return true;
                case WifiCommandCode.EmergencyStop:
                    command = WheelchairCommand.EmergencyStop; return true;
                default:
                    command = WheelchairCommand.None; return false;
            }
        }

        /// <summary>把内部指令翻译回任务书的动作码（状态帧上报用）。</summary>
        public static byte ActionCodeFor(WheelchairCommand command)
        {
            switch (command)
            {
                case WheelchairCommand.Forward:      return (byte)WifiCommandCode.Forward;
                case WheelchairCommand.Backward:     return (byte)WifiCommandCode.Backward;
                case WheelchairCommand.SteerLeft:    return (byte)WifiCommandCode.TurnLeft;
                case WheelchairCommand.RotateLeft:   return (byte)WifiCommandCode.TurnLeft;
                case WheelchairCommand.SteerRight:   return (byte)WifiCommandCode.TurnRight;
                case WheelchairCommand.RotateRight:  return (byte)WifiCommandCode.TurnRight;
                case WheelchairCommand.Stop:         return (byte)WifiCommandCode.Stop;
                case WheelchairCommand.EmergencyStop:return (byte)WifiCommandCode.EmergencyStop;
                default:                             return (byte)WifiCommandCode.None;
            }
        }

        /// <summary>
        /// 取指令帧数据区里的速度/转向强度（0~100）。
        /// 数据区： [0] 指令码，[1] 速度百分比（可选，1 字节）；也接受 [1..2] 的 int16 小端写法。
        /// specified = false 表示这次没带速度（0、缺省、负值都算没带），调用方应当沿用当前速度。
        /// </summary>
        public static int DecodeSpeedPercent(byte[] data, int dataLength, out bool specified)
        {
            specified = false;
            if (data == null || dataLength < 2) return 0;

            int value;
            if (dataLength >= 3) value = (short)(data[1] | (data[2] << 8));
            else value = data[1];

            if (value <= 0) return 0;
            if (value > 100) value = 100;
            specified = true;
            return value;
        }
    }
}
