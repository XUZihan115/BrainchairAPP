using UnityEngine;

namespace WheelchairSim
{
    /// <summary>
    /// 用基本几何体拼一辆"能看的"轮椅，用于在没有美术资源时先跑通逻辑。
    /// 拿到企业提供的真实模型后，把 WheelchairBootstrap 里的 wheelchairPrefab
    /// 指过去即可，本类就不再需要。
    ///
    /// 尺寸按真实电动轮椅估算：
    ///   驱动轮半径 0.30 m，两轮间距 0.60 m，坐垫高约 0.45 m。
    /// </summary>
    public static class WheelchairModelFactory
    {
        public struct BuildResult
        {
            public GameObject Root;
            public Transform LeftDriveWheel;
            public Transform RightDriveWheel;
            public float WheelRadius;
        }

        public static BuildResult Create(Transform parent = null)
        {
            BuildResult result = new BuildResult();

            GameObject root = new GameObject("Wheelchair");
            if (parent != null) root.transform.SetParent(parent, false);
            result.Root = root;
            result.WheelRadius = 0.30f;

            Material frameMat = MakeMaterial(new Color(0.16f, 0.17f, 0.20f));  // 深灰车架
            Material seatMat  = MakeMaterial(new Color(0.10f, 0.35f, 0.75f));  // 蓝色坐垫
            Material wheelMat = MakeMaterial(new Color(0.05f, 0.05f, 0.06f));  // 黑色轮胎
            Material rimMat   = MakeMaterial(new Color(0.75f, 0.76f, 0.78f));  // 银色轮毂

            // ---- 底盘 ----
            AddBox(root.transform, "Frame", new Vector3(0f, 0.33f, 0f),
                   new Vector3(0.56f, 0.06f, 1.00f), frameMat);

            // ---- 坐垫 ----
            AddBox(root.transform, "Seat", new Vector3(0f, 0.45f, -0.02f),
                   new Vector3(0.54f, 0.09f, 0.50f), seatMat);

            // ---- 靠背 ----
            AddBox(root.transform, "Backrest", new Vector3(0f, 0.72f, -0.28f),
                   new Vector3(0.54f, 0.55f, 0.08f), seatMat);

            // ---- 扶手 ----
            AddBox(root.transform, "ArmrestL", new Vector3(-0.30f, 0.60f, -0.02f),
                   new Vector3(0.05f, 0.05f, 0.46f), frameMat);
            AddBox(root.transform, "ArmrestR", new Vector3(0.30f, 0.60f, -0.02f),
                   new Vector3(0.05f, 0.05f, 0.46f), frameMat);

            // ---- 脚踏板 ----
            AddBox(root.transform, "Footrest", new Vector3(0f, 0.14f, 0.52f),
                   new Vector3(0.46f, 0.04f, 0.26f), frameMat);

            // ---- 两个大驱动轮（圆柱默认轴向为 Y，绕 Z 转 90° 后轴向变成 X）----
            float radius = result.WheelRadius;
            float width = 0.06f;
            result.LeftDriveWheel = AddCylinder(root.transform, "DriveWheelL",
                new Vector3(-0.30f, radius, -0.06f),
                new Vector3(radius * 2f, width * 0.5f, radius * 2f),
                new Quaternion(0f, 0f, 0.7071068f, 0.7071068f), wheelMat);

            result.RightDriveWheel = AddCylinder(root.transform, "DriveWheelR",
                new Vector3(0.30f, radius, -0.06f),
                new Vector3(radius * 2f, width * 0.5f, radius * 2f),
                new Quaternion(0f, 0f, 0.7071068f, 0.7071068f), wheelMat);

            // 轮毂装饰
            AddCylinder(result.LeftDriveWheel, "HubL", Vector3.zero,
                new Vector3(0.55f, 1.45f, 0.55f), Quaternion.identity, rimMat);
            AddCylinder(result.RightDriveWheel, "HubR", Vector3.zero,
                new Vector3(0.55f, 1.45f, 0.55f), Quaternion.identity, rimMat);

            // ---- 两个前万向轮 ----
            AddCylinder(root.transform, "CasterL", new Vector3(-0.22f, 0.09f, 0.40f),
                new Vector3(0.18f, 0.04f, 0.18f), new Quaternion(0f, 0f, 0.7071068f, 0.7071068f), wheelMat);
            AddCylinder(root.transform, "CasterR", new Vector3(0.22f, 0.09f, 0.40f),
                new Vector3(0.18f, 0.04f, 0.18f), new Quaternion(0f, 0f, 0.7071068f, 0.7071068f), wheelMat);

            // ---- 座椅靠枕（顺便当作"脑电帽用户"的位置提示）----
            AddBox(root.transform, "Headrest", new Vector3(0f, 1.06f, -0.30f),
                   new Vector3(0.30f, 0.18f, 0.10f), seatMat);

            return result;
        }

        // ------------------------------------------------------------ 工具

        private static GameObject AddBox(Transform parent, string name, Vector3 localPosition,
                                         Vector3 localScale, Material material)
        {
            GameObject go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPosition;
            go.transform.localScale = localScale;
            ApplyMaterial(go, material);
            return go;
        }

        private static Transform AddCylinder(Transform parent, string name, Vector3 localPosition,
                                             Vector3 localScale, Quaternion localRotation, Material material)
        {
            GameObject go = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPosition;
            go.transform.localRotation = localRotation;
            go.transform.localScale = localScale;
            ApplyMaterial(go, material);
            return go.transform;
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

            // 四种着色器一个都找不到时不要 new Material(null)：
            // 那会抛异常或生成粉红色材质。返回 null，ApplyMaterial 会跳过赋值，
            // 物体保留 CreatePrimitive 自带的默认材质。
            if (shader == null) return null;

            Material material = new Material(shader);
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", color);
            if (material.HasProperty("_Color"))     material.SetColor("_Color", color);
            material.color = color;
            return material;
        }
    }
}
