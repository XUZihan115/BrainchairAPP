using System.Collections.Generic;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 指令来源的统一抽象：蓝牙、串口、TCP、本地键盘都实现它。
    /// WheelchairHub 只跟这个接口打交道，换通信方式不影响运动控制与界面。
    /// </summary>
    public interface ICommandSource
    {
        /// <summary>显示名，出现在 HUD 上。</summary>
        string DisplayName { get; }

        /// <summary>当前是否已连接上对方。</summary>
        bool IsConnected { get; }

        /// <summary>能否向对方回传数据（状态帧）。</summary>
        bool CanSendToHost { get; }

        /// <summary>是否需要心跳看门狗。远程链路为 true，本地键盘为 false。</summary>
        bool RequiresHeartbeat { get; }

        /// <summary>一行状态描述，显示在 HUD 上。</summary>
        string StatusDetail { get; }

        void Connect();
        void Disconnect();

        /// <summary>每帧调用。解析出的指令请塞进 output 队列。</summary>
        void Tick(float deltaTime, Queue<CommandMessage> output);

        /// <summary>向对方发送原始字节（状态回传用）。</summary>
        void SendToHost(byte[] data);
    }

    /// <summary>
    /// 可选扩展：链路自己对"指令语义"的声明。
    /// 不实现它的链路（蓝牙/串口/TCP）走 WheelchairHub 的老规则（远程链路看 RemoteCommandMode、
    /// 本地键盘看 StepModeForLocalInput）；WiFi/UDP 链路实现它，因为任务书里的
    /// "前进 + 速度 0~100"天生是连续控制（收到前进就一直走，直到收到停止）。
    /// </summary>
    public interface ICommandModeHint
    {
        CommandMode PreferredCommandMode { get; }
    }

    /// <summary>
    /// 可选扩展：需要"仿真实时状态"才能回状态帧的链路。
    /// WheelchairState 里没有位置，而任务书要求状态帧带位置 (x,z)，
    /// 所以 WheelchairHub 定时把状态连同位置喂给实现了这个接口的链路。
    /// </summary>
    public interface ISimStateSink
    {
        /// <summary>由 WheelchairHub 每帧调用，参数是当前状态快照（不要在这里做耗时操作）。</summary>
        void OnSimState(WheelchairState state, Vector3 position, Vector3 forward);
    }

    public abstract class CommandSourceBase : ICommandSource
    {
        protected readonly CommandStreamDecoder Decoder;

        protected CommandSourceBase(string displayName, ProtocolMode mode = ProtocolMode.Auto)
        {
            DisplayName = displayName;
            Decoder = new CommandStreamDecoder(mode);
        }

        public string DisplayName { get; private set; }
        public bool IsConnected { get; protected set; }
        public string StatusDetail { get; protected set; }

        public virtual bool CanSendToHost { get { return IsConnected; } }
        public virtual bool RequiresHeartbeat { get { return true; } }

        public abstract void Connect();
        public abstract void Disconnect();
        public abstract void Tick(float deltaTime, Queue<CommandMessage> output);
        public virtual void SendToHost(byte[] data) { }

        protected void DrainDecoder(Queue<CommandMessage> output)
        {
            CommandMessage message;
            while (Decoder.TryDequeue(out message)) output.Enqueue(message);
        }
    }

    /// <summary>
    /// 本地输入源：键盘 + HUD 按钮。不需要任何外部设备，用来随时验证运动逻辑。
    /// 键盘的语义由 WheelchairHub.StepModeForLocalInput 决定，默认是"步进式"：
    /// 按一下 W/S 走 1 m、按一下 Q/E/A/D 转 30° 后自动停。
    /// 想连续走就按住 Shift 再按键——那一下会按"状态保持式"发出去。
    /// </summary>
    public sealed class LocalCommandSource : CommandSourceBase
    {
        private readonly Queue<CommandMessage> _pending = new Queue<CommandMessage>();

        private const string KeyHelp =
            "键盘 W/S 一步 1 m、Q/E/A/D 一步 30°（按住 Shift = 连续）\n空格=停止、X=急停、Z=解除";

        public LocalCommandSource() : base("本地输入")
        {
            IsConnected = true;
            StatusDetail = KeyHelp;
        }

        public override bool RequiresHeartbeat { get { return false; } }

        public override void Connect()
        {
            IsConnected = true;
            StatusDetail = KeyHelp;
        }

        public override void Disconnect() { IsConnected = false; }

        /// <summary>HUD 按钮用这个入口灌入指令。</summary>
        public void Push(WheelchairCommand command, float arg = 0f)
        {
            _pending.Enqueue(new CommandMessage(command, arg));
        }

        public override void Tick(float deltaTime, Queue<CommandMessage> output)
        {
            // HUD 按钮是在 OnGUI 里 Push 进来的，只能延迟到这一帧统一派发。
            while (_pending.Count > 0) output.Enqueue(_pending.Dequeue());

            // 键盘是轮询式的，直接写进 output，避免走 _pending 白白延迟一帧。
#if ENABLE_LEGACY_INPUT_MANAGER
            // 按住 Shift = 这一下要连续走：给运动指令打上"强制状态保持式"的标记。
            // 只在按下 Shift 的那一瞬间生效，松开就回到链路的默认语义（一步）。
            CommandModeOverride hold = (Input.GetKey(KeyCode.LeftShift) || Input.GetKey(KeyCode.RightShift))
                ? CommandModeOverride.Latch
                : CommandModeOverride.FollowLink;

            if (Input.GetKeyDown(KeyCode.W) || Input.GetKeyDown(KeyCode.UpArrow))
                output.Enqueue(new CommandMessage(WheelchairCommand.Forward, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.S) || Input.GetKeyDown(KeyCode.DownArrow))
                output.Enqueue(new CommandMessage(WheelchairCommand.Backward, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.A) || Input.GetKeyDown(KeyCode.LeftArrow))
                output.Enqueue(new CommandMessage(WheelchairCommand.SteerLeft, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.D) || Input.GetKeyDown(KeyCode.RightArrow))
                output.Enqueue(new CommandMessage(WheelchairCommand.SteerRight, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.Q))
                output.Enqueue(new CommandMessage(WheelchairCommand.RotateLeft, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.E))
                output.Enqueue(new CommandMessage(WheelchairCommand.RotateRight, 0f, 0, hold));
            if (Input.GetKeyDown(KeyCode.Space))
                output.Enqueue(new CommandMessage(WheelchairCommand.Stop));
            if (Input.GetKeyDown(KeyCode.X))
                output.Enqueue(new CommandMessage(WheelchairCommand.EmergencyStop));
            if (Input.GetKeyDown(KeyCode.Z))
                output.Enqueue(new CommandMessage(WheelchairCommand.ReleaseEmergency));
#endif
        }
    }
}
