using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Text;

namespace WheelchairSim
{
    /// <summary>
    /// 蓝牙链路抽象。真正干活的是各平台实现：
    ///   - AndroidSppLink     : Android 经典蓝牙 SPP（HC-05/HC-06 等模块）
    ///   - MockBluetoothLink  : 编辑器/PC 上的模拟链路，用来在没有硬件时跑通全流程
    /// 以后要换 BLE（低功耗蓝牙），只要再写一个实现即可，其它代码不用动。
    /// </summary>
    public interface IBluetoothLink
    {
        string Name { get; }
        bool IsConnected { get; }
        void Connect();
        void Disconnect();
        void Send(byte[] data);
        /// <summary>读取数据；返回读取到的字节数，0 表示暂时没有数据。</summary>
        int Read(byte[] buffer, int offset, int count);
        string Detail { get; }
    }

    /// <summary>没有真机时用的模拟蓝牙，配合 HUD 上的输入框即可验证整条链路。</summary>
    public sealed class MockBluetoothLink : IBluetoothLink
    {
        private readonly ConcurrentQueue<byte> _rx = new ConcurrentQueue<byte>();
        private readonly ConcurrentQueue<byte[]> _tx = new ConcurrentQueue<byte[]>();
        private bool _connected;

        public MockBluetoothLink(string name = "MockDevice")
        {
            Name = name;
            Detail = "模拟链路（编辑器内测试用）";
        }

        public string Name { get; private set; }
        public bool IsConnected { get { return _connected; } }
        public string Detail { get; private set; }

        public void Connect() { _connected = true; Detail = "模拟已连接"; }
        public void Disconnect() { _connected = false; Detail = "模拟已断开"; }

        public void Send(byte[] data)
        {
            if (data == null) return;
            _tx.Enqueue(data);
        }

        public int Read(byte[] buffer, int offset, int count)
        {
            int n = 0;
            byte b;
            while (n < count && _rx.TryDequeue(out b)) buffer[offset + n++] = b;
            return n;
        }

        /// <summary>把一个二进制帧"喂"进来，模拟设备下发指令。</summary>
        public void InjectFrame(WheelchairCommand command, float arg = 0f)
        {
            InjectBytes(WheelchairProtocol.EncodeFrame(command, arg));
        }

        /// <summary>把一段文本"喂"进来，例如 "F\n"。</summary>
        public void InjectText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            InjectBytes(Encoding.ASCII.GetBytes(text));
        }

        public void InjectBytes(byte[] data)
        {
            if (data == null) return;
            for (int i = 0; i < data.Length; i++) _rx.Enqueue(data[i]);
        }

        /// <summary>取出最近一次"发给设备"的数据，用于断言/调试。</summary>
        public bool TryTakeSent(out byte[] data) { return _tx.TryDequeue(out data); }
    }

    /// <summary>
    /// 蓝牙指令源：把任意 IBluetoothLink 包装成 ICommandSource。
    /// </summary>
    public sealed class BluetoothCommandSource : CommandSourceBase
    {
        private readonly IBluetoothLink _link;
        private readonly bool _requiresHeartbeat;
        private readonly byte[] _readBuffer = new byte[512];

        public BluetoothCommandSource(IBluetoothLink link, ProtocolMode mode = ProtocolMode.Auto,
                                      bool requiresHeartbeat = true)
            : base("蓝牙 " + link.Name, mode)
        {
            _link = link;
            _requiresHeartbeat = requiresHeartbeat;
            IsConnected = false;
            StatusDetail = "未连接";
        }

        public IBluetoothLink Link { get { return _link; } }

        public override bool RequiresHeartbeat { get { return _requiresHeartbeat; } }

        public override void Connect()
        {
            try
            {
                _link.Connect();
                IsConnected = _link.IsConnected;
                StatusDetail = _link.Detail;
            }
            catch (Exception e)
            {
                IsConnected = false;
                StatusDetail = "连接失败：" + e.Message;
            }
        }

        public override void Disconnect()
        {
            try { _link.Disconnect(); } catch (Exception) { }
            IsConnected = false;
            StatusDetail = "已断开";
        }

        public override void Tick(float deltaTime, Queue<CommandMessage> output)
        {
            if (_link.IsConnected)
            {
                for (int guard = 0; guard < 32; guard++)
                {
                    int n = _link.Read(_readBuffer, 0, _readBuffer.Length);
                    if (n <= 0) break;
                    Decoder.Append(_readBuffer, n);
                }
            }

            IsConnected = _link.IsConnected;
            StatusDetail = _link.Detail;
            DrainDecoder(output);
        }

        public override void SendToHost(byte[] data)
        {
            if (data != null && data.Length > 0 && _link.IsConnected) _link.Send(data);
        }
    }
}
