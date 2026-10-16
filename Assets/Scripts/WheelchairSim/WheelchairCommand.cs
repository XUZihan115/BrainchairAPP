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
    }

    /// <summary>一条已经解析完成的指令。</summary>
    public struct CommandMessage
    {
        public WheelchairCommand Command;
        public float Arg;
        public byte Seq;

        public CommandMessage(WheelchairCommand command, float arg = 0f, byte seq = 0)
        {
            Command = command;
            Arg = arg;
            Seq = seq;
        }

        public override string ToString()
        {
            return Arg == 0f
                ? Command.ToString()
                : string.Format("{0}({1:F2})", Command, Arg);
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
        public WheelchairCommand ActiveCommand;
    }
}
