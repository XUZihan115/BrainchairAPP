// 串口支持依赖 System.IO.Ports，而它不在 .NET Standard 里。
//
// Unity 6 默认的 Api Compatibility Level 是 ".NET Standard 2.1"。在这个级别下，Unity
// 不会把 System.IO.Ports 注入运行时代码集（Assembly-CSharp），因此本文件必须能自动
// 降级为空实现，否则整个工程都会编译失败，连 Play 模式都进不去。
//
// 想真正启用串口：Player Settings -> Other Settings -> Api Compatibility Level
// 改成 ".NET Framework"，然后回到 Unity 按 Ctrl+R 重新编译。
// 若你的环境判断有误，可在 Player Settings -> Scripting Define Symbols 里加
// WHEELCHAIR_NO_SERIAL（强制关闭）或 WHEELCHAIR_SERIAL（强制开启）。
#if !WHEELCHAIR_SERIAL && !WHEELCHAIR_NO_SERIAL && (UNITY_STANDALONE || UNITY_EDITOR) && !UNITY_ANDROID && !NET_STANDARD_2_0 && !NET_STANDARD_2_1
#define WHEELCHAIR_SERIAL
#endif

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Threading;

#if WHEELCHAIR_SERIAL
using System.IO.Ports;
#endif

namespace WheelchairSim
{
    /// <summary>
    /// 串口指令源。
    ///
    /// 这是 PC 上最省事的"蓝牙"通道：把 HC-05 / HC-06 之类的蓝牙模块与电脑配对后，
    /// Windows 会自动生成一个虚拟串口（比如 COM5），真·蓝牙链路就通了，
    /// 完全不需要任何第三方 Unity 插件。手机端同理可用"蓝牙串口 App"调试。
    ///
    /// 串口功能需要 Api Compatibility Level = .NET Framework。在默认的 .NET Standard 2.1
    /// 下本类会自动降级为空实现：工程照常编译运行，只是串口通道不可用，HUD 会显示原因。
    /// </summary>
    public sealed class SerialCommandSource : CommandSourceBase
    {
#if WHEELCHAIR_SERIAL
        private readonly string _portName;
        private readonly int _baudRate;
        private readonly ConcurrentQueue<byte[]> _incoming = new ConcurrentQueue<byte[]>();
        private readonly ConcurrentQueue<byte[]> _outgoing = new ConcurrentQueue<byte[]>();
        private readonly byte[] _readBuffer = new byte[512];

        private SerialPort _port;
        private Thread _thread;
        private volatile bool _running;
        private volatile string _error;

        public SerialCommandSource(string portName, int baudRate = 115200, ProtocolMode mode = ProtocolMode.Auto)
            : base("串口 " + portName, mode)
        {
            _portName = portName;
            _baudRate = baudRate;
            IsConnected = false;
            StatusDetail = "未打开";
        }

        /// <summary>列出本机所有串口名，用于排查蓝牙虚拟串口。</summary>
        public static string[] ListPorts()
        {
            try { return SerialPort.GetPortNames(); }
            catch (Exception) { return new string[0]; }
        }

        public override void Connect()
        {
            if (_running) return;
            try
            {
                _port = new SerialPort(_portName, _baudRate, Parity.None, 8, StopBits.One);
                _port.ReadTimeout = 50;
                _port.WriteTimeout = 200;
                _port.DtrEnable = true;
                _port.Open();

                _running = true;
                _thread = new Thread(Loop) { IsBackground = true, Name = "WheelchairSerial" };
                _thread.Start();

                IsConnected = true;
                StatusDetail = _portName + " @ " + _baudRate;
            }
            catch (Exception e)
            {
                _running = false;
                IsConnected = false;
                _error = e.Message;
                StatusDetail = "打开失败：" + e.Message;
            }
        }

        public override void Disconnect()
        {
            _running = false;
            try { if (_port != null && _port.IsOpen) _port.Close(); } catch (Exception) { }
            _port = null;
            IsConnected = false;
            StatusDetail = "已关闭";
        }

        public override void Tick(float deltaTime, Queue<CommandMessage> output)
        {
            byte[] chunk;
            int drained = 0;
            while (drained < 64 && _incoming.TryDequeue(out chunk))
            {
                Decoder.Append(chunk, chunk.Length);
                drained++;
            }

            DrainDecoder(output);

            IsConnected = _port != null && _port.IsOpen;
            if (!IsConnected && _error != null) StatusDetail = "打开失败：" + _error;
        }

        public override void SendToHost(byte[] data)
        {
            if (data != null && data.Length > 0 && IsConnected) _outgoing.Enqueue(data);
        }

        private void Loop()
        {
            try
            {
                while (_running)
                {
                    byte[] payload;
                    int guard = 0;
                    while (guard++ < 32 && _outgoing.TryDequeue(out payload))
                    {
                        try { _port.Write(payload, 0, payload.Length); }
                        catch (Exception) { }
                    }

                    try
                    {
                        if (_port.BytesToRead > 0)
                        {
                            int n = _port.Read(_readBuffer, 0, _readBuffer.Length);
                            if (n > 0)
                            {
                                byte[] copy = new byte[n];
                                Array.Copy(_readBuffer, copy, n);
                                _incoming.Enqueue(copy);
                            }
                        }
                        else
                        {
                            Thread.Sleep(5);
                        }
                    }
                    catch (TimeoutException) { }
                    catch (Exception e)
                    {
                        _error = e.Message;
                        Thread.Sleep(50);
                    }
                }
            }
            catch (Exception e)
            {
                _error = e.Message;
            }
        }
#else
        public SerialCommandSource(string portName, int baudRate = 115200, ProtocolMode mode = ProtocolMode.Auto)
            : base("串口 " + portName, mode)
        {
            IsConnected = false;
#if (UNITY_STANDALONE || UNITY_EDITOR) && !UNITY_ANDROID
            StatusDetail = "串口未启用：Api Compatibility Level 需为 .NET Framework";
#else
            StatusDetail = "当前平台不支持 System.IO.Ports 串口";
#endif
        }

        public static string[] ListPorts() { return new string[0]; }
        public override void Connect() { }
        public override void Disconnect() { }
        public override void Tick(float deltaTime, Queue<CommandMessage> output) { }
#endif
    }
}