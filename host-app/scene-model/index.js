/**
 * scene-model —— 脑控轮椅项目的 3D 场景与轮椅模型（Three.js r160，纯程序化）。
 *
 * 目录约定：
 *   materials.js  调色板、材质、贴图、文字精灵
 *   track.js      跑道分段数据 + heightAt / slopeRadAt / zoneAt / pitAt + 网格构建
 *   wheelchair.js 轮椅尺寸表 WHEELCHAIR_SPEC + buildWheelchair()
 *   world.js      World：渲染器 / 光照 / 环境贴图 / 跟随相机（把上面三块装起来）
 *
 * 只用模型、自己写渲染循环的话，import 这一行就够：
 *   import { buildWheelchair, makeMaterials, heightAt, SEGMENTS } from './scene-model/index.js';
 */
export * from './world.js';
