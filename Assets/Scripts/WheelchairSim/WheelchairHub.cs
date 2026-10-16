using System.Collections.Generic;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 系统中枢：把所有指令来源汇总成一条指令流，经过安全守卫后交给运动控制器，
    /// 并定时把轮椅状态回传给所有可发送的来源。
    ///
    ///   指令源(蓝牙/串口/TCP/本地) --&gt; WheelchairHub --&gt; SafetyGuard --&gt; WheelchairController
    ///                                        |
    ///                                        +--&gt; 状态帧回传
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WheelchairHub : MonoBehaviour
    {
        public static WheelchairHub Instance { get; private set; }

        [Header("引用")]
        public WheelchairController Controller;
        public SafetyGuard Safety = new SafetyGuard();

        [Header("状态")]
        public float BatteryPercent = 78f;
        public float TelemetryInterval = 0.25f;
        public bool  LogCommands = true;

        private readonly List<ICommandSource> _sources = new List<ICommandSource>();
        private readonly Queue<CommandMessage> _inbox = new Queue<CommandMessage>();
        private readonly RaycastHit[] _hitBuffer = new RaycastHit[16];

        private float _telemetryTimer;
        private byte  _telemetrySeq;
        private string _lastCommandText = "-";

        public IReadOnlyList<ICommandSource> Sources { get { return _sources; } }
        public string LastCommandText { get { return _lastCommandText; } }
        public bool AnyRemoteConnected { get; private set; }

        private void Awake()
        {
            Instance = this;
        }

        private void OnDestroy()
        {
            if (Instance == this) Instance = null;
            for (int i = 0; i < _sources.Count; i++)
            {
                try { _sources[i].Disconnect(); } catch (System.Exception) { }
            }
            _sources.Clear();
        }

        // ---------------------------------------------------------- 指令源

        public void AddSource(ICommandSource source)
        {
            if (source == null) return;
            _sources.Add(source);
            try { source.Connect(); } catch (System.Exception e) { Debug.LogWarning("[Hub] 连接失败 " + source.DisplayName + ": " + e.Message); }
            Debug.Log("[Hub] 注册指令源：" + source.DisplayName);
        }

        public void RemoveSource(ICommandSource source)
        {
            if (source == null) return;
            try { source.Disconnect(); } catch (System.Exception) { }
            _sources.Remove(source);
        }

        /// <summary>HUD 按钮 / 键盘走这条入口。</summary>
        public void SubmitLocalCommand(WheelchairCommand command, float arg = 0f)
        {
            _inbox.Enqueue(new CommandMessage(command, arg));
        }

        // ------------------------------------------------------------ 每帧

        private void Update()
        {
            float deltaTime = Time.deltaTime;

            for (int i = 0; i < _sources.Count; i++)
            {
                try { _sources[i].Tick(deltaTime, _inbox); }
                catch (System.Exception e) { Debug.LogWarning("[Hub] 指令源异常 " + _sources[i].DisplayName + ": " + e.Message); }
            }

            while (_inbox.Count > 0) Handle(_inbox.Dequeue());

            // 只有当存在"远程链路且已连接"时才启用看门狗，
            // 否则纯本地键盘调试会被误判成失联。
            AnyRemoteConnected = false;
            for (int i = 0; i < _sources.Count; i++)
            {
                if (_sources[i].RequiresHeartbeat && _sources[i].IsConnected) { AnyRemoteConnected = true; break; }
            }
            Safety.WatchdogEnabled = AnyRemoteConnected;

            UpdateObstacleSensor();

            if (Controller != null)
            {
                Controller.SetSpeedLimit(Safety.SpeedLimit);

                if (Safety.Tick(deltaTime))
                {
                    Controller.EmergencyStop();
                }

                Controller.Tick(deltaTime);
            }

            BroadcastTelemetry(deltaTime, false);
        }

        private void Handle(CommandMessage message)
        {
            if (Controller == null) return;

            _lastCommandText = message.ToString();
            if (LogCommands) Debug.Log("[Hub] <- " + message);

            switch (message.Command)
            {
                case WheelchairCommand.Heartbeat:
                    Safety.NotifyHeartbeat();
                    return;

                case WheelchairCommand.EmergencyStop:
                    Safety.Latch("收到上位机急停指令");
                    Controller.EmergencyStop();
                    return;

                case WheelchairCommand.ReleaseEmergency:
                    Safety.Release();
                    return;

                case WheelchairCommand.SetSpeedLimit:
                    Safety.SpeedLimit = Mathf.Clamp(message.Arg, 0.05f, 3f);
                    return;

                case WheelchairCommand.RequestStatus:
                    BroadcastTelemetry(0f, true);
                    return;

                case WheelchairCommand.Stop:
                    Safety.NotifyHeartbeat();
                    Controller.ApplyCommand(WheelchairCommand.Stop);
                    return;
            }

            // 任何非心跳的远程指令都算喂狗
            Safety.NotifyHeartbeat();

            if (!Safety.AllowsMotion)
            {
                // 急停锁定期间忽略一切运动指令，必须发 ReleaseEmergency
                return;
            }

            float arg = message.Arg;
            if (message.Command == WheelchairCommand.SetSpeed)
            {
                arg = Mathf.Min(arg, Safety.SpeedLimit);
            }
            else if (message.Command == WheelchairCommand.SetTurnRate)
            {
                // 角速度同样要限幅，否则上位机发一个大角度就能把轮椅甩翻。
                arg = Mathf.Clamp(arg, -Safety.AngularLimit, Safety.AngularLimit);
            }

            Controller.ApplyCommand(message.Command, arg);
        }

        private void UpdateObstacleSensor()
        {
            if (Controller == null || !Safety.ObstacleSensorEnabled)
            {
                Safety.DistanceToObstacle = float.PositiveInfinity;
                return;
            }

            Transform body = Controller.transform;
            Vector3 direction = Controller.CurrentLinearSpeed < -0.01f ? -body.forward : body.forward;
            Vector3 origin = body.position + Vector3.up * 0.45f;

            int count = Physics.RaycastNonAlloc(
                origin, direction, _hitBuffer, Safety.SensorMaxDistance, ~0, QueryTriggerInteraction.Ignore);

            float nearest = float.PositiveInfinity;
            for (int i = 0; i < count; i++)
            {
                Collider collider = _hitBuffer[i].collider;
                if (collider == null) continue;
                if (collider.transform.IsChildOf(body)) continue;   // 跳过自己
                // 坡面（含斜坡和地面）不能算障碍物，否则一上坡就会被"前方有障碍"拦停。
                // 只有比 MaxClimbableSlopeAngle 更陡的面（立着的墙）才当障碍物。
                if (Vector3.Angle(_hitBuffer[i].normal, Vector3.up) <= Safety.MaxClimbableSlopeAngle) continue;
                if (_hitBuffer[i].distance < nearest) nearest = _hitBuffer[i].distance;
            }

            Safety.DistanceToObstacle = nearest;
        }

        private void BroadcastTelemetry(float deltaTime, bool force)
        {
            if (Controller == null) return;

            _telemetryTimer += deltaTime;
            if (!force && _telemetryTimer < TelemetryInterval) return;
            _telemetryTimer = 0f;

            WheelchairState state;
            state.LinearSpeed = Controller.CurrentLinearSpeed;
            state.AngularSpeed = Controller.CurrentAngularSpeed;
            state.BatteryPercent = BatteryPercent;
            state.Heading = Controller.Heading;
            state.EmergencyStop = Safety.EmergencyLatched;
            // 目前没有独立的故障模型：急停已经由 EmergencyStop 标志单独上报，
            // 这里恒为 false，避免上位机把"急停锁存"误判成"硬件故障"。
            state.Fault = false;
            state.ActiveCommand = Controller.ActiveCommand;

            byte[] frame = WheelchairProtocol.EncodeStateFrame(state, _telemetrySeq++);

            for (int i = 0; i < _sources.Count; i++)
            {
                if (!_sources[i].CanSendToHost) continue;
                try { _sources[i].SendToHost(frame); }
                catch (System.Exception) { }
            }
        }
    }
}
