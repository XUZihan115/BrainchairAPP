using UnityEngine;

namespace WheelchairSim
{
    public enum MotionMode
    {
        /// <summary>运动学驱动：位置直接由脚本决定。简单、可控、可复现，仿真首选。</summary>
        Kinematic,
        /// <summary>物理驱动：交给 Rigidbody 的速度积分，可以撞到障碍物被挡住。</summary>
        Physics
    }

    /// <summary>
    /// 轮椅的运动模型（差速驱动）。
    ///
    /// 指令语义（状态保持式，收到 Stop / EmergencyStop 才停）：
    ///   Forward      -> 线速度 +v，角速度 0
    ///   Backward     -> 线速度 -v，角速度 0
    ///   SteerLeft    -> 线速度 +v，角速度 +w   （边走边左转）
    ///   SteerRight   -> 线速度 +v，角速度 -w
    ///   RotateLeft   -> 线速度 0，角速度 +w    （原地左旋）
    ///   RotateRight  -> 线速度 0，角速度 -w
    ///   Stop         -> 线速度 0，角速度 0     （按加速度平滑减速）
    ///   SetSpeed     -> 修改 v（arg = m/s）
    ///   SetTurnRate  -> 修改 w（arg = rad/s）
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WheelchairController : MonoBehaviour
    {
        [Header("速度参数")]
        [Tooltip("默认线速度 m/s")]
        public float targetSpeed = 0.6f;
        [Tooltip("默认角速度 rad/s")]
        public float turnRate = 0.9f;
        [Tooltip("硬上限，安全模块会把限速下调到这里")]
        public float maxSpeed = 1.5f;
        [Tooltip("线加速度 m/s^2，越小启动/刹车越柔和")]
        public float linearAcceleration = 1.2f;
        [Tooltip("角加速度 rad/s^2")]
        public float angularAcceleration = 3.0f;

        [Header("驱动方式")]
        public MotionMode motionMode = MotionMode.Kinematic;

        [Header("轮子外观（可为空）")]
        public Transform leftDriveWheel;
        public Transform rightDriveWheel;
        public float wheelRadius = 0.30f;
        [Tooltip("轮子自转轴（轮子自身的本地轴）。基本几何体拼的模型是 Y 轴；换成真实模型若转错方向，改这里。")]
        public Vector3 wheelSpinAxis = Vector3.up;
        public float wheelSpinSign = -1f;

        private Rigidbody _rb;
        private float _targetLinear;
        private float _targetAngular;
        private float _currentLinear;
        private float _currentAngular;
        private WheelchairCommand _activeCommand = WheelchairCommand.Stop;

        public float CurrentLinearSpeed  { get { return _currentLinear; } }
        public float CurrentAngularSpeed { get { return _currentAngular; } }
        public WheelchairCommand ActiveCommand { get { return _activeCommand; } }
        public float Heading { get { return transform.eulerAngles.y; } }
        public bool IsMoving
        {
            get { return Mathf.Abs(_currentLinear) > 0.01f || Mathf.Abs(_currentAngular) > 0.01f; }
        }

        private void Awake()
        {
            _rb = GetComponent<Rigidbody>();
            if (_rb == null) _rb = gameObject.AddComponent<Rigidbody>();

            _rb.mass = 60f;
            _rb.interpolation = RigidbodyInterpolation.Interpolate;
            _rb.constraints = RigidbodyConstraints.FreezeRotationX | RigidbodyConstraints.FreezeRotationZ;
            _rb.isKinematic = motionMode == MotionMode.Kinematic;
            _rb.useGravity = motionMode == MotionMode.Physics;
        }

        // ------------------------------------------------------------ 指令

        public void ApplyCommand(WheelchairCommand command, float arg = 0f)
        {
            switch (command)
            {
                case WheelchairCommand.Forward:
                case WheelchairCommand.Backward:
                case WheelchairCommand.SteerLeft:
                case WheelchairCommand.SteerRight:
                case WheelchairCommand.RotateLeft:
                case WheelchairCommand.RotateRight:
                case WheelchairCommand.Stop:
                    _activeCommand = command;
                    break;

                case WheelchairCommand.SetSpeed:
                    targetSpeed = Mathf.Clamp(arg, 0.05f, maxSpeed);
                    break;

                case WheelchairCommand.SetTurnRate:
                    turnRate = Mathf.Clamp(arg, 0.05f, 4f);
                    break;

                default:
                    return;
            }

            RecomputeTargets();
        }

        /// <summary>把所有运动量瞬间清零（急停用）。</summary>
        public void EmergencyStop()
        {
            _targetLinear = 0f;
            _targetAngular = 0f;
            _currentLinear = 0f;
            _currentAngular = 0f;
            _activeCommand = WheelchairCommand.EmergencyStop;
        }

        public void SetSpeedLimit(float limit)
        {
            maxSpeed = Mathf.Clamp(limit, 0.05f, 3f);
            if (targetSpeed > maxSpeed) targetSpeed = maxSpeed;
            RecomputeTargets();
        }

        private void RecomputeTargets()
        {
            float v = Mathf.Clamp(targetSpeed, 0f, maxSpeed);
            float w = turnRate;

            switch (_activeCommand)
            {
                case WheelchairCommand.Forward:
                    _targetLinear = v;   _targetAngular = 0f;  break;
                case WheelchairCommand.Backward:
                    _targetLinear = -v;  _targetAngular = 0f;  break;
                case WheelchairCommand.SteerLeft:
                    _targetLinear = v;   _targetAngular = w;   break;
                case WheelchairCommand.SteerRight:
                    _targetLinear = v;   _targetAngular = -w;  break;
                case WheelchairCommand.RotateLeft:
                    _targetLinear = 0f;  _targetAngular = w;   break;
                case WheelchairCommand.RotateRight:
                    _targetLinear = 0f;  _targetAngular = -w;  break;
                default:
                    _targetLinear = 0f;  _targetAngular = 0f;  break;
            }
        }

        // ------------------------------------------------------------ 每帧

        public void Tick(float deltaTime)
        {
            if (deltaTime <= 0f) return;

            _currentLinear = Mathf.MoveTowards(_currentLinear, _targetLinear, linearAcceleration * deltaTime);
            _currentAngular = Mathf.MoveTowards(_currentAngular, _targetAngular, angularAcceleration * deltaTime);

            ApplyMotion(deltaTime);
            AnimateWheels(deltaTime);
        }

        private void ApplyMotion(float deltaTime)
        {
            if (motionMode == MotionMode.Kinematic)
            {
                Vector3 delta = transform.forward * (_currentLinear * deltaTime);
                _rb.MovePosition(_rb.position + delta);

                Quaternion next = _rb.rotation * Quaternion.Euler(0f, _currentAngular * Mathf.Rad2Deg * deltaTime, 0f);
                _rb.MoveRotation(Quaternion.Euler(0f, next.eulerAngles.y, 0f));
            }
            else
            {
                Vector3 velocity = transform.forward * _currentLinear;
#if UNITY_6000_0_OR_NEWER
                velocity.y = _rb.linearVelocity.y;
                _rb.linearVelocity = velocity;
#else
                velocity.y = _rb.velocity.y;
                _rb.velocity = velocity;
#endif
                _rb.angularVelocity = Vector3.up * _currentAngular;
            }
        }

        private void AnimateWheels(float deltaTime)
        {
            if (leftDriveWheel == null && rightDriveWheel == null) return;

            float radius = Mathf.Max(0.01f, wheelRadius);
            // 差速：左轮 = v - w*L/2，右轮 = v + w*L/2 （L 用两轮间距近似）
            float halfTrack = Mathf.Abs(leftDriveWheel != null && rightDriveWheel != null
                ? Vector3.Distance(leftDriveWheel.position, rightDriveWheel.position) * 0.5f
                : 0.3f);

            float leftSpeed = _currentLinear - _currentAngular * halfTrack;
            float rightSpeed = _currentLinear + _currentAngular * halfTrack;

            float leftDeg = leftSpeed / radius * Mathf.Rad2Deg * deltaTime * wheelSpinSign;
            float rightDeg = rightSpeed / radius * Mathf.Rad2Deg * deltaTime * wheelSpinSign;

            if (leftDriveWheel != null) leftDriveWheel.Rotate(wheelSpinAxis, leftDeg, Space.Self);
            if (rightDriveWheel != null) rightDriveWheel.Rotate(wheelSpinAxis, rightDeg, Space.Self);
        }
    }
}
