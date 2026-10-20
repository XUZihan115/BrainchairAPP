using UnityEngine;
#if UNITY_EDITOR
using UnityEditor;
#endif

namespace WheelchairSim
{
    /// <summary>
    /// 演示开关（"专注模式"）。
    ///
    /// 演示时屏幕上那两个 HUD 面板很挡视线，按一下 <b>F</b> 就能把屏幕上的调试面板全部隐藏，
    /// 只留下 3D 场景；再按一次恢复。在编辑器里按 Play 演示时，顺带把
    /// Hierarchy / Project / Inspector / Console 这些窗口也收起来，画面更像成品。
    ///
    /// 自己写的 OnGUI 面板只要开头检查一下 <see cref="Visible"/>，就能跟着一起隐藏。
    ///
    /// 编辑器里没进 Play 时，用菜单 Tools &gt; Wheelchair &gt; 专注模式（Ctrl+Shift+F）切换；
    /// 那个入口走的是 <see cref="ToggleEditorPanels"/>，和这里是同一套实现。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DisplayToggle : MonoBehaviour
    {
        [Tooltip("切换显示 / 隐藏的按键")]
        public KeyCode toggleKey = KeyCode.F;

        [Tooltip("隐藏时是否在左上角留一行小字（提示怎么恢复）。演示想完全干净可以关掉")]
        public bool showRestoreHint = true;

        [Tooltip("（仅编辑器）隐藏时顺便把 Hierarchy / Project / Inspector / Console 等窗口收起来")]
        public bool syncEditorPanels = true;

        private static bool _visible = true;
        private static DisplayToggle _instance;

        private GUIStyle _hintStyle;
        private bool _hintStyleReady;

        /// <summary>屏幕调试面板是否可见。所有自绘 OnGUI 面板都应该先看它。</summary>
        public static bool Visible { get { return _visible; } }

        private void Awake()
        {
            _instance = this;
            _visible = true;   // 每次进 Play 都从"显示"开始，免得受上一次退出时的状态影响
        }

        private void OnDisable()
        {
            if (_instance == this) _instance = null;
        }

        private void Update()
        {
            if (!TogglePressed()) return;
            ToggleVisible();
        }

        /// <summary>在"显示 / 隐藏"之间切换。</summary>
        public static void ToggleVisible()
        {
            SetVisible(!_visible, true);
        }

        /// <summary>设置显示状态。notify = true 时在 Console 留一行日志，方便确认按键生效了。</summary>
        public static void SetVisible(bool visible, bool notify)
        {
            _visible = visible;

#if UNITY_EDITOR
            // 打包出去以后没有编辑器窗口这回事，这段会被整体裁掉
            if (_instance != null && _instance.syncEditorPanels) SetEditorPanelsHidden(!visible);
#endif

            if (notify)
            {
                Debug.Log(visible
                    ? "[演示开关] 已恢复屏幕面板（再按一次可隐藏）"
                    : "[演示开关] 已隐藏屏幕面板（再按一次可恢复）");
            }
        }

        /// <summary>
        /// 检测切换键。旧输入系统和新输入系统都支持，所以不管 Player Settings 里
        /// Active Input Handling 选的是哪种，F 键都能用。
        /// </summary>
        private bool TogglePressed()
        {
#if ENABLE_LEGACY_INPUT_MANAGER
            if (Input.GetKeyDown(toggleKey)) return true;
#endif
#if ENABLE_INPUT_SYSTEM
            if (InputSystemPressed(toggleKey)) return true;
#endif
            return false;
        }

#if ENABLE_INPUT_SYSTEM
        private static bool InputSystemPressed(KeyCode key)
        {
            UnityEngine.InputSystem.Keyboard keyboard = UnityEngine.InputSystem.Keyboard.current;
            if (keyboard == null) return false;

            // KeyCode 和 Input System 的 Key 枚举基本同名（F / H / Space / Tab ...），按名字找就行。
            UnityEngine.InputSystem.Key mapped;
            if (!System.Enum.TryParse(key.ToString(), out mapped)) return false;
            return keyboard[mapped].wasPressedThisFrame;
        }
#endif

        private void OnGUI()
        {
            if (_visible || !showRestoreHint) return;

            if (!_hintStyleReady)
            {
                _hintStyle = new GUIStyle(GUI.skin.label) { fontSize = 12 };
                _hintStyle.normal.textColor = new Color(1f, 1f, 1f, 0.45f);
                _hintStyleReady = true;
            }

            GUI.Label(new Rect(12f, 8f, 420f, 22f),
                string.Format("屏幕面板已隐藏 · 按 {0} 恢复", toggleKey), _hintStyle);
        }

#if UNITY_EDITOR
        // ------------------------------------------------------------ 编辑器窗口
        // 下面这段只在编辑器里编译，打包成 exe / apk 后会被整体裁掉。
        // 之所以放在运行时脚本里（而不是 Assets/Editor），是为了让"运行时按 F"和
        // "编辑器菜单"共用同一份实现；Assets/Editor 里的脚本引用不到运行时的静态状态。

        private const string EditorSessionKey = "WheelchairSim.FocusMode";

        /// <summary>要收起来的编辑器窗口的完整类型名。</summary>
        private static readonly string[] EditorPanelTypeNames =
        {
            "UnityEditor.SceneHierarchyWindow",
            "UnityEditor.ProjectBrowser",
            "UnityEditor.InspectorWindow",
            "UnityEditor.ConsoleWindow",
            "UnityEditor.ProfilerWindow",
            "UnityEditor.AnimationWindow"
        };

        /// <summary>编辑器辅助窗口当前是否被收起（存在 SessionState 里，重启 Unity 回到默认）。</summary>
        public static bool EditorPanelsHidden { get { return SessionState.GetBool(EditorSessionKey, false); } }

        /// <summary>收起 / 恢复编辑器里的辅助窗口。菜单项、快捷键、运行时按 F 都走这里。</summary>
        public static void SetEditorPanelsHidden(bool hidden)
        {
            if (hidden) CloseEditorPanels();
            else ReopenEditorPanels();

            SessionState.SetBool(EditorSessionKey, hidden);
        }

        public static void ToggleEditorPanels()
        {
            SetEditorPanelsHidden(!EditorPanelsHidden);
        }

        private static void CloseEditorPanels()
        {
            EditorWindow[] windows = Resources.FindObjectsOfTypeAll<EditorWindow>();
            for (int i = 0; i < windows.Length; i++)
            {
                EditorWindow window = windows[i];
                if (window == null) continue;
                if (System.Array.IndexOf(EditorPanelTypeNames, window.GetType().FullName) < 0) continue;

                try { window.Close(); }
                catch (System.Exception) { }   // 个别窗口不允许 Close，忽略即可
            }
        }

        private static void ReopenEditorPanels()
        {
            for (int i = 0; i < EditorPanelTypeNames.Length; i++)
            {
                System.Type type = System.Type.GetType(EditorPanelTypeNames[i] + ", UnityEditor");
                if (type == null) continue;

                try { EditorWindow.GetWindow(type, false); }
                catch (System.Exception) { }   // 个别窗口不允许自动创建，忽略即可
            }
        }
#endif
    }
}
