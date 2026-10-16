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