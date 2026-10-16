using System;

namespace WheelchairSim
{
    /// <summary>
    /// 上位机（App / 蓝牙 / 串口 / 网络）可下发的指令编码。
    /// 枚举数值同时就是通信协议里的 CMD 字段，改协议时必须同步修改。
    /// </summary>
    public enum WheelchairCommand : byte
    {
        None             = 0x00,

        Forward          = 0x01, // 前进
        Backward         = 0x02, // 后退
        SteerLeft        = 0x03, // 行进中向左转向（边走边转，弧线）
        SteerRight       = 0x04, // 行进中向右转向
        RotateLeft       = 0x05, // 原地左旋转
        RotateRight      = 0x06, // 原地右旋转
        Stop             = 0x07, // 平滑停车

        EmergencyStop    = 0x08, // 急停：立即刹停并锁定（需要解除才能再动）
        ReleaseEmergency = 0x09, // 解除急停锁

        SetSpeed         = 0x0A, // 参数：目标线速度 m/s
        SetSpeedLimit    = 0x0B, // 参数：线速度上限 m/s
        SetTurnRate      = 0x0C, // 参数：目标角速度 rad/s

        Heartbeat        = 0x0D, // 心跳（喂看门狗）
        RequestStatus    = 0x0E, // 请求立刻回传一次状态

        // ---- 以下为步进模式（SSVEP 脑电）专用指令 ----
        // 步进量指令（StepForward .. StepRight）用当前配置的默认步长/步转角；
        // StepMove / StepTurn 则用报文里的 ARG 指定这一步走多远 / 转多少。

        SetCommandMode   = 0x0F, // 参数：0 = 状态保持式，1 = 步进式（只影响发这条指令的那条链路）
        StepMove         = 0x10, // 参数：本步前进距离 m，负值 = 后退
        StepTurn         = 0x11, // 参数：本步旋转角度 °，正值 = 左转（逆时针）
        SetStepDistance  = 0x12, // 参数：默认步长 m
        SetStepAngle     = 0x13, // 参数：默认步转角 °
        StepForward      = 0x14, // 前进一步（默认步长）
        StepBackward     = 0x15, // 后退一步（默认步长）
        StepLeft         = 0x16, // 原地左转一步（默认步转角）
        StepRight        = 0x17, // 原地右转一步（默认步转角）
    }

    /// <summary>
    /// 单条指令的语义临时覆盖。
    /// 链路本身有默认语义（远程链路默认步进式、本地键盘默认步进式），
    /// 但本地调试时经常需要"这一下我想连续走"——按住 Shift 就是走这个通道，
    /// 只影响当前这条指令，不改链路设置。
    /// </summary>
    public enum CommandModeOverride : byte
    {
        FollowLink = 0, // 不覆盖，按链路自己的语义执行
        Latch      = 1, // 这条指令强制按"状态保持式"执行（连续）
        Step       = 2, // 这条指令强制按"步进式"执行（走完自动停）
    }

    /// <summary>一条已经解析完成的指令。</summary>
    public struct CommandMessage
    {
        public WheelchairCommand Command;
        public float Arg;
        public byte Seq;
        public CommandModeOverride ModeOverride;

        public CommandMessage(WheelchairCommand command, float arg = 0f, byte seq = 0,
                              CommandModeOverride modeOverride = CommandModeOverride.FollowLink)
        {
            Command = command;
            Arg = arg;
            Seq = seq;
            ModeOverride = modeOverride;
        }

        public override string ToString()
        {
            string text = Arg == 0f
                ? Command.ToString()
                : string.Format("{0}({1:F2})", Command, Arg);
            if (ModeOverride == CommandModeOverride.Latch) text += " [强制连续]";
            else if (ModeOverride == CommandModeOverride.Step) text += " [强制一步]";
            return text;
        }
    }

    /// <summary>轮椅回传给上位机的状态快照（仿真里由 WheelchairHub 定时广播）。</summary>
    [Serializable]
    public struct WheelchairState
    {
        public float LinearSpeed;      // m/s，正 = 前进
        public float AngularSpeed;     // rad/s，正 = 左转
        public float BatteryPercent;   // 0 - 100
        public float Heading;          // 度，0 - 360
        public bool  EmergencyStop;
        public bool  Fault;
        /// <summary>正在执行一步步进（走完自动清零）。上位机靠它的 1→0 跳变判断"这一步走完了，可以看下一眼"。</summary>
        public bool  Stepping;
        public WheelchairCommand ActiveCommand;
    }
}
