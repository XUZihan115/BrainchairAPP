#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.ShortcutManagement;
using UnityEngine;

namespace WheelchairSim.EditorTools
{
    /// <summary>
    /// 专注模式（编辑器侧入口）：一键把 Hierarchy / Project / Inspector / Console / Profiler / Animation
    /// 这些平时用不到的面板全部收起来，只留下 Scene + Game，方便给同学或评委演示。
    ///
    /// 真正的实现在运行时的 <see cref="WheelchairSim.DisplayToggle"/> 里，
    /// 这里只是转发，保证"编辑器里按 F"和"Play 时按 F"用的是同一套逻辑。
    ///
    /// 用法：
    ///   - Play 中：直接按 <b>F</b>（会同时隐藏屏幕 HUD 和编辑器窗口）；
    ///   - 编辑器里（没进 Play）：按 <b>Ctrl+Shift+F</b>，或菜单 Tools &gt; Wheelchair &gt; 专注模式；
    ///   - 编辑器全局快捷键 <b>F</b> 也注册了，但当鼠标焦点在 Scene 视图时让给 Unity 自带的"聚焦选中物体"。
    /// </summary>
    public static class WheelchairFocusMode
    {
        [MenuItem("Tools/Wheelchair/专注模式（隐藏多余窗口） %#f")]
        public static void Toggle()
        {
            DisplayToggle.ToggleEditorPanels();
        }

        // 全局快捷键 F。没有 context 参数 = 在任何编辑器窗口里都生效。
        [Shortcut("Wheelchair/Focus Mode", KeyCode.F)]
        private static void ToggleShortcut()
        {
            // Play 模式下由运行时的 DisplayToggle 统一处理（它还会把屏幕 HUD 一起隐藏，
            // 并且会调到这里）。两边都响应的话状态会被翻转两次，等于没切。
            if (EditorApplication.isPlaying) return;

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
    }
}
#endif
