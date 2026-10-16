#if UNITY_EDITOR
using System.Collections.Generic;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace WheelchairSim.EditorTools
{
    /// <summary>
    /// 一键把轮椅的三张 PBR 贴图挂到材质上，免去在 Inspector 里手工拖拽。
    ///
    /// 对应关系（Standard 与 URP Lit 两种着色器都能用）：
    ///   Albedo  _MainTex / _BaseMap  = wheelchair_Base_Color.png     颜色
    ///   Normal  _BumpMap            = wheelchair_Normal_OpenGL.png  凹凸细节
    ///   AO      _OcclusionMap       = wheelchair_Mixed_AO.png       环境光遮蔽
    ///
    /// Metallic / Roughness 两张灰度图故意不挂：Unity 需要把金属度和光滑度
    /// 合成在同一张图上（R 通道为金属度、A 通道为光滑度），而 roughness 与
    /// smoothness 的数值恰好相反，直接挂会得到完全颠倒的质感。
    /// 因此改用 Metallic 0.6 / Smoothness 0.4 两个滑条值代替。
    ///
    /// 法线贴图必须开启 _NORMALMAP 关键字才会真正参与光照计算，否则图挂了
    /// 也不起作用；遮挡贴图在内置着色器里没有对应关键字，挂上图即可生效。
    ///
    /// 菜单：Tools -> Wheelchair -> 自动挂贴图
    /// </summary>
    public static class WheelchairMaterialSetup
    {
        private const string ModelPath = "Assets/Models/Wheelchair/Wheelchair_LOW_POLY.fbx";
        private const string TextureFolder = "Assets/Models/Wheelchair/Textures/";
        private const string StandaloneMaterialPath = "Assets/Models/Wheelchair/wheelchair_material.mat";

        private const string BaseColorFileName = "wheelchair_Base_Color";
        private const string NormalFileName = "wheelchair_Normal_OpenGL";
        private const string OcclusionFileName = "wheelchair_Mixed_AO";

        private const float MetallicValue = 0.6f;
        private const float SmoothnessValue = 0.4f;
        private const float OcclusionStrengthValue = 1f;

        private const string AlbedoProperty = "_MainTex";
        private const string AlbedoPropertyUrp = "_BaseMap";
        private const string NormalProperty = "_BumpMap";
        private const string NormalKeyword = "_NORMALMAP";
        private const string OcclusionProperty = "_OcclusionMap";
        private const string OcclusionStrengthProperty = "_OcclusionStrength";
        private const string MetallicProperty = "_Metallic";
        private const string SmoothnessProperty = "_Glossiness";
        private const string SmoothnessPropertyUrp = "_Smoothness";

        [MenuItem("Tools/Wheelchair/自动挂贴图")]
        private static void ApplyAll()
        {
            Texture2D baseColor = LoadTexture(BaseColorFileName);
            Texture2D normalMap = LoadTexture(NormalFileName);
            Texture2D occlusionMap = LoadTexture(OcclusionFileName);

            if (baseColor == null || normalMap == null || occlusionMap == null)
            {
                EditorUtility.DisplayDialog(
                    "自动挂贴图",
                    "有贴图没找到，先让 Unity 把图片导入完，再点一次这个菜单。\n\n请看 Console 窗口里的黄色警告，缺的是哪一个文件。",
                    "好");
                return;
            }

            List<Material> targets = CollectTargets();

            if (targets.Count == 0)
            {
                EditorUtility.DisplayDialog(
                    "自动挂贴图",
                    "没找到任何材质。请确认模型还在这个位置：\n" + ModelPath,
                    "好");
                return;
            }

            int changedCount = 0;
            for (int i = 0; i < targets.Count; i++)
            {
                if (Configure(targets[i], baseColor, normalMap, occlusionMap))
                {
                    EditorUtility.SetDirty(targets[i]);
                    changedCount++;
                }
            }

            AssetDatabase.SaveAssets();

            string detail = Describe(targets);
            Debug.Log("[轮椅] 自动挂贴图完成：找到 " + targets.Count + " 个材质，本次改动 " + changedCount
                      + " 个。\n明细：" + detail
                      + "\n下一步：按 Unity 顶部的播放按钮，检查轮椅外观。");

            EditorUtility.DisplayDialog(
                "自动挂贴图",
                "完成！找到 " + targets.Count + " 个材质。\n\n" + detail
                + "\n\n下一步：按 Unity 顶部的播放按钮，看轮椅有没有颜色和细节。",
                "好");
        }

        /// <summary>
        /// 找出模型实际会使用的材质。优先取模型预制体上渲染器引用的材质（运行时用的就是它们），
        /// 再补上模型里没被引用的材质子资源，最后把手建的独立材质也一起填好。
        /// </summary>
        private static List<Material> CollectTargets()
        {
            List<Material> targets = new List<Material>();

            GameObject modelPrefab = AssetDatabase.LoadAssetAtPath<GameObject>(ModelPath);
            if (modelPrefab != null)
            {
                Renderer[] renderers = modelPrefab.GetComponentsInChildren<Renderer>(true);
                for (int i = 0; i < renderers.Length; i++)
                {
                    Material[] materials = renderers[i].sharedMaterials;
                    for (int j = 0; j < materials.Length; j++)
                    {
                        AddUnique(targets, materials[j]);
                    }
                }
            }

            Object[] subAssets = AssetDatabase.LoadAllAssetsAtPath(ModelPath);
            for (int i = 0; i < subAssets.Length; i++)
            {
                AddUnique(targets, subAssets[i] as Material);
            }

            AddUnique(targets, AssetDatabase.LoadAssetAtPath<Material>(StandaloneMaterialPath));

            return targets;
        }

        private static void AddUnique(List<Material> list, Material material)
        {
            if (material == null) return;
            if (list.Contains(material)) return;
            list.Add(material);
        }

        private static bool Configure(Material material, Texture2D baseColor, Texture2D normalMap, Texture2D occlusionMap)
        {
            bool changed = false;

            changed |= SetTexture(material, AlbedoProperty, baseColor);
            changed |= SetTexture(material, AlbedoPropertyUrp, baseColor);

            changed |= SetTexture(material, NormalProperty, normalMap);
            if (material.HasProperty(NormalProperty) && !material.IsKeywordEnabled(NormalKeyword))
            {
                material.EnableKeyword(NormalKeyword);
                changed = true;
            }

            changed |= SetTexture(material, OcclusionProperty, occlusionMap);
            changed |= SetFloat(material, OcclusionStrengthProperty, OcclusionStrengthValue);

            changed |= SetFloat(material, MetallicProperty, MetallicValue);
            changed |= SetFloat(material, SmoothnessProperty, SmoothnessValue);
            changed |= SetFloat(material, SmoothnessPropertyUrp, SmoothnessValue);

            return changed;
        }

        private static Texture2D LoadTexture(string fileNameWithoutExtension)
        {
            string path = TextureFolder + fileNameWithoutExtension + ".png";
            Texture2D texture = AssetDatabase.LoadAssetAtPath<Texture2D>(path);
            if (texture == null)
            {
                Debug.LogWarning("[轮椅] 找不到贴图（可能还没导入完）：" + path);
            }
            return texture;
        }

        private static bool SetTexture(Material material, string property, Texture2D texture)
        {
            if (texture == null) return false;
            if (!material.HasProperty(property)) return false;
            if (material.GetTexture(property) == texture) return false;
            material.SetTexture(property, texture);
            return true;
        }

        private static bool SetFloat(Material material, string property, float value)
        {
            if (!material.HasProperty(property)) return false;
            if (Mathf.Approximately(material.GetFloat(property), value)) return false;
            material.SetFloat(property, value);
            return true;
        }

        private static string Describe(List<Material> materials)
        {
            if (materials.Count == 0) return "（一个都没找到）";

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < materials.Count; i++)
            {
                if (i > 0) builder.Append("；");
                builder.Append(materials[i].name);
                builder.Append("（");
                builder.Append(materials[i].shader != null ? materials[i].shader.name : "无着色器");
                builder.Append("）");
            }
            return builder.ToString();
        }
    }
}
#endif