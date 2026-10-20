using System.Collections.Generic;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 系统中枢：把所有指令来源汇总成一条指令流，经过安全守卫后交给运动控制器，
    /// 并定时把轮椅状态回传给所有可发送的来源。
    ///
    ///   指令源(蓝牙/串口/TCP/WiFi-网络) --> WheelchairHub --> SafetyGuard --> WheelchairController
    ///                                        |               |
    ///                                        |               +--> StepCommander（步进式，脑电链路）
    ///                                        +--> 状态帧回传
    ///
    /// 指令语义按链路区分，两条链路默认都是"步进式"（一条指令只走固定距离/角度，走完自动停）：
    /// 远程链路是给离散的 SSVEP 脑电指令用的；本地键盘也默认步进，
    /// 这样按一下 Q/E/A/D 就是一次 30° 转向、按一下 W/S 就是一次 1 米。
    /// 需要连续控制时有两条路：键盘按住 Shift 再按键（只影响那一下），
    /// 或者由上位机用 MODE:LATCH / MODE:STEP 改自己那条链路的语义。
    /// HUD 上的运动按钮始终是状态保持式（按住意思明确：想连续就点它），
    /// HUD 的"步进"按钮走的是显式一步指令。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WheelchairHub : MonoBehaviour
    {
        public static WheelchairHub Instance { get; private set; }

        [Header("引用")]
        public WheelchairController Controller;
        public SafetyGuard Safety = new SafetyGuard();
        [Tooltip("步进执行器。留空会在运行时自动挂上（脑电链路的指令靠它变成有界动作）")]
        public StepCommander Steps;

        [Header("状态")]
        public float BatteryPercent = 78f;
        public float TelemetryInterval = 0.25f;
        public bool  LogCommands = true;

        [Header("指令语义")]
        [Tooltip("远程链路（蓝牙/串口/TCP）默认的指令语义。脑电指令是离散的，所以默认步进式")]
        public CommandMode RemoteCommandMode = CommandMode.Step;
        [Tooltip("本地键盘默认的指令语义。默认步进式：按一下走一步（前进/后退 1 m、左转/右转 30°）。" +
                 "想连续走就按住 Shift 再按键，按住期间那一下按状态保持式执行；本开关在运行时改也立刻生效")]
        public bool StepModeForLocalInput = true;

        private readonly List<ICommandSource> _sources = new List<ICommandSource>();
        private readonly Queue<CommandMessage> _inbox = new Queue<CommandMessage>();
        private readonly Queue<CommandMessage> _sourceInbox = new Queue<CommandMessage>();
        // 只记录"被 MODE: 指令显式改过"的链路；没记录的链路每次实时回落到默认值，
        // 这样 Inspector 里的 StepModeForLocalInput / RemoteCommandMode 改完立刻生效。
        private readonly Dictionary<ICommandSource, CommandMode> _modeOverrides =
            new Dictionary<ICommandSource, CommandMode>();
        private readonly RaycastHit[] _hitBuffer = new RaycastHit[16];

        private float _telemetryTimer;
        private byte  _telemetrySeq;
        private string _lastCommandText = "-";

        public IReadOnlyList<ICommandSource> Sources { get { return _sources; } }
        public string LastCommandText { get { return _lastCommandText; } }
        public bool AnyRemoteConnected { get; private set; }

        /// <summary>查询某条链路当前的指令语义（HUD 显示用）。</summary>
        public CommandMode GetSourceMode(ICommandSource source)
        {
            return GetSourceMode(source, CommandModeOverride.FollowLink);
        }

        /// <summary>
        /// 解析一条指令该按什么语义执行：
        /// 指令自带的临时覆盖（Shift）&gt; 该链路被 MODE: 显式改过的设置 &gt; 链路默认值。
        /// </summary>
        public CommandMode GetSourceMode(ICommandSource source, CommandModeOverride modeOverride)
        {
            if (modeOverride == CommandModeOverride.Latch) return CommandMode.Latch;
            if (modeOverride == CommandModeOverride.Step) return CommandMode.Step;

            CommandMode mode;
            if (source != null && _modeOverrides.TryGetValue(source, out mode)) return mode;
            return DefaultModeFor(source);
        }

        private CommandMode DefaultModeFor(ICommandSource source)
        {
            if (source == null) return CommandMode.Latch;                 // HUD 按钮：手动点按就该连续

            // 链路自己声明了语义就用它的（WiFi/UDP 是"按住就走"的连续控制，
            // 但任务书里它不需要心跳看门狗，光看 RequiresHeartbeat 会误判成本地键盘的步进式）。
            ICommandModeHint hint = source as ICommandModeHint;
            if (hint != null) return hint.PreferredCommandMode;

            if (source.RequiresHeartbeat) return RemoteCommandMode;       // 远程链路
            return StepModeForLocalInput ? CommandMode.Step : CommandMode.Latch; // 本地键盘
        }

        private void Awake()
        {
            Instance = this;
        }

        private void Start()
        {
            EnsureSteps();
        }

        /// <summary>保证步进执行器存在并接好线（运行期自动挂载，不需要手工拖引用）。</summary>
        private void EnsureSteps()
        {
            if (Steps == null) Steps = GetComponent<StepCommander>();
            if (Steps == null) Steps = gameObject.AddComponent<StepCommander>();
            Steps.Hub = this;
            if (Steps.Controller == null) Steps.Controller = Controller;
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
            Debug.Log(string.Format("[Hub] 注册指令源：{0}（指令语义：{1}）",
                source.DisplayName, GetSourceMode(source) == CommandMode.Step ? "步进式（走完自动停）" : "状态保持式（按一下就一直走）"));
        }

        public void RemoveSource(ICommandSource source)
        {
            if (source == null) return;
            try { source.Disconnect(); } catch (System.Exception) { }
            _sources.Remove(source);
            _modeOverrides.Remove(source);
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
            EnsureSteps();

            // 每条链路单独收指令：这样才知道"这条指令是谁发来的"，
            // 才能按链路决定它该按步进式还是状态保持式执行。
            for (int i = 0; i < _sources.Count; i++)
            {
                ICommandSource source = _sources[i];
                _sourceInbox.Clear();
                try { source.Tick(deltaTime, _sourceInbox); }
                catch (System.Exception e) { Debug.LogWarning("[Hub] 指令源异常 " + source.DisplayName + ": " + e.Message); }

                while (_sourceInbox.Count > 0) Handle(_sourceInbox.Dequeue(), source);
            }

            // HUD 按钮 / 键盘走的本地入口
            while (_inbox.Count > 0) Handle(_inbox.Dequeue(), null);

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
                    if (Steps != null) Steps.AbortStep(true);
                    Controller.EmergencyStop();
                }

                if (Steps != null) Steps.Tick(deltaTime);

                Controller.Tick(deltaTime);
            }

            BroadcastTelemetry(deltaTime, false);
        }

        private void Handle(CommandMessage message, ICommandSource source)
        {
            if (Controller == null) return;

            _lastCommandText = message.ToString();
            if (LogCommands)
            {
                Debug.Log("[Hub] <- " + message + (source != null ? "  [" + source.DisplayName + "]" : "  [本地]"));
            }

            switch (message.Command)
            {
                case WheelchairCommand.Heartbeat:
                    Safety.NotifyHeartbeat();
                    return;

                case WheelchairCommand.EmergencyStop:
                    if (Steps != null) Steps.AbortStep(true);
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
                    if (Steps != null) Steps.AbortStep(true);
                    Controller.ApplyCommand(WheelchairCommand.Stop);
                    return;

                case WheelchairCommand.SetCommandMode:
                    // 只影响发这条指令的那条链路：上位机连上来自报家门，
                    // 脑电上位机发 MODE:STEP，电脑调试工具发 MODE:LATCH。
                    if (source != null)
                    {
                        _modeOverrides[source] = message.Arg >= 0.5f ? CommandMode.Step : CommandMode.Latch;
                        Debug.Log(string.Format("[Hub] {0} 指令语义切换为 {1}",
                            source.DisplayName, GetSourceMode(source) == CommandMode.Step ? "步进式" : "状态保持式"));
                    }
                    else
                    {
                        StepModeForLocalInput = message.Arg >= 0.5f;
                        Debug.Log(string.Format("[Hub] 本地键盘指令语义切换为 {0}",
                            StepModeForLocalInput ? "步进式（按一下走一步）" : "状态保持式（按一下就一直走）"));
                    }
                    return;
            }

            // 任何非心跳的远程指令都算喂狗
            Safety.NotifyHeartbeat();

            if (!Safety.AllowsMotion)
            {
                // 急停锁定期间忽略一切运动指令，必须发 ReleaseEmergency
                if (Steps != null) Steps.AbortStep(true);
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

            // 先给步进执行器：脑电链路的运动指令在这里被翻译成"走固定距离后自动停"
            CommandMode mode = GetSourceMode(source, message.ModeOverride);
            if (Steps != null && Steps.TryHandle(new CommandMessage(message.Command, arg, message.Seq), mode))
            {
                return;
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
            // 只看水平方向：车身贴着坡面上坡时车头是抬头朝天的，
            // 用倾斜后的 forward 去探障碍会一路扫向天空、把矮墙漏掉。
            direction.y = 0f;
            if (direction.sqrMagnitude < 0.0001f) direction = body.forward;
            direction.Normalize();

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

            WheelchairState state;
            state.LinearSpeed = Controller.CurrentLinearSpeed;
            state.AngularSpeed = Controller.CurrentAngularSpeed;
            state.BatteryPercent = BatteryPercent;
            state.Heading = Controller.Heading;
            state.EmergencyStop = Safety.EmergencyLatched;
            // 目前没有独立的故障模型：急停已经由 EmergencyStop 标志单独上报，
            // 这里恒为 false，避免上位机把"急停锁存"误判成"硬件故障"。
            state.Fault = false;
            // 步进忙标志：上位机靠 busy 的 1→0 跳变判断"这一步走完了，可以看下一眼"
            state.Stepping = Steps != null && (Steps.IsExecuting || Steps.IsSettling);
            state.ActiveCommand = Controller.ActiveCommand;

            // 需要"仿真实时状态"的链路（WiFi/UDP 的状态帧带位置，而 WheelchairState 里没有位置）
            // 每帧喂一次最新快照，由它们自己决定什么时候发出去（任务书是 5 Hz）。
            for (int i = 0; i < _sources.Count; i++)
            {
                ISimStateSink sink = _sources[i] as ISimStateSink;
                if (sink == null) continue;
                try { sink.OnSimState(state, Controller.transform.position, Controller.transform.forward); }
                catch (System.Exception) { }
            }

            _telemetryTimer += deltaTime;
            if (!force && _telemetryTimer < TelemetryInterval) return;
            _telemetryTimer = 0f;

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
