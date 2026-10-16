using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 屏幕上的一块即时状态面板 + 调试按钮（用 OnGUI 实现，零依赖、不需要拖任何 UI 预制体）。
    /// 真实产品要换成 Unity UI / UIToolkit 的正式界面，这里只服务于仿真调试。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class HudOverlay : MonoBehaviour
    {
        public WheelchairHub Hub;

        /// <summary>
        /// 可选：由 WheelchairBootstrap 在运行时注入。挂上模拟蓝牙链路后，
        /// 可以直接在面板里手工输入指令来验证整条链路。
        /// 这是运行期引用，不参与 Unity 序列化（MockBluetoothLink 内部含队列，本身不可序列化）。
        /// </summary>
        [System.NonSerialized]
        public MockBluetoothLink MockLink;

        private GUIStyle _titleStyle;
        private GUIStyle _labelStyle;
        private GUIStyle _warnStyle;
        private bool _stylesReady;

        private float _speedSetting = 0.6f;
        private float _turnSetting = 0.9f;
        private float _limitSetting = 1.0f;
        private string _mockInput = "SPD:0.6";

        private void EnsureStyles()
        {
            if (_stylesReady) return;
            _titleStyle = new GUIStyle(GUI.skin.label) { fontSize = 17, fontStyle = FontStyle.Bold };
            _labelStyle = new GUIStyle(GUI.skin.label) { fontSize = 13 };
            _warnStyle = new GUIStyle(GUI.skin.label) { fontSize = 13, fontStyle = FontStyle.Bold };
            _warnStyle.normal.textColor = new Color(1f, 0.45f, 0.30f);
            _stylesReady = true;
        }

        private void OnGUI()
        {
            if (Hub == null || Hub.Controller == null) return;
            EnsureStyles();

            float scale = Mathf.Max(1f, Screen.height / 900f);
            Matrix4x4 previous = GUI.matrix;
            GUI.matrix = Matrix4x4.TRS(Vector3.zero, Quaternion.identity, new Vector3(scale, scale, 1f));

            float width = Screen.width / scale;
            float height = Screen.height / scale;

            DrawStatusPanel(width);
            DrawControlPanel(width, height);

            GUI.matrix = previous;
        }

        private void DrawStatusPanel(float screenWidth)
        {
            WheelchairController controller = Hub.Controller;
            SafetyGuard safety = Hub.Safety;

            // 放在右上角，和左下角的控制面板互不遮挡
            GUILayout.BeginArea(new Rect(screenWidth - 384f, 12f, 372f, 660f), GUI.skin.box);

            GUILayout.Label("脑控轮椅 · Unity 仿真", _titleStyle);
            GUILayout.Space(4f);

            GUILayout.Label(string.Format("最近指令：{0}", Hub.LastCommandText), _labelStyle);
            GUILayout.Label(string.Format("运行状态：{0}", controller.ActiveCommand), _labelStyle);
            GUILayout.Label(string.Format("线速度 v = {0,6:F2} m/s", controller.CurrentLinearSpeed), _labelStyle);
            GUILayout.Label(string.Format("角速度 w = {0,6:F2} rad/s", controller.CurrentAngularSpeed), _labelStyle);
            GUILayout.Label(string.Format("航向角度 = {0,5:F0} °", controller.Heading), _labelStyle);
            GUILayout.Label(string.Format("电量     = {0,5:F0} %", Hub.BatteryPercent), _labelStyle);

            GUILayout.Space(6f);
            GUILayout.Label("—— 安全 ——", _labelStyle);

            if (safety.EmergencyLatched)
                GUILayout.Label("● " + safety.StateText, _warnStyle);
            else
                GUILayout.Label("● 安全状态：正常", _labelStyle);

            if (safety.WatchdogEnabled)
                GUILayout.Label(string.Format("心跳看门狗：剩余 {0:F1} s / 超时 {1:F1} s",
                    Mathf.Max(0f, safety.HeartbeatTimeout - safety.TimeSinceHeartbeat), safety.HeartbeatTimeout), _labelStyle);
            else
                GUILayout.Label("心跳看门狗：未启用（当前无远程链路）", _labelStyle);

            if (safety.ObstacleSensorEnabled)
            {
                string distance = float.IsInfinity(safety.DistanceToObstacle)
                    ? "--"
                    : safety.DistanceToObstacle.ToString("F2");
                GUILayout.Label(string.Format("前方障碍：{0} m（{1:F2} m 内停车）", distance, safety.ObstacleStopDistance), _labelStyle);
            }

            GUILayout.Space(6f);
            GUILayout.Label("—— 指令来源 ——", _labelStyle);

            for (int i = 0; i < Hub.Sources.Count; i++)
            {
                ICommandSource source = Hub.Sources[i];
                string state = source.IsConnected ? "已连接" : "未连接";
                string mode = Hub.GetSourceMode(source) == CommandMode.Step ? "步进式" : "状态保持式";
                GUILayout.Label(string.Format("{0}  [{1}]  {2}", source.DisplayName, state, mode), _labelStyle);
                GUILayout.Label("    " + source.StatusDetail, _labelStyle);
            }

            GUILayout.Space(6f);
            DrawStepSection();

            GUILayout.Space(6f);
            GUILayout.Label("键盘：W/S 走一步 1 m · Q/E/A/D 转一步 30° · 按住 Shift 再按 = 连续 · 空格 停止 · X 急停 · Z 解除", _labelStyle);

            GUILayout.EndArea();
        }

        /// <summary>脑电步进模式的状态面板：模式、当前步、预输入队列、不应期。</summary>
        private void DrawStepSection()
        {
            GUILayout.Label("—— 脑电步进 ——", _labelStyle);

            StepCommander steps = Hub.Steps;
            if (steps == null)
            {
                GUILayout.Label("未挂载 StepCommander（步进功能不可用）", _warnStyle);
                return;
            }

            GUILayout.Label(steps.Enabled ? "步进功能：已开启" : "步进功能：已关闭（全部按状态保持式执行）", _labelStyle);
            GUILayout.Label(string.Format("键盘语义：{0}（按住 Shift 的那一下 = 连续）",
                Hub.StepModeForLocalInput ? "一步一停" : "按下即连续"), _labelStyle);
            GUILayout.Label(string.Format("步进量：{0:F1} m / {1:F0}°    速度 {2:F2} m/s    转速 {3:F0} °/s",
                steps.StepDistance, steps.StepTurnAngle, steps.StepSpeed, steps.StepTurnDegreesPerSecond), _labelStyle);
            GUILayout.Label(string.Format("执行中：{0}", steps.StatusText),
                steps.IsExecuting ? _warnStyle : _labelStyle);
            GUILayout.Label(string.Format("预输入队列：{0} / {1} 条    排队超时 {2:F1} s",
                steps.QueuedCount, steps.QueueDepth, steps.CommandTtl), _labelStyle);
            GUILayout.Label(steps.InRefractory
                ? string.Format("不应期：剩余 {0:F1} s（这期间的重复指令一律丢弃）", steps.RefractoryRemaining)
                : string.Format("不应期：空闲（两条指令最小间隔 {0:F1} s）", steps.RefractoryPeriod), _labelStyle);
            GUILayout.Label(string.Format("累计：完成 {0} 步 / 丢弃 {1} 条", steps.CompletedSteps, steps.RejectedSteps), _labelStyle);

            if (!string.IsNullOrEmpty(steps.LastRejectReason))
                GUILayout.Label("最近丢弃：" + steps.LastRejectReason, _warnStyle);
        }

        private void DrawControlPanel(float screenWidth, float screenHeight)
        {
            const float panelWidth = 372f;
            float panelHeight = (MockLink != null ? 330f : 268f) + 96f;
            float top = screenHeight - panelHeight - 12f;

            GUILayout.BeginArea(new Rect(12f, top, panelWidth, panelHeight), GUI.skin.box);

            GUILayout.Label("手动控制（等同于下位机收到指令）", _titleStyle);
            GUILayout.Space(4f);

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("↑ 前进", GUILayout.Height(30f))) Submit(WheelchairCommand.Forward);
            if (GUILayout.Button("↓ 后退", GUILayout.Height(30f))) Submit(WheelchairCommand.Backward);
            GUILayout.EndHorizontal();

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("↰ 左转", GUILayout.Height(30f))) Submit(WheelchairCommand.SteerLeft);
            if (GUILayout.Button("↱ 右转", GUILayout.Height(30f))) Submit(WheelchairCommand.SteerRight);
            GUILayout.EndHorizontal();

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("⟲ 原地左旋", GUILayout.Height(30f))) Submit(WheelchairCommand.RotateLeft);
            if (GUILayout.Button("⟳ 原地右旋", GUILayout.Height(30f))) Submit(WheelchairCommand.RotateRight);
            GUILayout.EndHorizontal();

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("■ 停止", GUILayout.Height(30f))) Submit(WheelchairCommand.Stop);
            Color previous = GUI.backgroundColor;
            GUI.backgroundColor = new Color(1f, 0.35f, 0.30f);
            if (GUILayout.Button("✕ 急停", GUILayout.Height(30f))) Submit(WheelchairCommand.EmergencyStop);
            GUI.backgroundColor = previous;
            GUILayout.EndHorizontal();

            if (GUILayout.Button("解除急停锁", GUILayout.Height(24f))) Submit(WheelchairCommand.ReleaseEmergency);

            GUILayout.Space(6f);
            GUILayout.Label("步进（脑电语义：走完自动停）", _labelStyle);

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("⇧ 步进前进", GUILayout.Height(26f))) Submit(WheelchairCommand.StepForward);
            if (GUILayout.Button("⇩ 步进后退", GUILayout.Height(26f))) Submit(WheelchairCommand.StepBackward);
            GUILayout.EndHorizontal();

            GUILayout.BeginHorizontal();
            if (GUILayout.Button("⟲ 步转左", GUILayout.Height(26f))) Submit(WheelchairCommand.StepLeft);
            if (GUILayout.Button("⟳ 步转右", GUILayout.Height(26f))) Submit(WheelchairCommand.StepRight);
            GUILayout.EndHorizontal();

            GUILayout.Label(Hub != null && Hub.Steps != null
                ? string.Format("当前步进量 {0:F1} m / {1:F0}°（可用 STEP: / SETSTEPANGLE: 修改）",
                    Hub.Steps.StepDistance, Hub.Steps.StepTurnAngle)
                : "未挂载 StepCommander", _labelStyle);

            GUILayout.Space(6f);

            GUILayout.Label(string.Format("速度 {0:F2} m/s", _speedSetting), _labelStyle);
            _speedSetting = GUILayout.HorizontalSlider(_speedSetting, 0.05f, 1.5f);
            if (GUILayout.Button("应用速度", GUILayout.Height(22f))) Submit(WheelchairCommand.SetSpeed, _speedSetting);

            GUILayout.Label(string.Format("角速度 {0:F2} rad/s", _turnSetting), _labelStyle);
            _turnSetting = GUILayout.HorizontalSlider(_turnSetting, 0.1f, 2.5f);
            if (GUILayout.Button("应用角速度", GUILayout.Height(22f))) Submit(WheelchairCommand.SetTurnRate, _turnSetting);

            GUILayout.Label(string.Format("安全限速 {0:F2} m/s", _limitSetting), _labelStyle);
            _limitSetting = GUILayout.HorizontalSlider(_limitSetting, 0.05f, 2.0f);
            if (GUILayout.Button("应用限速", GUILayout.Height(22f))) Submit(WheelchairCommand.SetSpeedLimit, _limitSetting);

            if (MockLink != null)
            {
                GUILayout.Space(6f);
                GUILayout.Label("模拟设备注入（走完整协议解析路径）", _labelStyle);
                GUILayout.BeginHorizontal();
                _mockInput = GUILayout.TextField(_mockInput, GUILayout.Height(22f));
                if (GUILayout.Button("发送", GUILayout.Width(56f), GUILayout.Height(22f)))
                {
                    MockLink.InjectText(_mockInput + "\n");
                }
                GUILayout.EndHorizontal();
            }

            GUILayout.EndArea();
        }

        private void Submit(WheelchairCommand command, float arg = 0f)
        {
            if (Hub != null) Hub.SubmitLocalCommand(command, arg);
        }
    }
}
