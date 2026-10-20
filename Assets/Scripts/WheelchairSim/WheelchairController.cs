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
    ///
    /// 关于上下坡：followGround 会在车身前后左右取 5 个点向下打射线，算出脚下高度和坡面法线；
    /// alignToSlope 再让车身"躺"到坡面上（上坡抬头、下坡低头、侧着走会侧倾），
    /// 所以轮椅是真的贴着斜坡走，而不是水平地悬在坡上方。
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
        [Tooltip("Kinematic = 脚本直接摆位置（默认，演示用）；Physics = 交给刚体，会撞到障碍物被挡住")]
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

        [Header("坡面贴合")]
        [Tooltip("让车身随坡面倾斜：上坡机头抬起、下坡机头下俯、横着走会侧倾。关掉就退回『始终水平』")]
        public bool alignToSlope = true;
        [Tooltip("坡度超过这个角度就只跟高度、不跟着倾斜（和 SafetyGuard 的可爬坡角保持一致，避免在墙角乱翘）")]
        public float maxSlopeAngle = 30f;
        [Tooltip("姿态贴合的最大角速度（度/秒）。越大越跟手，越小越平滑；填 0 = 瞬间贴合")]
        public float slopeAlignSpeed = 180f;
        [Tooltip("脚下采样点的前后半径 m（车头 / 车尾各取一点）")]
        public float groundHalfLength = 0.45f;
        [Tooltip("脚下采样点的左右半径 m（左右各取一点）")]
        public float groundHalfWidth = 0.35f;

        [Header("物理组件")]
        [Tooltip("车身没有任何碰撞体时（比如导入的真实模型），按模型包围盒自动补一个 BoxCollider")]
        public bool autoAddCollider = true;
        [Tooltip("物理模式下的重心高度（相对车身原点 m）。重心放低才不容易翻车")]
        public float centerOfMassHeight = 0.30f;

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

        /// <summary>
        /// 车身航向（度）。单独记一份，而不是直接读 transform.eulerAngles.y：
        /// 车身贴着坡面倾斜之后，欧拉角里的 y 会被俯仰/侧倾"污染"，用它累积转向会慢慢跑偏。
        /// </summary>
        private float _headingYaw;

        public float CurrentLinearSpeed  { get { return _currentLinear; } }
        public float CurrentAngularSpeed { get { return _currentAngular; } }
        public WheelchairCommand ActiveCommand { get { return _activeCommand; } }
        public bool IsMoving
        {
            get { return Mathf.Abs(_currentLinear) > 0.01f || Mathf.Abs(_currentAngular) > 0.01f; }
        }

        /// <summary>航向角（度，0-360）。取车头方向在水平面上的投影，车身倾斜时也稳定。</summary>
        public float Heading
        {
            get
            {
                Vector3 forward = transform.forward;
                forward.y = 0f;
                if (forward.sqrMagnitude < 0.0001f) return _headingYaw;
                return Mathf.Atan2(forward.x, forward.z) * Mathf.Rad2Deg;
            }
        }

        private void Awake()
        {
            _rb = GetComponent<Rigidbody>();
            if (_rb == null) _rb = gameObject.AddComponent<Rigidbody>();

            _headingYaw = transform.eulerAngles.y;
            ApplyMotionMode();

            if (alignToSlope && !followGround)
                Debug.LogWarning("[WheelchairController] 坡面贴合需要先打开 followGround（要探测坡面法线），本次忽略 alignToSlope");
        }

        // ------------------------------------------------------------ 物理组件

        /// <summary>
        /// 按当前 motionMode 配置 Rigidbody，并在需要时补一个碰撞体。
        /// WheelchairBootstrap 在场景搭建完（模型缩放、摆位之后）也会调它，保证
        /// "运行时新建的组件"和"场景里拖好的组件"配置完全一致。
        /// </summary>
        public void ApplyMotionMode()
        {
            if (_rb == null) _rb = GetComponent<Rigidbody>();
            if (_rb == null) _rb = gameObject.AddComponent<Rigidbody>();

            _rb.mass = 60f;
            _rb.interpolation = RigidbodyInterpolation.Interpolate;
            _rb.collisionDetectionMode = CollisionDetectionMode.ContinuousSpeculative;

            // 两种模式都放开 X/Z 旋转：车身要能随坡面俯仰/侧倾。
            // Kinematic 下姿态由脚本写，物理模式下靠低重心 + 接触力保持直立。
            _rb.constraints = RigidbodyConstraints.None;

            if (motionMode == MotionMode.Kinematic)
            {
                _rb.isKinematic = true;
                _rb.useGravity = false;
            }
            else
            {
                _rb.isKinematic = false;
                _rb.useGravity = true;
                _rb.centerOfMass = new Vector3(0f, centerOfMassHeight, 0f);
                _rb.maxAngularVelocity = 60f;
            }

            if (autoAddCollider) EnsureBodyCollider();
        }

        /// <summary>
        /// 车身（含所有子物体）上找不到实体碰撞体时，按渲染器包围盒补一个 BoxCollider。
        /// 用基本几何体拼的模型自带 Collider，不会重复添加；导入的真实模型通常没有。
        /// </summary>
        private void EnsureBodyCollider()
        {
            Collider[] existing = GetComponentsInChildren<Collider>();
            for (int i = 0; i < existing.Length; i++)
            {
                if (existing[i] != null && !existing[i].isTrigger) return;
            }

            Bounds localBounds;
            if (!TryGetLocalBounds(out localBounds)) return;

            BoxCollider box = gameObject.AddComponent<BoxCollider>();
            box.center = localBounds.center;
            box.size = localBounds.size;

            Debug.Log(string.Format(
                "[WheelchairController] 车身上没有碰撞体，已按模型包围盒自动补一个 BoxCollider（{0:F2} x {1:F2} x {2:F2} m）",
                localBounds.size.x, localBounds.size.y, localBounds.size.z));
        }

        /// <summary>
        /// 把所有渲染器的世界包围盒换算到车身本地坐标，再合成一个本地轴对齐包围盒。
        /// 逐个取 8 个角点换算，这样即使车身当前带着旋转/缩放也不会算歪。
        /// </summary>
        private bool TryGetLocalBounds(out Bounds localBounds)
        {
            localBounds = new Bounds();
            Renderer[] renderers = GetComponentsInChildren<Renderer>();
            bool any = false;

            for (int i = 0; i < renderers.Length; i++)
            {
                Renderer renderer = renderers[i];
                if (renderer == null) continue;

                Bounds world = renderer.bounds;
                Vector3 extents = world.extents;

                for (int corner = 0; corner < 8; corner++)
                {
                    Vector3 point = world.center + new Vector3(
                        (corner & 1) == 0 ? -extents.x : extents.x,
                        (corner & 2) == 0 ? -extents.y : extents.y,
                        (corner & 4) == 0 ? -extents.z : extents.z);

                    Vector3 local = transform.InverseTransformPoint(point);
                    if (!any) { localBounds = new Bounds(local, Vector3.zero); any = true; }
                    else localBounds.Encapsulate(local);
                }
            }

            return any;
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
                Vector3 position = _rb.position;
                Vector3 next = position + transform.forward * (_currentLinear * deltaTime);

                _headingYaw = Mathf.Repeat(_headingYaw + yawDegrees, 360f);
                Quaternion heading = Quaternion.Euler(0f, _headingYaw, 0f);

                float groundY;
                Vector3 normal;
                bool sampled = SampleGround(next, position.y, out groundY, out normal);
                bool grounded = followGround && sampled;
                if (grounded) next.y = groundY;

                Quaternion target = heading;
                if (alignToSlope && grounded && Vector3.Angle(normal, Vector3.up) <= maxSlopeAngle)
                {
                    // 车头方向要在坡面内，但它在水平面上的投影必须仍然是航向，否则上坡时车会自己拐弯。
                    // 坡面内满足这一条的方向：d = f - (f·n)/n.y * up
                    Vector3 headingForward = heading * Vector3.forward;
                    Vector3 slopeForward = headingForward - Vector3.up *
                        (Vector3.Dot(headingForward, normal) / Mathf.Max(0.05f, normal.y));

                    if (slopeForward.sqrMagnitude > 0.0001f)
                        target = Quaternion.LookRotation(slopeForward.normalized, normal);
                }

                _rb.MovePosition(next);
                _rb.MoveRotation(SlewRotation(_rb.rotation, target, slopeAlignSpeed * deltaTime));
            }
            else
            {
                Vector3 velocity = transform.forward * _currentLinear;
#if UNITY_6000_0_OR_NEWER
                velocity.y = _rb.linearVelocity.y;
                _rb.linearVelocity = velocity;

                // 只接管"转向"这一个分量：俯仰/侧倾角速度留给物理算，
                // 这样车轮压到坡面时车身会自然被"压"成贴着坡面的姿态。
                Vector3 angularVelocity = _rb.angularVelocity;
                Vector3 tilt = Vector3.ProjectOnPlane(angularVelocity, Vector3.up);
                _rb.angularVelocity = Vector3.up * (-_currentAngular) + tilt;
#else
                velocity.y = _rb.velocity.y;
                _rb.velocity = velocity;

                Vector3 angularVelocity = _rb.angularVelocity;
                Vector3 tilt = Vector3.ProjectOnPlane(angularVelocity, Vector3.up);
                _rb.angularVelocity = Vector3.up * (-_currentAngular) + tilt;
#endif
            }
        }

        /// <summary>
        /// 把当前姿态朝目标姿态转，每帧最多转 maxDegrees 度（姿态平滑，避免瞬间"啪"地贴上去）。
        /// maxDegrees &lt;= 0 表示不做平滑，直接贴合。
        /// </summary>
        private static Quaternion SlewRotation(Quaternion current, Quaternion target, float maxDegrees)
        {
            if (maxDegrees <= 0f) return target;

            float angle = Quaternion.Angle(current, target);
            if (angle <= 0.0001f) return target;

            return Quaternion.Slerp(current, target, Mathf.Clamp01(maxDegrees / angle));
        }

        /// <summary>
        /// 在车身"脚下"采 5 个点（中心 + 前后左右）向下打射线，得到支撑高度和坡面法线。
        /// 用平均值作为高度、取各点法线的平均作为坡面朝向，比单点探测平滑得多，
        /// 上坡时不会一格一格地跳。
        ///
        /// 每个点都只在"抬升幅度不超过 maxStepUp"时才认，这样遇到墙不会把轮椅送上墙顶；
        /// 最后再取一次 max(平均高度, 中心点高度)，避免车中心陷进台阶里。
        /// </summary>
        private bool SampleGround(Vector3 nextPosition, float currentY, out float groundY, out Vector3 normal)
        {
            Vector3 forward = transform.forward;
            forward.y = 0f;
            if (forward.sqrMagnitude < 0.0001f) forward = Vector3.forward;
            forward.Normalize();

            // 左手坐标系：cross(up, forward) 就是车身右侧
            Vector3 right = Vector3.Cross(Vector3.up, forward).normalized;

            float sumY = 0f;
            Vector3 sumNormal = Vector3.zero;
            int used = 0;
            float centerY = float.NegativeInfinity;

            for (int i = 0; i < 5; i++)
            {
                float along = 0f;
                float side = 0f;
                switch (i)
                {
                    case 1: along = groundHalfLength; break;    // 前
                    case 2: along = -groundHalfLength; break;   // 后
                    case 3: side = groundHalfWidth; break;      // 右
                    case 4: side = -groundHalfWidth; break;     // 左
                    default: break;                             // i == 0：车身中心
                }

                Vector3 point = nextPosition + forward * along + right * side;
                Vector3 origin = new Vector3(point.x, nextPosition.y + groundProbeLift, point.z);

                int count = Physics.RaycastNonAlloc(
                    origin, Vector3.down, _groundHits, groundProbeLift + groundProbeDepth, ~0, QueryTriggerInteraction.Ignore);

                bool found = false;
                float bestY = float.NegativeInfinity;
                Vector3 bestNormal = Vector3.up;

                for (int h = 0; h < count; h++)
                {
                    Collider collider = _groundHits[h].collider;
                    if (collider == null) continue;
                    if (collider.transform.IsChildOf(transform)) continue;   // 跳过自己
                    if (_groundHits[h].point.y > bestY)
                    {
                        bestY = _groundHits[h].point.y;                       // 取该点最高的支撑面
                        bestNormal = _groundHits[h].normal;
                        found = true;
                    }
                }

                if (!found) continue;
                if (bestY - currentY > maxStepUp) continue;                  // 台阶太高（比如墙顶），这个点不认

                sumY += bestY;
                sumNormal += bestNormal;
                used++;

                if (i == 0) centerY = bestY;
            }

            if (used == 0)
            {
                groundY = currentY;      // 脚下没有地面，保持原高度
                normal = Vector3.up;
                return false;
            }

            groundY = sumY / used;
            if (!float.IsNegativeInfinity(centerY) && groundY < centerY) groundY = centerY;

            normal = sumNormal.sqrMagnitude > 0.0001f ? sumNormal.normalized : Vector3.up;
            return true;
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
