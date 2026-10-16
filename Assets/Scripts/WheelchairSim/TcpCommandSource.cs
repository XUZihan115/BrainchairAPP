using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;

namespace WheelchairSim
{
    /// <summary>
    /// TCP 指令源（内置服务器，默认监听 0.0.0.0:8888）。
    /// 用途：
    ///   1) 开发阶段用电脑/手机上的"网络调试助手"直接发指令，验证协议；
    ///   2) 手机 App 通过 WiFi 控制时的正式通道；
    ///   3) 用配套的 Tools/host_test_client.py 做自动化测试。
    /// 所有网络读写都在后台线程完成，收到的字节丢进队列，主线程 Tick 里再解析。
    /// </summary>
    public sealed class TcpCommandSource : CommandSourceBase
    {
        private readonly int _port;
        private readonly ConcurrentQueue<byte[]> _incoming = new ConcurrentQueue<byte[]>();
        private readonly ConcurrentQueue<byte[]> _outgoing = new ConcurrentQueue<byte[]>();
        private readonly byte[] _readBuffer = new byte[1024];

        /// <summary>待发送队列上限。客户端不读数据时，遥测帧会无限堆积，必须封顶。</summary>
        private const int MaxOutgoingBacklog = 256;

        private TcpListener _listener;
        private TcpClient _client;
        private NetworkStream _stream;
        private Thread _thread;
        private volatile bool _running;
        private volatile string _error;

        public TcpCommandSource(int port, ProtocolMode mode = ProtocolMode.Auto)
            : base("TCP :" + port, mode)
        {
            _port = port;
            IsConnected = false;
            StatusDetail = "未启动";
        }

        public override void Connect()
        {
            if (_running) return;
            try
            {
                _listener = new TcpListener(IPAddress.Any, _port);
                _listener.Start();
                _running = true;
                _thread = new Thread(Loop) { IsBackground = true, Name = "WheelchairTcp" };
                _thread.Start();
                StatusDetail = "监听 0.0.0.0:" + _port + "，等待客户端…";
            }
            catch (Exception e)
            {
                _running = false;
                IsConnected = false;
                _error = e.Message;
                StatusDetail = "启动失败：" + e.Message;
            }
        }

        public override void Disconnect()
        {
            _running = false;
            try { if (_stream != null) _stream.Close(); } catch (Exception) { }
            try { if (_client != null) _client.Close(); } catch (Exception) { }
            try { if (_listener != null) _listener.Stop(); } catch (Exception) { }
            _stream = null;
            _client = null;
            _listener = null;
            IsConnected = false;
            StatusDetail = "已停止";
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

            IsConnected = _client != null && _client.Connected;
            StatusDetail = IsConnected
                ? "已连接客户端 " + RemoteEndPointText()
                : (_error != null ? "启动失败：" + _error : "等待客户端连接…");
        }

        private string RemoteEndPointText()
        {
            try
            {
                if (_client != null && _client.Client != null && _client.Client.RemoteEndPoint != null)
                    return _client.Client.RemoteEndPoint.ToString();
            }
            catch (Exception) { }
            return "?";
        }

        public override void SendToHost(byte[] data)
        {
            if (data == null || data.Length == 0) return;
            if (_client == null) return;

            // 客户端长时间不读数据时，遥测帧会越堆越多，最终把内存吃光。
            // 这里保留最近的若干帧、丢掉最旧的：遥测是周期性重发的，丢旧数据无影响。
            byte[] dropped;
            while (_outgoing.Count >= MaxOutgoingBacklog)
            {
                if (!_outgoing.TryDequeue(out dropped)) break;
            }

            _outgoing.Enqueue(data);
        }

        private void Loop()
        {
            try
            {
                while (_running)
                {
                    if (_client == null)
                    {
                        if (_listener != null && _listener.Pending())
                        {
                            _client = _listener.AcceptTcpClient();
                            _client.NoDelay = true;
                            _stream = _client.GetStream();
                        }
                        else
                        {
                            Thread.Sleep(20);
                            continue;
                        }
                    }

                    // 1) 发
                    byte[] payload;
                    int guard = 0;
                    while (guard++ < 64 && _outgoing.TryDequeue(out payload))
                    {
                        try
                        {
                            _stream.Write(payload, 0, payload.Length);
                        }
                        catch (Exception)
                        {
                            DropClient();
                            break;
                        }
                    }

                    if (_client == null) continue;

                    // 2) 收
                    try
                    {
                        if (_stream.DataAvailable)
                        {
                            int n = _stream.Read(_readBuffer, 0, _readBuffer.Length);
                            if (n <= 0) DropClient();
                            else
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
                    catch (Exception)
                    {
                        DropClient();
                    }
                }
            }
            catch (Exception e)
            {
                _error = e.Message;
                _running = false;
                IsConnected = false;
            }
        }

        private void DropClient()
        {
            try { if (_stream != null) _stream.Close(); } catch (Exception) { }
            try { if (_client != null) _client.Close(); } catch (Exception) { }
            _stream = null;
            _client = null;
            IsConnected = false;
            StatusDetail = "客户端已断开，等待重连…";
        }
    }
}
