# 去爬山 GoHiking 🏔

一款面向徒步登山爱好者的 Android 记录应用：离线可用、数据私有、专为山区场景设计。
从选点规划、沿途记录到回顾分析，覆盖登山出行的完整闭环。

> 当前版本 **0.5.1**（开发期）· 变更记录见 [CHANGELOG.md](CHANGELOG.md) · English: [README_EN.md](README_EN.md)

## 功能特性

| 模块 | 能力 |
| --- | --- |
| 🗺 计划线路 | 地图选点/搜索、自动推荐 3 条步行线路、手动打点兜底、去程/返程、爬升与难度估算、沿线方向箭头、线路导出 |
| 🥾 轨迹记录 | 开始/暂停/继续/结束、实时轨迹与配速、气压计海拔（可选硬件多级降级）、计步四级降级、距离/海拔提醒与 TTS 语音播报 |
| 📊 历史与统计 | 距离/时长/配速/累计爬升/卡路里（MET 估算）、海拔曲线与每公里配速图、公里/爬升分段表 |
| 📷 照片地图 | 扫描本机带位置的照片/视频（WGS-84→GCJ-02 自动转换），网格聚合气泡、缩略图条、全屏查看器，与轨迹自动关联（±30min + 500m） |
| 💾 数据私有 | 记录 JSON / GPX / 全量 ZIP 备份导出，带冲突预览的导入（ZIP 安全防线），单 Key 无云端依赖 |
| 🌐 中英双语 | 一等公民 i18n（英文兜底 + 中文），界面语言随系统；深浅色主题跟随系统 |

设计原则：**山区弱网可用**（离线兜底永远存在）、**数据不编造**（传感器不可用显示「—」，绝不伪造）、**隐私先行**（高德 SDK 必须在用户同意后才初始化）。

## 技术栈

- **语言/UI**：Kotlin 2.1 + Jetpack Compose（Material 3）+ MVVM
- **依赖注入**：Hilt ｜ **持久化**：Room + DataStore ｜ **异步**：Kotlin Coroutines + Flow
- **地图/定位/搜索**：高德 Android SDK（官方三合一包 `3dmap-location-search`）
- **构建**：AGP 8.7.3 · Gradle 8.9 · JDK 17 · minSdk 26 / targetSdk 35

## 工程结构

```
GoHiking/                     # Gradle 多模块（18 个）
├── app/                      # 壳工程：隐私门 + 页面装配
├── core/                     # 基础层
│   ├── common/               #   结果类型 / 格式化 / 折线抽稀与 JSON
│   ├── model/                #   领域模型（枚举不翻译，是协议）
│   ├── database/             #   Room（轨迹/媒体/计划线路/高程缓存）
│   ├── datastore/            #   偏好设置
│   ├── resources/            #   字符串中英双语（checkStringKeys 门禁对齐）
│   ├── designsystem/         #   主题
│   ├── location/             #   高德定位/Fused 双源、坐标转换（GCJ-02↔WGS-84）
│   ├── map/                  #   地图渲染、搜索与路径规划客户端
│   ├── elevation/            #   高程三级降级 + 线路爬升/难度评估
│   └── data/                 #   记录会话/仓库层
├── feature/                  # 页面层：home / recording / history / plan / media / io / settings
├── docs/                     # PRD · 开发设计 · 可行性研究
└── prototype/                # 可交互 HTML 原型（P-01~P-18，393×852）
```

## 快速开始

### 前置条件

- Android Studio（自带 JBR 也可，但运行 Gradle 的 JDK 需 **17**）
- Android SDK 35
- 高德开放平台 [Android Key](https://console.amap.com/)（绑定包名 `com.gohiking.app` 与签名 SHA1）

### 配置高德 Key

Key 走 `local.properties` 注入，**不提交仓库**（参考 `local.properties.example`）：

```properties
AMAP_KEY_DEBUG=你的调试Key
AMAP_KEY_RELEASE=你的发布Key
```

### 构建与验证

```bash
# 构建 debug APK（app/build/outputs/apk/debug/GoHiking-debug-v<版本>.apk）
.\gradlew.bat assembleDebug

# 完整门禁（提交前必跑）：构建 + lint + 字符串键对齐 + 单元测试
.\gradlew.bat assembleDebug :app:lintDebug :core:resources:checkStringKeys ^
  :core:data:testDebugUnitTest :core:location:testDebugUnitTest ^
  :core:common:testDebugUnitTest :core:elevation:testDebugUnitTest
```

> 运行 Gradle 的 JDK 必须是 17（AGP 8.7.3 与 JDK 25 组合会失败，详见 `docs/DEV-DESIGN.md` §7.1.1）。

### 已知限制：16 KB 页对齐设备

本项目使用的**高德三合一 SDK**（`3dmap-location-search` 10.0.700）自带的 native 库
（`libamap3dmap.so` 等）ELF program header 的 `p_align` 仍是 4 KB，**未按 16 KB 页对齐**。

- Android 15（API 35）起系统支持 16 KB 页大小，Google Play 自 2025-11-01 起对
  `targetSdk ≥ 35` 的应用强制要求 16 KB 对齐；
- 本 App `targetSdk = 35`，因此在 16 KB 页设备上加载高德地图/定位 native 库会失败；
- 该问题**只能由高德官方在 SDK 侧修复**，App 侧无法重打包或对齐第三方 `.so`。

缓解措施：升级到高德已声明支持 16 KB 的版本后，用 `tools/` 下的 ELF 检查脚本复核所有
`lib/*/*.so` 的 `p_align`；在问题解除前，应在分发说明中标注「暂不支持 16 KB 页设备」。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/PRD.md](docs/PRD.md) | 产品需求基线（功能编号 `F-XXX-nn`、验收标准、路线图） |
| [docs/DEV-DESIGN.md](docs/DEV-DESIGN.md) | 开发设计（模块/接口/算法参数/构建基线/设计补充登记） |
| [docs/FEASIBILITY-TERRAIN.md](docs/FEASIBILITY-TERRAIN.md) | 等高线/坡度/真 3D 地形可行性研究 |
| [CHANGELOG.md](CHANGELOG.md) | 版本历史与版本管理规则 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南（工程约束与提交前门禁） |

## 协议

[Apache-2.0](LICENSE)。高德 SDK 与其他第三方库各自的许可见其上游仓库。

## 版本管理

- 版本号唯一来源：`gradle.properties`（`gohiking.versionName` / `gohiking.versionCode`）
- 语义化版本：`0.x` 开发期，`1.0.0` 为首个正式发布；MINOR 位对应里程碑批次
- 任何变更在 `CHANGELOG.md` 的 `[Unreleased]` 记录，发布时定版
- **发布流程**：`CHANGELOG.md` 对应小节定版 → `git tag vX.Y.Z && git push origin vX.Y.Z`
  → GitHub Actions 自动构建 release APK（签名走仓库 Secrets，未配置时用临时 keystore）
  并创建 GitHub Release，Release 说明自动提取自 CHANGELOG 对应小节

## 开发状态

- ✅ M0 工程骨架与地图验证（v0.1.0）
- ✅ M1 轨迹记录与历史统计（v0.2.0）
- ✅ M2 计划线路（v0.3.0）
- ✅ M3 提醒/TTS、设置页、导出导入 + M4 图表、照片地图、打磨（v0.4.0）
- ✅ 两轮审查修复与文档体系收尾（v0.5.0，非里程碑批次）
- ⏳ 真机回归（M1 全流程 / 提醒 + TTS / 导出导入往返 / 照片地图 / 语言切换）与 v1.0 收尾
