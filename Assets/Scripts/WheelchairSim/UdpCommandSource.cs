using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// WiFi（UDP）指令源：给"手机开热点、Unity 端当客户端"的展示链路用（任务书 2.2）。
    ///
    ///   手机 App  --UDP 9001-->  Unity（本类）  --UDP 9002, 5 Hz-->  手机 App
    ///     指令帧 0x01                 解析成 CommandMessage           状态帧 0x03
    ///
    /// 为什么不用 CommandStreamDecoder：任务书的帧格式（帧头 0xAA 0x55 + 类型 + 长度 + 数据 +
    /// 校验 + 帧尾 0x0D 0x0A）虽然也是 0xAA 0x55 开头，但字段含义和工程里原有的二进制帧不同，
    /// 喂给老解码器会被当成另一套协议解析。所以本类自己用 WifiUdpFrameParser，
    /// 靠帧尾 + 校验切帧，并把校验算法做成自动识别。
    ///
    /// 两处任务书没写、由本类默认约定的地方（都在 Bootstrap 里可改）：
    ///   1) 左转/右转 = 行进中转向（弧线）还是原地旋转 —— 默认与 HUD 的"左转/右转"一致：行进中转向；
    ///   2) 速度 0~100 的满量程 —— 默认 100% = 1.0 m/s / 1.5 rad/s（与安全限速一致）。
    ///
    /// 网络读写全在后台线程，收到的整包丢进队列，主线程 Tick 里解析成指令；
    /// 状态帧由后台线程按 5 Hz 节奏自己发，不受渲染帧率影响。
    /// </summary>
    public sealed class UdpCommandSource : CommandSourceBase, ICommandModeHint, ICommandLeaseHint, ISimStateSink
    {
        public const int DefaultListenPort = 9001;
        public const int DefaultStatusPort = 9002;
        /// <summary>任务书要求状态帧 5 Hz。</summary>
        public const float DefaultStatusInterval = 0.2f;
        /// <summary>超过这么久没收到任何包，就算"对端不在线"（只影响显示与看门狗）。</summary>
        public const int PeerTimeoutMs = 3000;

        /// <summary>收包超时。用它当"心跳滴答"：既能及时退出，又不至于忙等占满 CPU。</summary>
        private const int ReceiveTimeoutMs = 20;
        /// <summary>待解析包上限，防止对端猛发把内存吃光。</summary>
        private const int MaxIncomingBacklog = 256;

        // ------------------------------------------------------------ 配置

        public int ListenPort { get; private set; }

        /// <summary>状态帧发到对端的哪个端口。任务书是 9002；填 0 = 回到对端发指令用的源端口。</summary>
        public int StatusPort = DefaultStatusPort;

        /// <summary>是否回状态帧（任务书里状态帧是"可选"的）。</summary>
        public bool SendStatusFrames = true;

        /// <summary>状态帧周期（秒），默认 0.2 = 5 Hz。</summary>
        public float StatusInterval = DefaultStatusInterval;

        /// <summary>左转/右转的语义：行进中转向 or 原地旋转。</summary>
        public WifiTurnMode TurnMode = WifiTurnMode.SteerWhileMoving;

        /// <summary>App 下发 100% 时对应的线速度 m/s（前进/后退）。</summary>
        public float MaxLinearSpeed = 1.0f;

        /// <summary>App 下发 100% 时对应的角速度 rad/s（左转/右转）。</summary>
        public float MaxAngularSpeed = 1.5f;

        /// <summary>回状态帧用的校验算法。Auto = 跟随对端识别结果（对称最不容易出错）。</summary>
        public WifiChecksum OutgoingChecksum = WifiChecksum.Auto;

        /// <summary>本链路是否需要心跳看门狗。</summary>
        public bool RequiresHeartbeatFlag;

        /// <summary>收到"停止"时是否顺带解除急停锁（默认不解除：急停必须显式解除）。</summary>
        public bool StopAlsoReleasesEmergency;

        /// <summary>
        /// 是否参与"指令租期"：App 按住前进时每 0.2 s 重发一次、最多 4 s，
        /// 停发之后接收端得自己停下，否则状态保持式会一直开下去。
        /// 默认开；在 WheelchairBootstrap 里用 wifiCommandLease 控制。
        /// </summary>
        public bool WantsCommandLease { get; set; }

        /// <summary>App 主界面按键是"按住就走、松开才停"的连续控制，所以默认状态保持式。</summary>
        public CommandMode PreferredCommandMode { get; set; }

        // ------------------------------------------------------------ 运行时

        private readonly WifiUdpFrameParser _parser = new WifiUdpFrameParser();
        private readonly ConcurrentQueue<byte[]> _incoming = new ConcurrentQueue<byte[]>();
        private readonly System.Diagnostics.Stopwatch _clock = new System.Diagnostics.Stopwatch();
        private readonly object _stateLock = new object();

        private UdpClient _client;
        private Thread _thread;
        private volatile bool _running;
        private volatile string _error;
        private volatile bool _peerKnown;
        private IPEndPoint _peer;
        private volatile int _lastPacketMs;
        private long _lastStatusMs;
        private int _statusSentCount;
        private WifiChecksum _resolvedOutgoingChecksum = WifiChecksum.Sum8;

        private WifiStatusData _statusSnapshot;

        // 诊断计数：只在主线程读写
        private int _commandMessages;
        private int _unknownCodeFrames;
        private int _tooShortFrames;
        private int _reservedFrames;
        private int _peerStatusFrames;
        private int _unknownTypeFrames;

        private string _lastCommandText = "-";

        public UdpCommandSource(int listenPort = DefaultListenPort, int statusPort = DefaultStatusPort,
                                float maxLinearSpeed = 1.0f, float maxAngularSpeed = 1.5f)
            : base("WiFi/UDP :" + listenPort, ProtocolMode.Binary)
        {
            ListenPort = listenPort;
            StatusPort = statusPort;
            MaxLinearSpeed = maxLinearSpeed;
            MaxAngularSpeed = maxAngularSpeed;
            PreferredCommandMode = CommandMode.Latch;
            WantsCommandLease = true;
            IsConnected = false;
            StatusDetail = "未启动";
        }

        public override bool CanSendToHost { get { return false; } }

        public override bool RequiresHeartbeat { get { return RequiresHeartbeatFlag; } }

        /// <summary>用任务书 0x03 状态帧回传，而不是工程内部的状态帧，所以不参与 Hub 的通用回传。</summary>
        public string LastCommandText { get { return _lastCommandText; } }

        public int FramesReceived { get { return _parser.FramesReceived; } }
        public int CommandFrames { get { return _parser.CommandFrames; } }
        public int ChecksumFailures { get { return _parser.ChecksumFailures; } }
        public int LengthMismatches { get { return _parser.LengthMismatches; } }
        public string ChecksumDetectionText { get { return _parser.DetectionText; } }

        // ------------------------------------------------------------ 连接

        public override void Connect()
        {
            if (_running) return;

            try
            {
                UdpClient client = new UdpClient();
                client.Client.Bind(new IPEndPoint(IPAddress.Any, ListenPort));
                client.EnableBroadcast = true;                       // 对端可能往广播地址发
                client.Client.ReceiveTimeout = ReceiveTimeoutMs;
                _client = client;

                _clock.Start();
                _lastStatusMs = 0;
                _error = null;
                _running = true;
                _thread = new Thread(Loop) { IsBackground = true, Name = "WheelchairUdp" };
                _thread.Start();

                IsConnected = false;
                StatusDetail = "监听 0.0.0.0:" + ListenPort + "，等待 App 指令…";
            }
            catch (Exception e)
            {
                _running = false;
                IsConnected = false;
                _error = e.Message;
                StatusDetail = "启动失败：" + e.Message
                    + "（端口被占用？改 Bootstrap 里的 wifiListenPort，或关掉占用它的程序）";
            }
        }

        public override void Disconnect()
        {
            _running = false;

            UdpClient client = _client;
            _client = null;
            if (client != null)
            {
                try { client.Close(); } catch (Exception) { }
            }

            Thread thread = _thread;
            _thread = null;
            if (thread != null)
            {
                try { thread.Join(500); } catch (Exception) { }
            }

            byte[] dropped;
            while (_incoming.TryDequeue(out dropped)) { }
            _parser.Reset();
            _peer = null;
            _peerKnown = false;
            _clock.Reset();
            IsConnected = false;
            StatusDetail = "已停止";
        }

        // ------------------------------------------------------------ 每帧（主线程）

        public override void Tick(float deltaTime, Queue<CommandMessage> output)
        {
            byte[] packet;
            int drained = 0;
            while (drained < 64 && _incoming.TryDequeue(out packet))
            {
                _parser.Append(packet, packet.Length);
                drained++;
            }

            // 回状态帧用对端同一种校验算法（任务书没写算法，对称最不容易出错）。
            // 这里在主线程算好存下来，后台线程只读，避免两边同时碰解析器。
            _resolvedOutgoingChecksum = OutgoingChecksum == WifiChecksum.Auto
                ? (_parser.LockedChecksum == WifiChecksum.Auto ? WifiChecksum.Sum8 : _parser.LockedChecksum)
                : OutgoingChecksum;

            WifiFrame frame;
            while (_parser.TryRead(out frame)) Handle(frame, output);

            IsConnected = _peerKnown && ((int)_clock.ElapsedMilliseconds - _lastPacketMs) <= PeerTimeoutMs;
            StatusDetail = BuildStatusDetail();
        }

        private void Handle(WifiFrame frame, Queue<CommandMessage> output)
        {
            switch (frame.Type)
            {
                case (byte)WifiFrameType.Command:
                    HandleCommand(frame, output);
                    return;

                case (byte)WifiFrameType.Reserved:
                    // 任务书没定义 0x02：收到只当保活，不产生任何动作
                    _reservedFrames++;
                    return;

                case (byte)WifiFrameType.Status:
                    // 状态帧是仿真端发给 App 的，App 不该回发；收到只记数，不当指令执行
                    _peerStatusFrames++;
                    return;

                default:
                    _unknownTypeFrames++;
                    return;
            }
        }

        /// <summary>
        /// 指令帧数据区： [0] 指令码，[1]（可选）速度/转向强度 0~100。
        /// 没带速度（缺省或 0）时不动速度参数，按当前速度走；带了就先改速度再执行动作。
        /// </summary>
        private void HandleCommand(WifiFrame frame, Queue<CommandMessage> output)
        {
            if (frame.DataLength < 1)
            {
                _tooShortFrames++;
                return;
            }

            WifiCommandCode code = (WifiCommandCode)frame.Data[0];
            WheelchairCommand command;
            if (!WifiUdpProtocol.TryMapCommand(code, TurnMode, out command))
            {
                _unknownCodeFrames++;
                return;
            }

            bool speedSpecified;
            int percent = WifiUdpProtocol.DecodeSpeedPercent(frame.Data, frame.DataLength, out speedSpecified);

            switch (code)
            {
                case WifiCommandCode.Forward:
                case WifiCommandCode.Backward:
                    if (speedSpecified)
                        output.Enqueue(new CommandMessage(WheelchairCommand.SetSpeed, MaxLinearSpeed * percent / 100f));
                    break;

                case WifiCommandCode.TurnLeft:
                case WifiCommandCode.TurnRight:
                    if (speedSpecified)
                        output.Enqueue(new CommandMessage(WheelchairCommand.SetTurnRate, MaxAngularSpeed * percent / 100f));
                    break;

                case WifiCommandCode.Stop:
                    if (StopAlsoReleasesEmergency)
                        output.Enqueue(new CommandMessage(WheelchairCommand.ReleaseEmergency));
                    break;
            }

            output.Enqueue(new CommandMessage(command));
            _commandMessages++;
            _lastCommandText = string.Format("0x{0:X2} {1}{2}", (byte)code, command,
                speedSpecified ? string.Format(" {0}%", percent) : string.Empty);
        }

        private string BuildStatusDetail()
        {
            if (!string.IsNullOrEmpty(_error)) return "出错：" + _error;

            string peer = _peerKnown
                ? (_peer == null ? "?" : _peer.Address + ":" + _peer.Port)
                : "等待首个数据包";
            string link = IsConnected ? "已连接 " + peer : "未连接（" + peer + "）";

            string status = "状态帧 ";
            if (!SendStatusFrames) status += "已关闭";
            else if (!_peerKnown) status += "待对端出现";
            else status += string.Format("{0:F1} Hz -> 端口 {1}（已发 {2} 帧）",
                1f / Mathf.Max(0.05f, StatusInterval), StatusPort <= 0 ? "对端源端口" : StatusPort.ToString(),
                Volatile.Read(ref _statusSentCount));

            return string.Format(
                "监听 {0}:{1} · {2}\n收到 {3} 帧（指令 {4}）· 校验 {5}\n{6} · 左转/右转＝{7}\n丢弃：校验 {8} / 长度不符 {9} / 未知码 {10} / 未知类型 {11}\n最近：{12}",
                "0.0.0.0", ListenPort, link,
                _parser.FramesReceived, _parser.CommandFrames, _parser.DetectionText,
                status, TurnMode == WifiTurnMode.RotateInPlace ? "原地旋转" : "行进中转向",
                _parser.ChecksumFailures, _parser.LengthMismatches, _unknownCodeFrames, _unknownTypeFrames,
                _lastCommandText);
        }

        /// <summary>任务书 0x03 状态帧由本类自己发，所以不参与 Hub 的通用状态回传。</summary>
        public override void SendToHost(byte[] data)
        {
            // 故意留空：CanSendToHost = false，Hub 不会调用到这里。
        }

        // ------------------------------------------------ 状态快照（主线程写入）

        public void OnSimState(WheelchairState state, Vector3 position, Vector3 forward)
        {
            WifiStatusData snapshot = new WifiStatusData();
            snapshot.Flags = 0;
            if (state.EmergencyStop) snapshot.Flags |= WifiStatusData.FlagEmergency;
            if (state.Fault) snapshot.Flags |= WifiStatusData.FlagFault;
            if (state.Stepping) snapshot.Flags |= WifiStatusData.FlagStepping;
            if (Mathf.Abs(state.LinearSpeed) > 0.01f || Mathf.Abs(state.AngularSpeed) > 0.01f)
                snapshot.Flags |= WifiStatusData.FlagMoving;

            snapshot.ActionCode = WifiUdpProtocol.ActionCodeFor(state.ActiveCommand);
            // 状态帧里用 mm/s 与 0.001 rad/s：分辨率比内部用的小数更细，且不含浮点传输的坑
            snapshot.LinearMillimetersPerSecond = WifiUdpProtocol.ClampToInt16((int)Math.Round(state.LinearSpeed * 1000f));
            snapshot.AngularMilliRadiansPerSecond = WifiUdpProtocol.ClampToInt16((int)Math.Round(state.AngularSpeed * 1000f));
            snapshot.XCentimeters = WifiUdpProtocol.ClampToInt16((int)Math.Round(position.x * 100f));
            snapshot.ZCentimeters = WifiUdpProtocol.ClampToInt16((int)Math.Round(position.z * 100f));
            snapshot.SpeedPercent = (byte)Mathf.Clamp(Mathf.Abs(state.LinearSpeed) / Mathf.Max(0.01f, MaxLinearSpeed) * 100f, 0f, 100f);

            lock (_stateLock) _statusSnapshot = snapshot;
        }

        // ------------------------------------------------------------ 网络线程

        private void Loop()
        {
            try
            {
                while (_running)
                {
                    UdpClient client = _client;
                    if (client == null) break;

                    try
                    {
                        IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                        byte[] packet = client.Receive(ref remote);

                        if (packet != null && packet.Length > 0)
                        {
                            _peer = remote;                              // 状态帧默认回给最后发指令的那台机器
                            _peerKnown = true;
                            _lastPacketMs = (int)_clock.ElapsedMilliseconds;

                            byte[] stale;
                            while (_incoming.Count >= MaxIncomingBacklog && _incoming.TryDequeue(out stale)) { }
                            _incoming.Enqueue(packet);
                        }
                    }
                    catch (SocketException socketError)
                    {
                        // 收包超时是正常的：我们就是靠它每 20 ms 醒一次去发状态帧
                        if (!_running) break;
                        if (socketError.SocketErrorCode != SocketError.TimedOut)
                        {
                            _error = socketError.Message;
                            Thread.Sleep(50);
                        }
                    }
                    catch (ObjectDisposedException)
                    {
                        break;                                           // Disconnect() 关掉了套接字
                    }
                    catch (Exception e)
                    {
                        if (!_running) break;
                        _error = e.Message;
                        Thread.Sleep(50);
                    }

                    SendStatusIfDue(client);
                }
            }
            catch (Exception e)
            {
                _error = e.Message;
            }
            finally
            {
                _running = false;
            }
        }

        /// <summary>到点就把最新状态快照打成任务书 0x03 帧发出去（任务书要求 5 Hz）。</summary>
        private void SendStatusIfDue(UdpClient client)
        {
            if (!SendStatusFrames || !_peerKnown) return;

            long now = _clock.ElapsedMilliseconds;
            long intervalMs = (long)(Mathf.Max(0.05f, StatusInterval) * 1000f);
            if (now - _lastStatusMs < intervalMs) return;
            _lastStatusMs = now;

            IPEndPoint target = StatusTarget();
            if (target == null) return;

            WifiStatusData snapshot;
            lock (_stateLock) snapshot = _statusSnapshot;
            snapshot.TimestampMilliseconds = (uint)now;

            byte[] frame = WifiUdpProtocol.EncodeStatusFrame(snapshot, _resolvedOutgoingChecksum);

            try
            {
                client.Send(frame, frame.Length, target);
                Interlocked.Increment(ref _statusSentCount);
            }
            catch (Exception e)
            {
                _error = e.Message;
            }
        }

        private IPEndPoint StatusTarget()
        {
            IPEndPoint peer = _peer;
            if (peer == null) return null;
            if (StatusPort <= 0) return new IPEndPoint(peer.Address, peer.Port);   // 回到对端源端口
            return new IPEndPoint(peer.Address, StatusPort);                       // 任务书：9002
        }
    }
}
