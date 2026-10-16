#if UNITY_EDITOR
using System;
using UnityEditor;
using UnityEditor.ShortcutManagement;
using UnityEngine;

namespace WheelchairSim.EditorTools
{
    /// <summary>
    /// 专注模式：一键把 Hierarchy / Project / Inspector / Console / Profiler / Animation
    /// 这些平时用不到的面板全部收起来，只留下 Scene + Game，方便给同学或评委演示。
    ///
    /// 用法：
    ///   - 按快捷键 <b>F</b>（在 Unity 编辑器里全局生效），或
    ///   - 菜单 Tools &gt; Wheelchair &gt; 专注模式（隐藏多余窗口）
    /// 再按一次 F 就恢复原来的面板。
    ///
    /// 注意：Scene 视图里 F 本来是"聚焦选中物体"，为了不打架，
    /// 当鼠标焦点在 Scene 视图里时，F 交还给 Unity 自带的聚焦功能。
    /// </summary>
    public static class WheelchairFocusMode
    {
        /// <summary>开关状态存在 SessionState 里：重启 Unity 会回到默认（面板都显示）。</summary>
        private const string SessionKey = "WheelchairSim.FocusMode";

        /// <summary>要收起的面板的完整类型名。</summary>
        private static readonly string[] PanelTypeNames =
        {
            "UnityEditor.SceneHierarchyWindow",
            "UnityEditor.ProjectBrowser",
            "UnityEditor.InspectorWindow",
            "UnityEditor.ConsoleWindow",
            "UnityEditor.ProfilerWindow",
            "UnityEditor.AnimationWindow"
        };

        [MenuItem("Tools/Wheelchair/专注模式（隐藏多余窗口）")]
        public static void Toggle()
        {
            bool focusing = !SessionState.GetBool(SessionKey, false);
            Apply(focusing);
        }

        // 全局快捷键 F。没有 context 参数 = 在任何编辑器窗口里都生效。
        [Shortcut("Wheelchair/Focus Mode", KeyCode.F)]
        private static void ToggleShortcut()
        {
            // 正在输入框里打字（比如改个名字），不要把 F 抢走。
            if (EditorGUIUtility.editingTextField) return;

            // Scene 视图里 F 是"聚焦选中物体"，让给 Unity 自己处理。
            EditorWindow focused = EditorWindow.focusedWindow;
            if (focused != null && focused.GetType().FullName == "UnityEditor.SceneView")
            {
                if (SceneView.lastActiveSceneView != null) SceneView.lastActiveSceneView.FrameSelected();
                return;
            }

            Toggle();
        }

        private static void Apply(bool focusing)
        {
            if (focusing) ClosePanels();
            else ReopenPanels();

            SessionState.SetBool(SessionKey, focusing);
            Debug.Log(focusing
                ? "[FocusMode] 已进入专注模式（按 F 或再点一次菜单恢复）"
                : "[FocusMode] 已退出专注模式");
        }

        private static void ClosePanels()
        {
            EditorWindow[] windows = Resources.FindObjectsOfTypeAll<EditorWindow>();
            for (int i = 0; i < windows.Length; i++)
            {
                EditorWindow window = windows[i];
                if (window == null) continue;
                if (Array.IndexOf(PanelTypeNames, window.GetType().FullName) < 0) continue;

                try { window.Close(); }
                catch (Exception) { }   // 个别窗口不允许 Close，忽略即可
            }
        }

        private static void ReopenPanels()
        {
            for (int i = 0; i < PanelTypeNames.Length; i++)
            {
                Type type = Type.GetType(PanelTypeNames[i] + ", UnityEditor");
                if (type == null) continue;

                try { EditorWindow.GetWindow(type, false); }
                catch (Exception) { }   // 个别窗口不允许自动创建，忽略即可
            }
        }
    }
}
#endif
