#if UNITY_EDITOR
using System.IO;
using UnityEditor;
using UnityEngine;

namespace WheelchairSim.EditorTools
{
    /// <summary>
    /// 自动给 Assets/Models/Wheelchair/Textures 下的 PBR 贴图套用正确的导入设置，
    /// 省得在 Inspector 里一张张手点（而且 sRGB 这一项非常容易点错，点错材质就废了）。
    ///
    /// 三条规则：
    ///   1) 基础色 (Base Color) 是「颜色」，保持 sRGB 勾选；
    ///   2) Metallic / Roughness / AO 是「数据」，必须关掉 sRGB，否则数值被伽马校正扭曲；
    ///   3) 法线图必须把 Texture Type 设成 Normal map —— Unity 会自动关 sRGB 并启用法线解码。
    ///      这套素材是 OpenGL 约定的法线图，和 Unity 默认约定一致，不用翻绿通道。
    ///
    /// 另外原图是 4096×4096（五张合计 45MB），这里统一压到 2048，显存省 4 倍；
    /// Android 上再单独压到 1024 + ASTC，避免手机爆显存。
    ///
    /// 改完想重新套用：在贴图上右键 → Reimport。
    /// </summary>
    public sealed class WheelchairTextureImporter : AssetPostprocessor
    {
        private const string TextureFolder = "Assets/Models/Wheelchair/Textures/";
        private const int DesktopMaxSize = 2048;
        private const int AndroidMaxSize = 1024;

        private void OnPreprocessTexture()
        {
            string path = assetPath.Replace('\\', '/');
            if (!path.StartsWith(TextureFolder, System.StringComparison.OrdinalIgnoreCase)) return;

            TextureImporter importer = assetImporter as TextureImporter;
            if (importer == null) return;

            string file = Path.GetFileNameWithoutExtension(path).ToLowerInvariant();

            importer.maxTextureSize = DesktopMaxSize;
            importer.mipmapEnabled = true;
            importer.isReadable = false;
            importer.wrapMode = TextureWrapMode.Repeat;
            importer.anisoLevel = 4;
            importer.textureCompression = TextureImporterCompression.Compressed;

            if (file.Contains("normal"))
            {
                importer.textureType = TextureImporterType.NormalMap;
                importer.convertToNormalmap = false;
            }
            else
            {
                importer.textureType = TextureImporterType.Default;
                bool isColor = file.Contains("base_color") || file.Contains("albedo") || file.Contains("diffuse");
                importer.sRGBTexture = isColor;
            }

            TextureImporterPlatformSettings android = importer.GetPlatformTextureSettings("Android");
            android.overridden = true;
            android.maxTextureSize = AndroidMaxSize;
            android.textureCompression = TextureImporterCompression.Compressed;
            android.format = TextureImporterFormat.ASTC_6x6;
            importer.SetPlatformTextureSettings(android);
        }
    }
}
#endif