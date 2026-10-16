using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 一键搭建整个仿真场景。
    ///
    /// 用法（3 步）：
    ///   1) 新建一个空的 3D 场景（File -> New Scene -> Basic/Empty）；
    ///   2) 在 Hierarchy 里新建一个空物体，把本脚本挂上去；
    ///   3) 按 Play。
    /// 地面、灯光、相机、轮椅、HUD、指令源都会自动创建。
    /// 之后想接真设备，只要打开对应的开关（TCP / 串口 / 蓝牙）即可。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WheelchairBootstrap : MonoBehaviour
    {
        [Header("场景自动搭建")]
        public bool buildGround = true;
        public bool buildObstacles = true;
        public bool createCameraIfMissing = true;
        public bool createLightIfMissing = true;
        public bool followCamera = true;
        public Vector3 cameraOffset = new Vector3(0f, 3.6f, -6.0f);
        public float cameraSmoothing = 6f;

        [Header("轮椅")]
        [Tooltip("留空 = 用基本几何体自动拼一辆；有真实模型时把预制体拖进来")]
        public GameObject wheelchairPrefab;
        public Vector3 spawnPosition = Vector3.zero;

        [Header("指令来源开关")]
        public bool enableLocalInput = true;

        [Tooltip("模拟蓝牙设备，用于在没有硬件时验证整条协议链路")]
        public bool enableMockBluetooth = true;
        public bool mockBluetoothRequiresHeartbeat = false;

        public bool enableTcp = false;
        public int tcpPort = 8888;

        public bool enableSerial = false;
        public string serialPortName = "COM5";
        public int serialBaudRate = 115200;

        public bool enableAndroidBluetooth = false;
        public string bluetoothDeviceName = "HC-06";
        public string bluetoothDeviceAddress = "00:11:22:33:44:55";

        [Tooltip("Auto = 自动识别二进制帧或 ASCII 文本行")]
        public ProtocolMode protocolMode = ProtocolMode.Auto;

        [Header("安全参数")]
        public float speedLimit = 1.0f;
        public float heartbeatTimeout = 2.0f;
        public bool obstacleSensorEnabled = true;
        public float obstacleStopDistance = 0.40f;
        public float batteryPercent = 78f;

        private WheelchairController _controller;
        private WheelchairHub _hub;
        private Camera _camera;

        private void Start()
        {
            Application.targetFrameRate = 60;
            Application.runInBackground = true;

            EnsureCamera();
            EnsureLight();
            BuildEnvironment();

            GameObject wheelchair = BuildWheelchair(out Transform leftWheel, out Transform rightWheel, out float wheelRadius);

            _controller = wheelchair.GetComponent<WheelchairController>();
            if (_controller == null) _controller = wheelchair.AddComponent<WheelchairController>();
            _controller.leftDriveWheel = leftWheel;
            _controller.rightDriveWheel = rightWheel;
            _controller.wheelRadius = wheelRadius;

            Rigidbody body = wheelchair.GetComponent<Rigidbody>();
            if (body == null) body = wheelchair.AddComponent<Rigidbody>();
            body.mass = 60f;
            body.interpolation = RigidbodyInterpolation.Interpolate;
            body.constraints = RigidbodyConstraints.FreezeRotationX | RigidbodyConstraints.FreezeRotationZ;
            body.isKinematic = _controller.motionMode == MotionMode.Kinematic;
            body.useGravity = _controller.motionMode == MotionMode.Physics;

            _hub = gameObject.GetComponent<WheelchairHub>();
            if (_hub == null) _hub = gameObject.AddComponent<WheelchairHub>();
            _hub.Controller = _controller;
            _hub.Safety = new SafetyGuard
            {
                WatchdogEnabled = true,
                HeartbeatTimeout = heartbeatTimeout,
                ObstacleSensorEnabled = obstacleSensorEnabled,
                ObstacleStopDistance = obstacleStopDistance,
                SensorMaxDistance = 2.0f,
                SpeedLimit = speedLimit,
                AngularLimit = 1.5f
            };
            _hub.BatteryPercent = batteryPercent;

            HudOverlay hud = gameObject.GetComponent<HudOverlay>();
            if (hud == null) hud = gameObject.AddComponent<HudOverlay>();
            hud.Hub = _hub;

            SetupSources(hud);

            if (_camera != null) _camera.transform.position = spawnPosition + cameraOffset;
        }

        private void Update()
        {
            if (!followCamera || _camera == null || _controller == null) return;

            Vector3 target = _controller.transform.position + cameraOffset;
            float t = 1f - Mathf.Exp(-cameraSmoothing * Time.deltaTime);
            _camera.transform.position = Vector3.Lerp(_camera.transform.position, target, t);
            _camera.transform.LookAt(_controller.transform.position + Vector3.up * 0.7f);
        }

        // -------------------------------------------------------- 指令源

        private void SetupSources(HudOverlay hud)
        {
            if (enableLocalInput)
            {
                _hub.AddSource(new LocalCommandSource());
            }

            if (enableMockBluetooth)
            {
                MockBluetoothLink link = new MockBluetoothLink("SimDevice-01");
                BluetoothCommandSource source = new BluetoothCommandSource(link, protocolMode, mockBluetoothRequiresHeartbeat);
                _hub.AddSource(source);
                hud.MockLink = link;
            }

            if (enableTcp)
            {
                _hub.AddSource(new TcpCommandSource(tcpPort, protocolMode));
            }

            if (enableSerial)
            {
                _hub.AddSource(new SerialCommandSource(serialPortName, serialBaudRate, protocolMode));
            }

            if (enableAndroidBluetooth)
            {
                AndroidSppLink link = new AndroidSppLink(bluetoothDeviceName, bluetoothDeviceAddress);
                _hub.AddSource(new BluetoothCommandSource(link, protocolMode, true));
            }
        }

        // ---------------------------------------------------------- 场景

        private GameObject BuildWheelchair(out Transform leftWheel, out Transform rightWheel, out float wheelRadius)
        {
            GameObject wheelchair;

            if (wheelchairPrefab != null)
            {
                wheelchair = Instantiate(wheelchairPrefab, spawnPosition, Quaternion.identity);
                wheelchair.name = "Wheelchair";
                leftWheel = FindChildByName(wheelchair.transform, "DriveWheelL", "WheelL", "Wheel_L", "LeftWheel");
                rightWheel = FindChildByName(wheelchair.transform, "DriveWheelR", "WheelR", "Wheel_R", "RightWheel");
                wheelRadius = 0.30f;
            }
            else
            {
                WheelchairModelFactory.BuildResult built = WheelchairModelFactory.Create(null);
                wheelchair = built.Root;
                wheelchair.transform.position = spawnPosition;
                leftWheel = built.LeftDriveWheel;
                rightWheel = built.RightDriveWheel;
                wheelRadius = built.WheelRadius;
            }

            return wheelchair;
        }

        private void BuildEnvironment()
        {
            if (buildGround)
            {
                GameObject ground = GameObject.CreatePrimitive(PrimitiveType.Plane);
                ground.name = "Ground";
                ground.transform.position = Vector3.zero;
                ground.transform.localScale = new Vector3(8f, 1f, 8f);   // 80m x 80m
                ApplyMaterial(ground, MakeMaterial(new Color(0.30f, 0.32f, 0.35f)));
            }

            if (buildObstacles)
            {
                Material wallMat = MakeMaterial(new Color(0.82f, 0.52f, 0.20f));
                Material coneMat = MakeMaterial(new Color(0.90f, 0.28f, 0.24f));

                CreateBox(new Vector3(0f, 0.6f, 6.0f), new Vector3(2.0f, 1.2f, 1.2f), wallMat, "Obstacle_Wall_1");
                CreateBox(new Vector3(-3.5f, 0.6f, 9.0f), new Vector3(1.4f, 1.2f, 1.4f), wallMat, "Obstacle_Wall_2");
                CreateBox(new Vector3(3.5f, 0.6f, 12.0f), new Vector3(1.4f, 1.2f, 1.4f), wallMat, "Obstacle_Wall_3");

                CreateCylinder(new Vector3(-1.6f, 0.35f, 3.5f), new Vector3(0.5f, 0.35f, 0.5f), coneMat, "Obstacle_Cone_1");
                CreateCylinder(new Vector3(1.6f, 0.35f, 4.5f), new Vector3(0.5f, 0.35f, 0.5f), coneMat, "Obstacle_Cone_2");
            }
        }

        private void EnsureCamera()
        {
            _camera = Camera.main;
            if (_camera == null) _camera = FindFirst<Camera>();

            if (_camera == null && createCameraIfMissing)
            {
                GameObject go = new GameObject("Main Camera");
                go.tag = "MainCamera";
                _camera = go.AddComponent<Camera>();
                if (FindFirst<AudioListener>() == null) go.AddComponent<AudioListener>();
            }

            if (_camera != null)
            {
                _camera.transform.position = spawnPosition + cameraOffset;
                _camera.transform.LookAt(spawnPosition + Vector3.up * 0.7f);
            }
        }

        private void EnsureLight()
        {
            if (FindFirst<Light>() != null) return;
            if (!createLightIfMissing) return;

            GameObject go = new GameObject("Directional Light");
            Light lightComponent = go.AddComponent<Light>();
            lightComponent.type = LightType.Directional;
            lightComponent.intensity = 1.1f;
            lightComponent.color = new Color(1f, 0.97f, 0.92f);
            go.transform.rotation = Quaternion.Euler(50f, -30f, 0f);
        }

        // ---------------------------------------------------------- 工具

        private static void CreateBox(Vector3 position, Vector3 scale, Material material, string name)
        {
            GameObject go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.position = position;
            go.transform.localScale = scale;
            ApplyMaterial(go, material);
        }

        private static void CreateCylinder(Vector3 position, Vector3 scale, Material material, string name)
        {
            GameObject go = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            go.name = name;
            go.transform.position = position;
            go.transform.localScale = scale;
            ApplyMaterial(go, material);
        }

        private static void ApplyMaterial(GameObject go, Material material)
        {
            Renderer renderer = go.GetComponent<Renderer>();
            if (renderer != null && material != null) renderer.sharedMaterial = material;
        }

        private static Material MakeMaterial(Color color)
        {
            Shader shader = Shader.Find("Universal Render Pipeline/Lit");
            if (shader == null) shader = Shader.Find("Standard");
            if (shader == null) shader = Shader.Find("HDRP/Lit");
            if (shader == null) shader = Shader.Find("Sprites/Default");

            // 如果连 Sprites/Default 都找不到（例如被裁剪掉的构建），
            // new Material(null) 会抛异常或产生粉红色材质，这里直接放弃着色，只保留默认外观。
            if (shader == null) return null;

            Material material = new Material(shader);
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", color);
            if (material.HasProperty("_Color"))     material.SetColor("_Color", color);
            material.color = color;
            return material;
        }

        private static Transform FindChildByName(Transform root, params string[] candidates)
        {
            for (int i = 0; i < candidates.Length; i++)
            {
                Transform found = SearchRecursive(root, candidates[i]);
                if (found != null) return found;
            }
            return null;
        }

        private static Transform SearchRecursive(Transform node, string name)
        {
            if (string.Equals(node.name, name, System.StringComparison.OrdinalIgnoreCase)) return node;
            for (int i = 0; i < node.childCount; i++)
            {
                Transform found = SearchRecursive(node.GetChild(i), name);
                if (found != null) return found;
            }
            return null;
        }

        private static T FindFirst<T>() where T : Object
        {
#if UNITY_2023_1_OR_NEWER
            return Object.FindAnyObjectByType<T>();
#else
            return Object.FindObjectOfType<T>();
#endif
        }
    }
}
