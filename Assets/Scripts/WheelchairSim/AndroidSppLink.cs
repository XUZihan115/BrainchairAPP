using System;
using System.Collections.Generic;

#if UNITY_ANDROID && !UNITY_EDITOR
using System.Collections.Concurrent;
using System.Threading;
using UnityEngine;
#endif

namespace WheelchairSim
{
    /// <summary>
    /// Android 经典蓝牙 SPP 链路（HC-05 / HC-06 / 大多数康复设备蓝牙模块都用这个协议）。
    ///
    /// 使用前必须准备的材料：
    ///   1) Android 真机 + 已配对的蓝牙模块（先系统里配对，再填 MAC 地址）；
    ///   2) manifest 里必须声明蓝牙权限。本工程已经用"Android Library 插件"的方式准备好了，
    ///      不需要你去改 Player Settings：
    ///        Assets/Plugins/Android/BluetoothPermissions.androidlib/src/main/AndroidManifest.xml
    ///      它声明了 BLUETOOTH / BLUETOOTH_ADMIN（Android 11 及以下，maxSdkVersion 30）
    ///      和 BLUETOOTH_CONNECT / BLUETOOTH_SCAN（Android 12 及以上，运行时申请）。
    ///   3) Android 12 及以上，权限必须由用户在系统弹框里点"允许"才行，而弹框是异步的：
    ///      第一次点「连接」只负责弹框并提示，用户同意后再点一次即可（本类已按这个流程处理）。
    ///   4) 若目标是 BLE（低功耗蓝牙）设备，本类不适用，需要改用 BLE 流程或第三方插件。
    ///
    /// 注意：这条路必须打包到 Android 真机上才能验证，编辑器里会自动退化成空实现。
    /// 如果只想在 PC 上先跑通"蓝牙"，请直接用 SerialCommandSource + 配对后的虚拟串口。
    /// </summary>
    public sealed class AndroidSppLink : IBluetoothLink
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        private const string SppUuid = "00001101-0000-1000-8000-00805F9B34FB";
        private const string ConnectPermission = "android.permission.BLUETOOTH_CONNECT";
        private const string ScanPermission = "android.permission.BLUETOOTH_SCAN";

        private readonly string _address;
        private readonly ConcurrentQueue<byte> _rx = new ConcurrentQueue<byte>();
        private readonly ConcurrentQueue<byte[]> _tx = new ConcurrentQueue<byte[]>();

        private AndroidJavaObject _adapter;
        private AndroidJavaObject _socket;
        private AndroidJavaObject _input;
        private AndroidJavaObject _output;
        private Thread _readThread;
        private volatile bool _running;
        private volatile bool _connected;
        private volatile string _detail = "未连接";

        public AndroidSppLink(string deviceName, string deviceAddress)
        {
            Name = string.IsNullOrEmpty(deviceName) ? deviceAddress : deviceName;
            _address = deviceAddress;
        }

        public string Name { get; private set; }
        public bool IsConnected { get { return _connected; } }
        public string Detail { get { return _detail; } }

        /// <summary>列出系统里已经配对过的蓝牙设备，格式 "名称|MAC"。</summary>
        public static string[] ListPairedDevices()
        {
            try
            {
                // Android 12 及以上没有 BLUETOOTH_CONNECT 时，getBondedDevices() 会直接抛异常，
                // 表现为"列表是空的"却没有任何提示。所以这里先说清楚原因，并顺手把弹框弹出来。
                if (!HasPermission(ConnectPermission))
                {
                    EnsurePermissions();
                    Debug.LogWarning("[Wheelchair] 还没有蓝牙权限（BLUETOOTH_CONNECT）：已在系统里弹出授权框，" +
                                     "同意后再点一次「刷新已配对设备」。");
                    return new string[0];
                }

                using (AndroidJavaClass adapterClass = new AndroidJavaClass("android.bluetooth.BluetoothAdapter"))
                {
                    AndroidJavaObject adapter = adapterClass.CallStatic<AndroidJavaObject>("getDefaultAdapter");
                    if (adapter == null) return new string[0];

                    AndroidJavaObject bonded = adapter.Call<AndroidJavaObject>("getBondedDevices");
                    if (bonded == null) return new string[0];

                    List<string> result = new List<string>();
                    AndroidJavaObject iterator = bonded.Call<AndroidJavaObject>("iterator");
                    while (iterator.Call<bool>("hasNext"))
                    {
                        AndroidJavaObject device = iterator.Call<AndroidJavaObject>("next");
                        string name = device.Call<string>("getName");
                        string address = device.Call<string>("getAddress");
                        result.Add(name + "|" + address);
                    }
                    return result.ToArray();
                }
            }
            catch (Exception)
            {
                return new string[0];
            }
        }

        public void Connect()
        {
            if (_connected) return;

            try
            {
                // 权限弹框是异步的：用户点「允许」需要时间，而这里一旦继续往下走就会抛
                // SecurityException（表现为"连接失败"，但用户其实刚看到弹框，很容易误解）。
                // 所以没拿到权限时只负责弹框 + 给一句明确提示，用户同意后再点一次连接。
                if (!HasBluetoothPermissions())
                {
                    EnsurePermissions();
                    _detail = "请在系统弹框里允许“附近的设备”权限，然后再次点「连接」";
                    return;
                }

                using (AndroidJavaClass adapterClass = new AndroidJavaClass("android.bluetooth.BluetoothAdapter"))
                {
                    _adapter = adapterClass.CallStatic<AndroidJavaObject>("getDefaultAdapter");
                }

                if (_adapter == null)
                {
                    _detail = "本机没有蓝牙适配器";
                    return;
                }

                if (!_adapter.Call<bool>("isEnabled"))
                {
                    _detail = "蓝牙未开启，请先在系统设置里打开蓝牙";
                    return;
                }

                _adapter.Call<bool>("cancelDiscovery");

                AndroidJavaObject device = _adapter.Call<AndroidJavaObject>("getRemoteDevice", _address);
                if (device == null)
                {
                    _detail = "找不到已配对设备 " + _address;
                    return;
                }

                using (AndroidJavaClass uuidClass = new AndroidJavaClass("java.util.UUID"))
                {
                    AndroidJavaObject uuid = uuidClass.CallStatic<AndroidJavaObject>("fromString", SppUuid);
                    _socket = device.Call<AndroidJavaObject>("createRfcommSocketToServiceRecord", uuid);
                }

                _socket.Call("connect");
                _input = _socket.Call<AndroidJavaObject>("getInputStream");
                _output = _socket.Call<AndroidJavaObject>("getOutputStream");

                _running = true;
                _connected = true;
                _readThread = new Thread(ReadLoop) { IsBackground = true, Name = "WheelchairBtSpp" };
                _readThread.Start();

                _detail = "已连接 " + Name + " (" + _address + ")";
            }
            catch (Exception e)
            {
                _detail = "连接失败：" + e.Message;
                _connected = false;
                Cleanup();
            }
        }

        public void Disconnect()
        {
            _running = false;
            _connected = false;
            _detail = "已断开";
            Cleanup();
        }

        public void Send(byte[] data)
        {
            if (data == null || data.Length == 0 || !_connected) return;
            _tx.Enqueue(data);
        }

        public int Read(byte[] buffer, int offset, int count)
        {
            int n = 0;
            byte b;
            while (n < count && _rx.TryDequeue(out b)) buffer[offset + n++] = b;
            return n;
        }

        private void ReadLoop()
        {
            try
            {
                while (_running)
                {
                    byte[] payload;
                    int guard = 0;
                    while (guard++ < 32 && _tx.TryDequeue(out payload))
                    {
                        try { _output.Call("write", payload); }
                        catch (Exception) { _running = false; break; }
                    }

                    try
                    {
                        int available = _input.Call<int>("available");
                        if (available > 0)
                        {
                            byte[] buffer = new byte[Math.Min(available, 256)];
                            int n = _input.Call<int>("read", buffer);
                            for (int i = 0; i < n; i++) _rx.Enqueue(buffer[i]);
                        }
                        else
                        {
                            Thread.Sleep(5);
                        }
                    }
                    catch (Exception)
                    {
                        Thread.Sleep(10);
                    }
                }
            }
            catch (Exception e)
            {
                _detail = "读取异常：" + e.Message;
            }
            finally
            {
                _connected = false;
            }
        }

        private void Cleanup()
        {
            try { if (_input != null) _input.Call("close"); } catch (Exception) { }
            try { if (_output != null) _output.Call("close"); } catch (Exception) { }
            try { if (_socket != null) _socket.Call("close"); } catch (Exception) { }
            _input = null;
            _output = null;
            _socket = null;
        }

        private static void EnsurePermissions()
        {
            RequestPermission(ConnectPermission);
            RequestPermission(ScanPermission);
        }

        private static bool HasBluetoothPermissions()
        {
            return HasPermission(ConnectPermission) && HasPermission(ScanPermission);
        }

        private static bool HasPermission(string permission)
        {
            try
            {
                return UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission);
            }
            catch (Exception)
            {
                // Android 11 及以下没有这套运行时权限，查询会失败：此时按"已授权"处理。
                return true;
            }
        }

        private static void RequestPermission(string permission)
        {
            try
            {
                if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(permission))
                    UnityEngine.Android.Permission.RequestUserPermission(permission);
            }
            catch (Exception) { }
        }
#else
        public AndroidSppLink(string deviceName, string deviceAddress)
        {
            Name = string.IsNullOrEmpty(deviceName) ? deviceAddress : deviceName;
        }

        public string Name { get; private set; }
        public bool IsConnected { get { return false; } }
        public string Detail { get { return "Android SPP 仅在 Android 真机可用（编辑器会自动空转）"; } }

        public static string[] ListPairedDevices() { return new string[0]; }
        public void Connect() { }
        public void Disconnect() { }
        public void Send(byte[] data) { }
        public int Read(byte[] buffer, int offset, int count) { return 0; }
#endif
    }
}
