#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace WheelchairSim.EditorTools
{
    /// <summary>
    /// 备用方案：用 Unity 官方 API 重新生成仿真场景。
    ///
    /// 正常情况下直接用 Assets/Scenes/Main.unity 就够了，本菜单只是保险：
    /// 万一场景文件因为版本差异打不开，或者你不小心把场景改坏了，
    /// 点一下本菜单，Unity 会用官方接口重新生成一个内容一致的场景。
    ///
    /// 菜单：Tools -> Wheelchair -> 重新生成仿真场景
    /// </summary>
    public sealed class WheelchairSceneBuilder
    {
        private const string SceneFolder = "Assets/Scenes";
        private const string ScenePath = "Assets/Scenes/Main.unity";
        private const string RootName = "WheelchairSim";

        /// <summary>工程自带的天空盒材质（源自 Unity Essentials 的 SkyBrightCloudy）。</summary>
        private const string SkyboxMaterialPath = "Assets/Skybox/SkyBrightCloudy.mat";

        [MenuItem("Tools/Wheelchair/重新生成仿真场景")]
        public static void BuildScene()
        {
            // 当前场景有未保存改动时先问清楚，避免误删用户正在改的东西。
            if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.Log("[Wheelchair] 已取消场景重建。");
                return;
            }

            UnityEngine.SceneManagement.Scene scene =
                EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

            GameObject root = new GameObject(RootName);
            WheelchairBootstrap bootstrap = root.AddComponent<WheelchairBootstrap>();
            if (bootstrap == null)
            {
                Debug.LogError("[Wheelchair] 挂载 WheelchairBootstrap 失败，请先确认脚本编译通过（无红色报错）。");
                return;
            }

            // 新建的空场景用的是 Unity 内置天空盒，这里换成工程自带的那个（要赶在保存之前）
            ApplySkybox();

            if (!AssetDatabase.IsValidFolder(SceneFolder))
            {
                AssetDatabase.CreateFolder("Assets", "Scenes");
            }

            if (!EditorSceneManager.SaveScene(scene, ScenePath))
            {
                Debug.LogError("[Wheelchair] 场景保存失败：" + ScenePath);
                return;
            }

            RegisterBuildScene();
            AssetDatabase.Refresh();

            Debug.Log("[Wheelchair] 仿真场景已重新生成：" + ScenePath + "，直接按 Play 即可。");
            EditorUtility.DisplayDialog(
                "轮椅仿真",
                "场景已重新生成：\n" + ScenePath + "\n\n直接按 Play，然后在 Game 窗口里点一下再敲 WASD。",
                "好");
        }

        /// <summary>
        /// 一键把**当前打开的场景**的天空盒换成 SkyBrightCloudy。
        /// 菜单：Tools -> Wheelchair -> 应用天空盒（SkyBrightCloudy）
        ///
        /// 正常情况下不用点：Main.unity 里已经引用了这个材质。
        /// 这个菜单是保险——万一你换了场景、或者场景被重建过导致天空盒回到 Unity 默认，点一下即可恢复。
        /// </summary>
        [MenuItem("Tools/Wheelchair/应用天空盒（SkyBrightCloudy）")]
        public static void ApplySkyboxMenu()
        {
            if (!ApplySkybox()) return;

            EditorSceneManager.MarkSceneDirty(UnityEngine.SceneManagement.SceneManager.GetActiveScene());
            Debug.Log("[Wheelchair] 天空盒已应用，记得 Ctrl+S 保存场景。");
        }

        /// <summary>把当前打开场景的天空盒设为工程自带材质；找不到就保留 Unity 默认天空盒。</summary>
        private static bool ApplySkybox()
        {
            Material skybox = AssetDatabase.LoadAssetAtPath<Material>(SkyboxMaterialPath);
            if (skybox == null)
            {
                Debug.LogWarning("[Wheelchair] 找不到天空盒材质 " + SkyboxMaterialPath +
                                 "（可能还在导入中），本次保持 Unity 默认天空盒。");
                return false;
            }

            RenderSettings.skybox = skybox;
            return true;
        }

        /// <summary>把场景登记进 Build Settings，以后出安装包不会漏。</summary>
        private static void RegisterBuildScene()
        {
            EditorBuildSettingsScene[] scenes = EditorBuildSettings.scenes;
            for (int i = 0; i < scenes.Length; i++)
            {
                if (scenes[i].path == ScenePath) return;
            }

            EditorBuildSettingsScene[] next = new EditorBuildSettingsScene[scenes.Length + 1];
            System.Array.Copy(scenes, next, scenes.Length);
            next[scenes.Length] = new EditorBuildSettingsScene(ScenePath, true);
            EditorBuildSettings.scenes = next;
        }
    }
}
#endif