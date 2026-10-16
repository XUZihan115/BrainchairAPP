using System;
using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 安全守卫。这是康复设备项目里最容易漏、但验收时最看重的一环。
    ///
    /// 三道防线：
    ///   1) 心跳看门狗：上位机（App/蓝牙）超过 HeartbeatTimeout 没发心跳
    ///      —— 说明链路断了或程序卡死了 —— 立刻锁死停车。
    ///   2) 障碍物急停：前方距离小于 ObstacleStopDistance 时锁死停车。
    ///   3) 急停锁存：一旦锁死，必须显式发 ReleaseEmergency 才能恢复，
    ///      避免指令抖动导致"停了又自己跑"。
    /// </summary>
    [Serializable]
    public sealed class SafetyGuard
    {
        public bool  WatchdogEnabled = true;
        public float HeartbeatTimeout = 2.0f;

        public bool  ObstacleSensorEnabled = true;
        public float ObstacleStopDistance = 0.40f;
        public float SensorMaxDistance = 2.0f;

        [Tooltip("能爬上去的最大坡角（度）。比这更陡的面会被当成障碍物挡停，避免轮椅顺着墙爬上墙顶。")]
        public float MaxClimbableSlopeAngle = 30f;

        public float SpeedLimit = 1.0f;
        public float AngularLimit = 1.5f;

        [NonSerialized] public float DistanceToObstacle = float.PositiveInfinity;
        [NonSerialized] public float TimeSinceHeartbeat;
        [NonSerialized] public string LastReason;
        [NonSerialized] private bool _latched;

        public bool EmergencyLatched { get { return _latched; } }
        public bool AllowsMotion { get { return !_latched; } }

        /// <summary>收到任何一条来自上位机的指令都算喂狗。</summary>
        public void NotifyHeartbeat()
        {
            TimeSinceHeartbeat = 0f;
        }

        /// <summary>每帧调用，返回 true 表示当前处于锁死状态。</summary>
        public bool Tick(float deltaTime)
        {
            TimeSinceHeartbeat += deltaTime;

            if (!_latched && WatchdogEnabled && TimeSinceHeartbeat > HeartbeatTimeout)
            {
                Latch("心跳超时（上位机失联或程序卡死），已自动停车");
            }

            if (!_latched && ObstacleSensorEnabled && DistanceToObstacle < ObstacleStopDistance)
            {
                Latch(string.Format("前方障碍物过近（{0:F2} m），已自动停车", DistanceToObstacle));
            }

            return _latched;
        }

        public void Latch(string reason)
        {
            _latched = true;
            LastReason = reason;
        }

        public void Release()
        {
            _latched = false;
            LastReason = null;
            TimeSinceHeartbeat = 0f;
        }

        public string StateText
        {
            get { return _latched ? ("急停锁定：" + LastReason) : "正常"; }
        }
    }
}
