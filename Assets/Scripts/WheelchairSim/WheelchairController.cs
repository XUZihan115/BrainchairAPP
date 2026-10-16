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
    ///
    /// 角速度符号约定：正 = 左转（从上往下看的逆时针方向）。
    /// 注意 Unity 用的是左手坐标系，绕 +Y 轴的正向旋转在屏幕上其实是"顺时针"，
    /// 所以 ApplyMotion 里统一取了负号把它翻过来，否则 Q/E、A/D 的左右会反。
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

        [Header("地形跟随（上下坡用）")]
        [Tooltip("开启后轮椅贴着地面走，能自动上下坡")]
        public bool followGround = true;
        [Tooltip("向下探测地面时，射线起点比当前位置高这么多 m")]
        public float groundProbeLift = 1.5f;
        [Tooltip("向下探测地面的最大深度 m")]
        public float groundProbeDepth = 4.0f;
        [Tooltip("单帧允许的最大抬升高度 m。比这个高的面（比如墙顶）不会把轮椅抬上去")]
        public float maxStepUp = 0.25f;

        [Header("轮子外观（可为空）")]
        public Transform leftDriveWheel;
        public Transform rightDriveWheel;
        public float wheelRadius = 0.30f;
        [Tooltip("轮子自转轴（轮子自身的本地轴）。基本几何体拼的模型是 Y 轴；换成真实模型若转错方向，改这里。")]
        public Vector3 wheelSpinAxis = Vector3.up;
        public float wheelSpinSign = -1f;

        private Rigidbody _rb;
        private readonly RaycastHit[] _groundHits = new RaycastHit[8];
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
            // Unity 是左手坐标系：绕 +Y 的正向旋转在屏幕上表现为顺时针。
            // 我们的约定是"角速度为正 = 左转（逆时针）"，所以这里统一取负。
            float yawDegrees = -_currentAngular * Mathf.Rad2Deg * deltaTime;

            if (motionMode == MotionMode.Kinematic)
            {
                Vector3 next = _rb.position + transform.forward * (_currentLinear * deltaTime);
                if (followGround) next.y = SampleGroundHeight(next, _rb.position.y);
                _rb.MovePosition(next);

                Quaternion rotated = _rb.rotation * Quaternion.Euler(0f, yawDegrees, 0f);
                _rb.MoveRotation(Quaternion.Euler(0f, rotated.eulerAngles.y, 0f));
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
                _rb.angularVelocity = Vector3.up * -_currentAngular;
            }
        }

        /// <summary>
        /// 向下打一条射线求脚下地面的高度，让轮椅能顺着斜坡起伏。
        /// 只在"抬升幅度不超过 maxStepUp"时才跟随，这样遇到墙不会把轮椅送上墙顶。
        /// 取命中的最高支撑面（向下射线里最先碰到的那个），并跳过自身碰撞体。
        /// </summary>
        private float SampleGroundHeight(Vector3 nextPosition, float currentY)
        {
            Vector3 origin = new Vector3(nextPosition.x, nextPosition.y + groundProbeLift, nextPosition.z);
            float depth = groundProbeLift + groundProbeDepth;

            int count = Physics.RaycastNonAlloc(
                origin, Vector3.down, _groundHits, depth, ~0, QueryTriggerInteraction.Ignore);

            float best = float.NegativeInfinity;
            for (int i = 0; i < count; i++)
            {
                Collider collider = _groundHits[i].collider;
                if (collider == null) continue;
                if (collider.transform.IsChildOf(transform)) continue;   // 跳过自己
                if (_groundHits[i].point.y > best) best = _groundHits[i].point.y;
            }

            if (float.IsNegativeInfinity(best)) return currentY;         // 脚下没有地面，保持原高度
            if (best - currentY > maxStepUp) return currentY;            // 台阶太高（比如墙顶），不抬升
            return best;
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
