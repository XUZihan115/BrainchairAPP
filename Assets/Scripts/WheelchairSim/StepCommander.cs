using System.Collections.Generic;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>一条链路上运动指令的语义。</summary>
    public enum CommandMode
    {
        /// <summary>
        /// 状态保持式：收到"前进"就一路前进，直到 Stop / EmergencyStop。
        /// 适合键盘、摇杆、电脑调试工具——人一直盯着，随时能松手。
        /// </summary>
        Latch,

        /// <summary>
        /// 步进式：一条指令只走固定距离 / 转固定角度，走完自动停，再等下一次指令。
        /// 适合 SSVEP 脑电——指令天生离散（判一次意图要注视 1~4 s），
        /// 而且用户不能一边盯着目标一边观察轮椅，所以必须让每条指令的影响有界、可预期。
        /// </summary>
        Step
    }

    /// <summary>
    /// 步进指令执行器（脑电控制的核心）。
    ///
    /// 为什么需要它：
    ///   WheelchairController 是"状态保持式"的——收到前进就一路前进。
    ///   把离散的脑电指令直接接上去，一次误判就会变成"轮椅自己一直往前开"。
    ///   步进模式把每条指令的影响限制成一段确定的位移 / 转角，走完自动停，
    ///   于是"一次误判"的代价被限定成"多走了 1 m，停下来等下一步"。
    ///
    /// 一条指令的完整生命周期：
    ///   到达 → 不应期检查 → 障碍物检查（可能缩短步长，甚至整步拒绝）
    ///        → 立即执行，或进"预输入"队列（最多 QueueDepth 条，等待超过 CommandTtl 就被丢弃）
    ///        → 走满距离 / 角度 → 平滑停车 → 恢复原来的速度参数
    ///
    /// 本组件只负责"把离散指令变成有界动作"，不碰限速与急停：
    /// 那些仍然由 SafetyGuard + WheelchairHub 负责，这里是串在它们之间的一层。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class StepCommander : MonoBehaviour
    {
        [Header("引用（由 WheelchairHub 在运行时注入）")]
        public WheelchairHub Hub;
        public WheelchairController Controller;

        [Header("总开关")]
        [Tooltip("关掉后所有指令都按状态保持式执行（等于退回旧行为）")]
        public bool Enabled = true;

        [Header("步进量")]
        [Tooltip("前进一步 / 后退一步的距离，单位 m")]
        public float StepDistance = 1.0f;
        [Tooltip("左转一步 / 右转一步的角度，单位 °（正值 = 逆时针 = 左转）")]
        public float StepTurnAngle = 30f;

        [Header("步进运动参数")]
        [Tooltip("步进时的线速度 m/s")]
        public float StepSpeed = 0.30f;
        [Tooltip("步进时的旋转角速度 °/s（30° 一步约 1 s 转完）")]
        public float StepTurnDegreesPerSecond = 34f;
        [Tooltip("步进时的线加速度 m/s^2，越小起步和刹车越柔和")]
        public float StepAcceleration = 0.5f;

        [Header("预输入（指令队列）")]
        [Tooltip("最多缓存几条还没执行的指令，0 = 关闭预输入（来第二条直接丢弃）")]
        public int QueueDepth = 2;
        [Tooltip("排队指令最长等待多少秒，超时丢弃。必须大于一步的实际耗时（1 m / 0.3 m/s ≈ 3.3 s），" +
                 "否则指令还没来得及执行就先过期了，队列等于没用")]
        public float CommandTtl = 5.0f;

        [Header("防误触")]
        [Tooltip("两条步进指令之间的最小间隔 s：间隔内重复来的指令一律丢弃，" +
                 "防止一次注视被判成两条（SSVEP 误报的主要形态）")]
        public float RefractoryPeriod = 1.0f;
        [Tooltip("一步结束、队列也空了之后，再过这么久仍无指令就补发一次停车 s")]
        public float IdleStopTimeout = 1.5f;

        [Header("障碍物联动")]
        [Tooltip("本步要走的方向上，距障碍物不足这个余量时缩短步长 m")]
        public float ObstacleMargin = 0.25f;
        [Tooltip("缩短后小于这个距离就整步拒绝 m（宁可不走，也不要贴上去）")]
        public float MinStepDistance = 0.10f;
        [Tooltip("探测障碍物的最远距离 m")]
        public float ProbeDistance = 6.0f;

        [Header("听觉反馈")]
        [Tooltip("用户要一直盯着闪烁目标，指令有没有被接受只能靠听。开启后发出合成提示音（不需要音频素材）")]
        public bool EnableAudioCues = true;

        // ------------------------------------------------------------ 内部状态

        /// <summary>一条待执行的步进请求。要么是平移（Distance != 0），要么是原地旋转（Angle != 0）。</summary>
        private struct StepRequest
        {
            public float Distance;      // 有符号 m，正 = 前进
            public float Angle;         // 有符号 °，正 = 左转
            public float EnqueuedAt;    // 进入队列的时刻（用于 TTL）
        }

        private readonly List<StepRequest> _queue = new List<StepRequest>(8);
        private readonly RaycastHit[] _probeHits = new RaycastHit[16];

        private float _clock;
        private float _lastAcceptTime = float.NegativeInfinity;   // 不应期计时
        private float _lastStepEndTime = float.NegativeInfinity;  // 空闲补停车计时

        private bool _executing;
        private bool _settling;                 // 已发停车，等真正停稳再交还控制权
        private float _stopIssuedAt;
        private StepRequest _active;
        private bool _activeIsMove;
        private Vector3 _activeStartPosition;
        private Vector3 _activeDirection;       // 起步瞬间的车头方向（平移步不会转向，可以用它做投影）
        private float _activeStartHeading;
        private float _activeTargetAmount;      // 目标量（恒为正）：m 或 °
        private float _activeSpeed;             // 实际生效的线速度（可能被限速压低）
        private float _activeTurnRate;          // 实际生效的角速度 rad/s
        private float _activeStartedAt;
        private float _activeTimeout;           // 单步最长执行时间，防卡死

        private bool _savedParams;
        private float _savedTargetSpeed;
        private float _savedLinearAcceleration;
        private float _savedTurnRate;

        private bool _idleStopArmed;
        private string _statusText = "空闲（等待下一条指令）";
        private string _lastRejectReason = string.Empty;
        private int _completedSteps;
        private int _rejectedSteps;

        private AudioSource _audio;
        private AudioClip _cueAccept;
        private AudioClip _cueDone;
        private AudioClip _cueReject;

        // ------------------------------------------------------------ 只读状态（给 HUD / 遥测）

        public bool IsExecuting { get { return _executing; } }
        public bool IsSettling { get { return _settling; } }
        public int QueuedCount { get { return _queue.Count; } }
        public string StatusText { get { return _statusText; } }
        public string LastRejectReason { get { return _lastRejectReason; } }
        public int CompletedSteps { get { return _completedSteps; } }
        public int RejectedSteps { get { return _rejectedSteps; } }

        public bool InRefractory { get { return _clock - _lastAcceptTime < RefractoryPeriod; } }

        public float RefractoryRemaining
        {
            get { return Mathf.Max(0f, RefractoryPeriod - (_clock - _lastAcceptTime)); }
        }

        // ------------------------------------------------------------ 主循环

        public void Tick(float deltaTime)
        {
            if (Controller == null && Hub != null) Controller = Hub.Controller;
            if (Controller == null) return;

            if (deltaTime > 0f) _clock += deltaTime;
            PruneExpired();

            if (_executing)
            {
                UpdateExecuting();
                return;
            }

            if (_settling)
            {
                // 等它真的停稳再恢复参数，否则刹车会被提前"松开"，落点会偏。
                if (Controller.IsMoving && _clock - _stopIssuedAt < 3f) return;
                _settling = false;
                RestoreParams();
                _statusText = "空闲（等待下一条指令）";
                _idleStopArmed = true;
            }

            // 预输入：上一步刚走完，队列里还有指令就立刻接着走
            if (QueueDepth > 0 && _queue.Count > 0)
            {
                StepRequest next = _queue[0];
                _queue.RemoveAt(0);
                StartStep(next);
                return;
            }

            if (_idleStopArmed && _clock - _lastStepEndTime >= IdleStopTimeout)
            {
                Controller.ApplyCommand(WheelchairCommand.Stop);
                _idleStopArmed = false;
                _statusText = "空闲（已确认停车）";
            }
        }

        // ------------------------------------------------------------ 指令入口

        /// <summary>
        /// 尝试把一条指令按步进语义消费掉。
        /// </summary>
        /// <param name="message">已解析的指令。</param>
        /// <param name="mode">这条指令所属链路的语义。</param>
        /// <returns>
        /// true  = 已被步进逻辑消费，调用方不要再交给运动控制器；
        /// false = 不归我管（心跳、停车、参数设置，或该链路是状态保持式），按原路径继续走。
        /// </returns>
        public bool TryHandle(CommandMessage message, CommandMode mode)
        {
            if (Controller == null && Hub != null) Controller = Hub.Controller;
            if (Controller == null) return false;

            switch (message.Command)
            {
                // ---- 显式步进：不受链路模式限制，任何时候都能用 ----
                case WheelchairCommand.StepMove:
                    SubmitMove(message.Arg);
                    return true;

                case WheelchairCommand.StepTurn:
                    SubmitTurn(message.Arg);
                    return true;

                case WheelchairCommand.StepForward:
                    SubmitMove(StepDistance);
                    return true;

                case WheelchairCommand.StepBackward:
                    SubmitMove(-StepDistance);
                    return true;

                case WheelchairCommand.StepLeft:
                    SubmitTurn(StepTurnAngle);
                    return true;

                case WheelchairCommand.StepRight:
                    SubmitTurn(-StepTurnAngle);
                    return true;

                // ---- 步进参数：步进模式下 SPD:/TURN: 改的就是步进速度 ----
                case WheelchairCommand.SetStepDistance:
                    if (message.Arg <= 0f) { Reject("步长必须为正数"); return true; }
                    StepDistance = Mathf.Clamp(message.Arg, 0.05f, 10f);
                    _statusText = string.Format("步长已设为 {0:F2} m", StepDistance);
                    return true;

                case WheelchairCommand.SetStepAngle:
                    if (message.Arg <= 0f) { Reject("步转角必须为正数"); return true; }
                    StepTurnAngle = Mathf.Clamp(message.Arg, 1f, 180f);
                    _statusText = string.Format("步转角已设为 {0:F0} °", StepTurnAngle);
                    return true;

                case WheelchairCommand.SetSpeed:
                    if (!Enabled || mode != CommandMode.Step) return false;
                    StepSpeed = Mathf.Clamp(message.Arg, 0.05f, 3f);
                    _statusText = string.Format("步进速度已设为 {0:F2} m/s", StepSpeed);
                    return true;

                case WheelchairCommand.SetTurnRate:
                    if (!Enabled || mode != CommandMode.Step) return false;
                    StepTurnDegreesPerSecond = Mathf.Clamp(message.Arg * Mathf.Rad2Deg, 1f, 180f);
                    _statusText = string.Format("步进角速度已设为 {0:F0} °/s", StepTurnDegreesPerSecond);
                    return true;

                // ---- 不归我管：心跳 / 状态请求 / 急停 / 解除 / 限速 ----
                // 停车（Stop）由 Hub 直接处理，但 Hub 会先调用 AbortStep。
                case WheelchairCommand.Heartbeat:
                case WheelchairCommand.RequestStatus:
                case WheelchairCommand.EmergencyStop:
                case WheelchairCommand.ReleaseEmergency:
                case WheelchairCommand.SetSpeedLimit:
                case WheelchairCommand.SetCommandMode:
                case WheelchairCommand.None:
                case WheelchairCommand.Stop:
                    return false;
            }

            // ---- 普通运动指令 ----
            if (!Enabled || mode != CommandMode.Step)
            {
                // 这条链路是状态保持式：先把正在执行的步进收尾，
                // 免得"步进逻辑"和"状态保持逻辑"同时抢方向盘。
                AbortStep(true);
                _idleStopArmed = false;
                return false;
            }

            switch (message.Command)
            {
                case WheelchairCommand.Forward:
                    SubmitMove(StepDistance);
                    return true;
                case WheelchairCommand.Backward:
                    SubmitMove(-StepDistance);
                    return true;
                // 步进模式下 L/R 与 RL/RR 等价：都做成原地转身。
                // 弧线行驶需要连续微调，脑电用户没法做，所以不做弧线。
                case WheelchairCommand.SteerLeft:
                case WheelchairCommand.RotateLeft:
                    SubmitTurn(StepTurnAngle);
                    return true;
                case WheelchairCommand.SteerRight:
                case WheelchairCommand.RotateRight:
                    SubmitTurn(-StepTurnAngle);
                    return true;
            }

            return false;
        }

        /// <summary>中止正在执行 / 排队的步进（急停、停车、切换到状态保持式时调用）。</summary>
        public void AbortStep(bool clearQueue)
        {
            if (clearQueue && _queue.Count > 0) _queue.Clear();
            _idleStopArmed = false;

            if (_executing && Controller != null)
            {
                Controller.ApplyCommand(WheelchairCommand.Stop);
                _executing = false;
                _settling = true;
                _stopIssuedAt = _clock;
                _lastStepEndTime = _clock;
                _statusText = "已中止，停车中…";
            }
        }

        // ------------------------------------------------------------ 提交与排队

        private void SubmitMove(float distance)
        {
            StepRequest request = new StepRequest();
            request.Distance = distance;
            request.Angle = 0f;
            Submit(request);
        }

        private void SubmitTurn(float angle)
        {
            StepRequest request = new StepRequest();
            request.Distance = 0f;
            request.Angle = angle;
            Submit(request);
        }

        private void Submit(StepRequest request)
        {
            if (!Enabled) { Reject("步进功能已关闭"); return; }

            if (Mathf.Abs(request.Distance) < 0.001f && Mathf.Abs(request.Angle) < 0.001f)
            {
                Reject("步进量为 0");
                return;
            }

            // 不应期：一次注视只允许对应一条指令。这里是唯一能挡住
            // "同一次判定被判成两条"的地方——如果只在开始执行时挡，
            // 第二条会被当成预输入排队，最后照样走出来。
            if (_clock - _lastAcceptTime < RefractoryPeriod)
            {
                Reject(string.Format("不应期内（距上一条指令 {0:F1} s < {1:F1} s），重复指令已丢弃",
                    _clock - _lastAcceptTime, RefractoryPeriod));
                return;
            }

            _lastAcceptTime = _clock;
            request.EnqueuedAt = _clock;

            if (_executing || _settling)
            {
                Enqueue(request);
                return;
            }

            StartStep(request);
        }

        private void Enqueue(StepRequest request)
        {
            if (QueueDepth <= 0) { Reject("预输入已关闭（队列上限 0）"); return; }

            if (_queue.Count >= QueueDepth)
            {
                Reject(string.Format("预输入队列已满（{0}/{1}），本条丢弃", _queue.Count, QueueDepth));
                return;
            }

            _queue.Add(request);
            PlayCue(_cueAccept);
            Debug.Log(string.Format("[Step] 预输入排队 {0}（队列 {1}/{2}，{3:F1} s 内不执行就丢弃）",
                Describe(request), _queue.Count, QueueDepth, CommandTtl));
        }

        private void PruneExpired()
        {
            for (int i = _queue.Count - 1; i >= 0; i--)
            {
                if (_clock - _queue[i].EnqueuedAt > CommandTtl)
                {
                    Debug.Log(string.Format("[Step] 排队超时丢弃：{0}（等了 {1:F1} s）",
                        Describe(_queue[i]), _clock - _queue[i].EnqueuedAt));
                    _queue.RemoveAt(i);
                    _rejectedSteps++;
                    _lastRejectReason = "排队超时丢弃（" + CommandTtl.ToString("F1") + " s）";
                }
            }
        }

        // ------------------------------------------------------------ 执行

        private void StartStep(StepRequest request)
        {
            SaveParamsOnce();
            _lastRejectReason = string.Empty;

            _active = request;
            _activeIsMove = Mathf.Abs(request.Distance) >= 0.001f;
            _activeStartedAt = _clock;
            _activeStartPosition = Controller.transform.position;
            _activeDirection = Controller.transform.forward;
            _activeStartHeading = Controller.Heading;

            // 步进用的加速度比常态更柔和，走完再恢复
            Controller.linearAcceleration = Mathf.Max(0.05f, StepAcceleration);

            if (_activeIsMove)
            {
                float limited;
                string note;
                if (!TryLimitDistance(request.Distance, out limited, out note))
                {
                    Reject(note);
                    RestoreParams();
                    _statusText = "空闲（等待下一条指令）";
                    return;
                }

                _active.Distance = limited;
                _activeTargetAmount = Mathf.Abs(limited);

                Controller.ApplyCommand(WheelchairCommand.SetSpeed, StepSpeed);
                _activeSpeed = Mathf.Max(0.05f, Controller.targetSpeed);   // 读回实际值（可能被限速压低）
                Controller.ApplyCommand(limited > 0f ? WheelchairCommand.Forward : WheelchairCommand.Backward);

                _statusText = string.Format("{0} {1:F2} m（剩余 {2:F2} m）",
                    limited > 0f ? "前进" : "后退", _activeTargetAmount, _activeTargetAmount);
                if (!string.IsNullOrEmpty(note)) _lastRejectReason = note;
            }
            else
            {
                _active.Angle = request.Angle;
                _activeTargetAmount = Mathf.Abs(request.Angle);

                Controller.ApplyCommand(WheelchairCommand.SetTurnRate,
                    StepTurnDegreesPerSecond * Mathf.Deg2Rad);
                _activeTurnRate = Mathf.Max(0.05f, Controller.turnRate);   // rad/s
                Controller.ApplyCommand(request.Angle > 0f
                    ? WheelchairCommand.RotateLeft
                    : WheelchairCommand.RotateRight);

                _statusText = string.Format("{0} {1:F0} °（剩余 {2:F0} °）",
                    request.Angle > 0f ? "左转" : "右转", _activeTargetAmount, _activeTargetAmount);
            }

            // 单步最长执行时间：正常耗时的 2 倍再加 1.5 s。
            // 步进是"到量即停"，万一被卡住（贴墙、托空）也要能自己放弃，不能无限走下去。
            float rate = _activeIsMove ? _activeSpeed : _activeTurnRate * Mathf.Rad2Deg;
            _activeTimeout = _activeTargetAmount / Mathf.Max(0.05f, rate) * 2f + 1.5f;

            _executing = true;
            _settling = false;
            _idleStopArmed = false;
            PlayCue(_cueAccept);

            Debug.Log(string.Format("[Step] 开始执行 {0}（速度 {1:F2} {2}，最长 {3:F1} s）",
                Describe(_active), rate, _activeIsMove ? "m/s" : "°/s", _activeTimeout));
        }

        private void UpdateExecuting()
        {
            float elapsed = _clock - _activeStartedAt;
            if (elapsed > _activeTimeout)
            {
                Debug.LogWarning(string.Format("[Step] 单步超时（{0:F1} s > {1:F1} s），判定为被卡住，已中止",
                    elapsed, _activeTimeout));
                Controller.ApplyCommand(WheelchairCommand.Stop);
                _executing = false;
                _settling = true;
                _stopIssuedAt = _clock;
                _lastStepEndTime = _clock;
                _rejectedSteps++;
                _lastRejectReason = "单步超时中止";
                _statusText = "超时中止，停车中…";
                PlayCue(_cueReject);
                return;
            }

            float done;
            float stopThreshold;

            if (_activeIsMove)
            {
                Vector3 delta = Controller.transform.position - _activeStartPosition;
                done = Mathf.Abs(Vector3.Dot(delta, _activeDirection));

                // 刹车还要滑行 v^2 / (2a)，提前这么多就下令停车，落点才准
                stopThreshold = Mathf.Max(0.005f,
                    _activeSpeed * _activeSpeed / (2f * Mathf.Max(0.05f, StepAcceleration)));

                _statusText = string.Format("{0} {1:F2} m（剩余 {2:F2} m）",
                    _active.Distance > 0f ? "前进" : "后退",
                    _activeTargetAmount, Mathf.Max(0f, _activeTargetAmount - done));
            }
            else
            {
                done = Mathf.Abs(Mathf.DeltaAngle(_activeStartHeading, Controller.Heading));

                float w = Mathf.Max(0.05f, _activeTurnRate);
                float a = Mathf.Max(0.1f, Controller.angularAcceleration);
                stopThreshold = Mathf.Max(0.2f, w * w / (2f * a) * Mathf.Rad2Deg);

                _statusText = string.Format("{0} {1:F0} °（剩余 {2:F0} °）",
                    _active.Angle > 0f ? "左转" : "右转",
                    _activeTargetAmount, Mathf.Max(0f, _activeTargetAmount - done));
            }

            if (done + stopThreshold >= _activeTargetAmount)
            {
                Controller.ApplyCommand(WheelchairCommand.Stop);
                _executing = false;
                _settling = true;
                _stopIssuedAt = _clock;
                _lastStepEndTime = _clock;
                _completedSteps++;
                _statusText = string.Format("到位（{0}），停车中…", Describe(_active));
                PlayCue(_cueDone);
                Debug.Log(string.Format("[Step] 到位：{0}，实际完成 {1:F2}{2}",
                    Describe(_active), done, _activeIsMove ? " m" : " °"));
            }
        }

        // ------------------------------------------------------------ 障碍物联动

        private bool TryLimitDistance(float requested, out float limited, out string note)
        {
            limited = requested;
            note = string.Empty;

            SafetyGuard safety = Hub != null ? Hub.Safety : null;
            if (safety == null || !safety.ObstacleSensorEnabled) return true;

            Vector3 direction = requested > 0f ? Controller.transform.forward : -Controller.transform.forward;
            float gap = ProbeObstacle(direction);
            if (float.IsPositiveInfinity(gap)) return true;

            float allowed = gap - ObstacleMargin;

            if (allowed < MinStepDistance)
            {
                note = string.Format("{0}方 {1:F2} m 处有障碍，本步整步拒绝（可用 {2:F2} m < {3:F2} m）",
                    requested > 0f ? "前" : "后", gap, allowed, MinStepDistance);
                return false;
            }

            if (allowed < Mathf.Abs(requested))
            {
                limited = Mathf.Sign(requested) * allowed;
                note = string.Format("{0}方 {1:F2} m 处有障碍，本步由 {2:F2} m 缩短为 {3:F2} m",
                    requested > 0f ? "前" : "后", gap, Mathf.Abs(requested), allowed);
                Debug.Log("[Step] " + note);
            }

            return true;
        }

        /// <summary>
        /// 朝指定方向探一次障碍物。规则和 WheelchairHub.UpdateObstacleSensor 保持一致：
        /// 跳过自己身上的碰撞体，坡面（法线不够陡）不算障碍。
        /// 前后都要能探——驾驶舱前方传感器看不到车尾，后退这一步得自己看。
        /// </summary>
        private float ProbeObstacle(Vector3 direction)
        {
            Vector3 origin = Controller.transform.position + Vector3.up * 0.45f;

            int count = Physics.RaycastNonAlloc(
                origin, direction, _probeHits, ProbeDistance, ~0, QueryTriggerInteraction.Ignore);

            float nearest = float.PositiveInfinity;
            for (int i = 0; i < count; i++)
            {
                Collider collider = _probeHits[i].collider;
                if (collider == null) continue;
                if (collider.transform.IsChildOf(Controller.transform)) continue;
                if (Vector3.Angle(_probeHits[i].normal, Vector3.up) <= MaxClimbableSlopeAngle()) continue;
                if (_probeHits[i].distance < nearest) nearest = _probeHits[i].distance;
            }

            return nearest;
        }

        private float MaxClimbableSlopeAngle()
        {
            SafetyGuard safety = Hub != null ? Hub.Safety : null;
            return safety != null ? safety.MaxClimbableSlopeAngle : 30f;
        }

        // ------------------------------------------------------------ 参数保存 / 恢复 / 工具

        private void SaveParamsOnce()
        {
            if (_savedParams) return;
            _savedTargetSpeed = Controller.targetSpeed;
            _savedLinearAcceleration = Controller.linearAcceleration;
            _savedTurnRate = Controller.turnRate;
            _savedParams = true;
        }

        private void RestoreParams()
        {
            if (!_savedParams || Controller == null) return;
            Controller.targetSpeed = Mathf.Clamp(_savedTargetSpeed, 0.05f, Mathf.Max(0.05f, Controller.maxSpeed));
            Controller.linearAcceleration = _savedLinearAcceleration;
            Controller.turnRate = _savedTurnRate;
            _savedParams = false;
        }

        private void Reject(string reason)
        {
            _rejectedSteps++;
            _lastRejectReason = reason;
            Debug.LogWarning("[Step] 指令被丢弃：" + reason);
            PlayCue(_cueReject);
        }

        private static string Describe(StepRequest request)
        {
            if (Mathf.Abs(request.Distance) >= 0.001f)
                return string.Format("{0} {1:F2} m", request.Distance > 0f ? "前进" : "后退", Mathf.Abs(request.Distance));
            return string.Format("{0} {1:F0} °", request.Angle > 0f ? "左转" : "右转", Mathf.Abs(request.Angle));
        }

        // ------------------------------------------------------------ 听觉反馈

        private void EnsureAudio()
        {
            if (_audio != null) return;

            _audio = GetComponent<AudioSource>();
            if (_audio == null) _audio = gameObject.AddComponent<AudioSource>();
            _audio.playOnAwake = false;
            _audio.spatialBlend = 0f;
            _audio.volume = 1f;

            // 场景里没有 AudioListener 的话，Unity 会刷一条警告，这里顺手补一个
            if (UnityEngine.Object.FindAnyObjectByType<AudioListener>() == null)
                gameObject.AddComponent<AudioListener>();

            _cueAccept = CreateTone("step_accept", new[] { 880f }, 0.06f);          // 单声高音：指令已接受
            _cueDone   = CreateTone("step_done",   new[] { 660f, 440f }, 0.07f);     // 两声下行：这一步走完了
            _cueReject = CreateTone("step_reject", new[] { 220f }, 0.14f);           // 低声长音：指令被丢弃
        }

        private void PlayCue(AudioClip clip)
        {
            if (!EnableAudioCues) return;
            try
            {
                EnsureAudio();
                if (_audio != null && clip != null) _audio.PlayOneShot(clip);
            }
            catch (System.Exception) { }
        }

        /// <summary>合成一段提示音，不需要任何音频素材文件。</summary>
        private static AudioClip CreateTone(string name, float[] frequencies, float secondsPerTone)
        {
            const int sampleRate = 44100;
            int perTone = Mathf.Max(1, Mathf.RoundToInt(sampleRate * secondsPerTone));
            float[] data = new float[perTone * frequencies.Length];

            float attack = sampleRate * 0.005f;
            float release = sampleRate * 0.02f;

            for (int tone = 0; tone < frequencies.Length; tone++)
            {
                for (int i = 0; i < perTone; i++)
                {
                    float envelope = Mathf.Min(1f, i / attack) * Mathf.Min(1f, (perTone - i) / release);
                    float wave = Mathf.Sin(2f * Mathf.PI * frequencies[tone] * i / sampleRate);
                    data[tone * perTone + i] = wave * 0.25f * envelope;
                }
            }

            AudioClip clip = AudioClip.Create(name, data.Length, 1, sampleRate, false);
            clip.SetData(data, 0);
            return clip;
        }
    }
}
