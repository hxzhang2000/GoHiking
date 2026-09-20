# 去爬山 · GoHiking 开发设计文档（TDD）

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 去爬山 GoHiking 开发设计文档 |
| 文档版本 | v1.7 |
| 文档状态 | 初稿（**§9.2 的可 PC 化项已实测完成**，见 §9.2.1；其余 15 行分列 §9.2.2「缺 Key / 构建产物」与 §9.2.3「必须真机」；§9.5 的 C 类已全部裁定，D 类已全部落地 PRD） |
| 最后更新 | 2026-09-19 |
| 上游需求基线 | [docs/PRD.md](./PRD.md) **v1.14**（270 条需求，已评审） |
| 相关文档 | [docs/FEASIBILITY-TERRAIN.md](./FEASIBILITY-TERRAIN.md) v1.2 |
| 代码仓库 | https://github.com/hxzhang2000/GoHiking |
| 文档用途 | 实现规格 / 编码依据 / 代码评审基准 |

---

## 0. 文档边界与使用方式

### 0.1 本文档与 PRD 的分工

| 文档 | 回答的问题 | 变更频率 | 变更流程 |
| --- | --- | --- | --- |
| **PRD.md** | **做什么**（需求、范围、验收标准） | 低 | 先改 PRD → 升版本号 → 再改代码 |
| **DEV-DESIGN.md（本文档）** | **怎么做**（模块、接口、算法、参数） | 高（随编码演进） | 直接改本文档 → 同步改代码 |

**硬规则**：

1. **本文档不复制 PRD 的需求正文**，一律用需求编号引用（如「实现 `F-REC-05`」）。需求描述的权威来源永远是 PRD。
2. 如果实现中发现需求有问题，**改 PRD，不要在本文档里绕开**。
3. 如果本文档与 PRD 冲突，**以 PRD 为准**，并必须提 Issue 修正本文档。
4. 本文档可以补充 PRD 未涉及的**实现层决策**（新增表、新增接口、算法参数），但不得新增或削减功能。补充项统一在 §9.3 登记。

### 0.2 文档结构导航

| 章节 | 内容 | 什么时候看 |
| --- | --- | --- |
| §1 | 全局约定与工程基线（依赖、风格、隐私合规时序） | 开工第一天 |
| §2 | 模块与分层落地 | 建工程 / 加模块时 |
| §3 | 数据层设计（Room 版本、DAO、事务） | 写数据读写时 |
| §4 | 关键算法规格与参数表 | 写坐标/海拔/计步/抽稀/聚类时 |
| §5 | 页面 × ViewModel 契约 | 写任何一个界面时 |
| §6 | 平台能力实现（前台服务、定位、传感器、地图、TTS） | 接系统能力时 |
| §7 | 构建与 CI | M0 / 发版 |
| §8 | 测试计划 | 每个里程碑收尾 |
| §9 | 需求映射、待办、**隐含假设与待裁定清单** | 排期 / 复盘 / **动手前必读** |

### 0.3 标记约定

| 标记 | 含义 |
| --- | --- |
| `⚠ TODO(M0)` | 需在 M0 里程碑用真机实测或查证后回填，**不得凭猜测定稿** |
| `[待定]` | 尚未决策，需在对应里程碑开始前拍板 |
| `＋设计补充` | PRD 未要求、由本文档补充的实现层设计，已登记在 §9.3 |

---

## 1. 全局约定与工程基线

### 1.1 包名、模块名与命名约定

| 项 | 约定 |
| --- | --- |
| 应用包名 | `com.gohiking.app`（PRD 7.3 的 `generator.packageName` 已固定为此值，**不得更改**） |
| 模块命名 | `:app`、`:core:<name>`、`:feature:<name>` |
| 包结构 | `<模块>/src/main/kotlin/com/gohiking/<模块名>/`，如 `com.gohiking.core.database` |
| 类命名 | ViewModel `XxxViewModel`；UiState `XxxUiState`；UiEvent `XxxEvent`；UiEffect `XxxEffect`；Repository 接口 `XxxRepository`，实现 `DefaultXxxRepository`；数据源 `XxxDataSource` |
| 资源命名 | 字符串 `core/resources`（`<域>_<对象>_<用途>`，见 PRD 9.7.2）；Compose 组件 `XxxScreen` / `XxxSheet` / `XxxDialog` |
| 常量 | 全部集中在 `core/common/constant/` 或各模块 `XxxDefaults`，**禁止在 UI 里写字面量阈值**（阈值一律来自 DataStore 设置） |
| 日志 | Timber，Tag 用类名；`Timber.d` 仅 Debug；Release 关闭（PRD 4.1） |

### 1.2 代码规范与静态检查基线

| 项 | 取值 | 说明 |
| --- | --- | --- |
| 代码风格 | ktlint（官方 `ktlint-gradle`）+ Android Studio 默认 Kotlin 风格 | CI 强制 |
| 最大行宽 | 120 | |
| 缩进 | 4 空格，不用 Tab | |
| 类型显式声明 | 公开 API 必须显式声明返回类型 | |
| `!!` 操作符 | **禁用**（`detekt` 规则 `UnsafeCallOnNullableType`） | 用 `?:` / `requireNotNull` |
| 可变全局状态 | **禁用** `object` 单例中的可变字段（除 Holder 模式，见 §2.4） | |
| detekt | 启用 `complexity` / `potential-bugs` / `naming` 三组规则 | M0 落地 |

### 1.3 依赖版本基线

> **⚠ 2026-09-19 更新：下表 20 个版本号已全部到真实仓库核实（脚本 `tools/m0-verify/01_maven_versions.py`），结论是「全都存在，但整体偏旧」。**
> 它们落在 **2024 年末 ~ 2025 年初**；而各仓库当前最新为 **AGP 9.4.1 / Kotlin 2.4.20 / Compose BOM 2026.09.00 / Hilt 2.60.1 / Room 2.8.5 / Vico 3.3.1**。
> 因此 M0 需要先做一次决策：**「用这套旧稳定基线起步」还是「整体起新版本」**（起新版本要连带改 §7.1 的若干写法，见 T-19 结论）。决策完再逐个锁死。
> 锁定后不得随被动升级（PRD 无「持续升级依赖」要求，稳定性优先）。
>
> 特别容易出错的组合：`ksp` 的版本号必须与 `kotlin` **精确匹配**（形如 `<kotlin>-<ksp>`）；`kotlin-compose` 插件版本必须与 `kotlin` **完全一致**。这两个写错会直接编译失败。

**`gradle/libs.versions.toml`**

```toml
# ✅ 以下版本号均已核实真实存在（tools/m0-verify/01_maven_versions.py，§9.2.1 T-14/B1）；整体偏旧，「旧稳定基线 vs 新版本」的锁定决策随 M0/T-14 一并做
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
composeBom = "2024.12.01"
hilt = "2.53.1"
room = "2.6.1"
datastore = "1.1.1"
serialization = "1.7.3"
coil = "3.0.4"
timber = "5.0.1"
coroutines = "1.9.0"
navigation = "2.8.5"
vico = "3.3.1"          # ✅ 已核实：2.0.0-beta.2 确实存在，但 v3 才是稳定线（最新 3.3.1）；见 §9.2.1 T-10
amapCombined = "10.0.700_loc6.4.5_sea9.7.2"  # 官方三合一（3dmap+location+search）；单独引 3dmap 与 search 会重复类冲突（D-17）

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version = "1.7.0" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.15.0" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version = "2.8.7" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version = "2.8.7" }
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version = "1.3.7" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-video = { group = "io.coil-kt.coil3", name = "coil-video", version.ref = "coil" }
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }
vico-compose-m3 = { group = "com.patrykandpatrick.vico", name = "compose-m3", version.ref = "vico" }
amap-combined = { group = "com.amap.api", name = "3dmap-location-search", version.ref = "amapCombined" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.1.0-1.0.29" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

**关键约束**

| 约束 | 说明 |
| --- | --- |
| Compose 编译器 | Kotlin 2.0+ 起由 `kotlin-compose` 插件提供，**版本必须与 `kotlin` 完全一致**，不再是独立的 `composeOptions.kotlinCompilerExtensionVersion` |
| 注解处理 | Room 与 Hilt 统一走 **KSP**，不用 KAPT（编译速度） |
| 高德 SDK 是**四件套**，不是一个包 | M0 已核实（§9.2.1 T-01）：`3dmap`（地图，含 `AMap`/`MapsInitializer`）／`location`（定位，含 `AMapLocationClient`）／**`search`（搜索 + 路径规划，含 `PoiSearch`/`Inputtips`/`RouteSearch`/`RouteSearchV2`）**／`navi-3dmap`（导航，选配）。**`PoiSearch`、`Inputtips`、`RouteSearch` 都不在地图包里**——项目一开始必须把 `search` 一起引上，否则 PRD 6.2.1 的搜索选点与 PRD 6.2.2 的线路规划都做不了 |
| 高德 SDK 引入方式（已查清） | 公网 Maven **只有 jar、没有 aar**（已实测 `3dmap-10.0.600.aar` 返回 404，`.jar` 正常）。两个后果必须在 M0 处理：① **jar 不带 `AndroidManifest.xml`，权限与 `com.amap.api.v2.apikey` 的 `meta-data` 必须由 App 自己声明**；② **jar 内没有任何 proguard 规则**（见 T-17），Release 混淆规则必须手写。若改用官网 aar 则反之（aar 带清单、但仍不带混淆规则） |
| 导航 SDK 的版本号编码了地图 SDK 版本 | `navi-3dmap` 的版本形如 `10.0.800_3dmap10.0.800`——**它绑定了配套的地图 SDK 版本**。若引入导航 SDK，地图 SDK 必须与它成对锁定，不能各自取最新 |
| 图表库 | **Vico 3.3.1（稳定线）**——T-10 已裁定直接用 v3，**不准备 MPAndroidChart 兜底**（PRD 4.1 的备选仅在 v3 出现不兼容时再议） |
| minSdk / targetSdk | 26 / 35（PRD 4.1） |
| 新增依赖的核实结论 | 见 §9.2.1 T-01 / T-10 / T-11 / T-14：20 个版本号全部存在但整体偏旧；**Vico 直接用 3.3.1**；**高德必须引四个坐标** |

### 1.4 构建变体与签名

**变体**：`debug` / `release`（不做 flavor，PRD 无此需求）。

| 项 | debug | release |
| --- | --- | --- |
| `applicationIdSuffix` | **无（2026-09-20 起）**：与 release 共用包名 `com.gohiking.app`，配合高德单 Key 双 SHA1 | 无 |
| 高德 Key | `AMAP_KEY_DEBUG` | `AMAP_KEY_RELEASE`（单 Key 方案下与 debug 同值） |
| 签名 | debug keystore | release keystore（`keystore.properties`） |
| minify | 关 | 开（R8），保留高德与 serialization 规则 |
| Timber | 开 | 关 |

**Key 注入（PRD 4.2 的落地方式）**

`local.properties`（已在 `.gitignore` 中）：

```properties
AMAP_KEY_DEBUG=你的debug key
AMAP_KEY_RELEASE=你的release key
```

`app/build.gradle.kts`：

```kotlin
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey = localProps.getProperty("AMAP_KEY_DEBUG").orEmpty()

android {
    defaultConfig {
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }
}
```

`AndroidManifest.xml`：

```xml
<meta-data
    android:name="com.amap.api.v2.apikey"
    android:value="${AMAP_KEY}" />
```

**仓库内只保留 `local.properties.example`。`app/libs/` 下的高德 aar 若含非公开分发条款，同样不得提交，README 说明获取方式。**

### 1.5 隐私合规启动时序（**最容易被漏掉的合规红线**）

> PRD 4.2 / 9.5 要求：**必须在用户同意隐私政策之后**才初始化高德 SDK 与定位。提前初始化 = 违规采集，是下架级问题。

```
App 启动
  │
  ├─ Application.onCreate()
  │    └─ 只做：Timber 初始化、Hilt、DataStore 读取「是否已同意隐私政策」
  │       ❌ 不初始化 AMap / AMapLocationClient
  │
  ├─ 首启 → 显示隐私政策页（必读，勾选后方可继续）
  │    └─ 用户点「同意并继续」
  │         ├─ 写 DataStore: privacy_agreed = true
  │         ├─ MapsInitializer.updatePrivacyShow(context, true, true)
  │         ├─ MapsInitializer.updatePrivacyAgree(context, true)
  │         ├─ AMapLocationClient.updatePrivacyShow(context, true, true)   // 参数含义 M0 核对官方文档
  │         ├─ AMapLocationClient.updatePrivacyAgree(context, true)
  │         └─ 之后才允许创建 MapView / AMapLocationClient
  │
  └─ 非首启且 privacy_agreed = true → 直接在 Application 内完成上述 4 个 SDK 调用
```

| 规则 | 说明 |
| --- | --- |
| 所有 SDK 的 `updatePrivacy*` 调用**收口到 Gate 类** | **每 SDK 一个 Gate 类、全仓只此两处**：`core/map/AmapPrivacyGate.kt`（MapsInitializer）与 `core/location/LocationPrivacyGate.kt`（AMapLocationClient），禁止散落到第三处 |
| 未同意前 | 地图区域显示占位图 + 「请先同意隐私政策」，**记录功能仍可用**（PRD 9.6「高德 SDK 初始化失败」同款降级） |
| 权限申请 | 隐私政策同意之后才申请定位权限（PRD 10 权限清单的「何时申请」） |
| 导出的文件 | 不得含设备标识（PRD 9.5）；`generator` 段只写 app 名、包名、版本 |

### 1.6 错误处理与 Result 约定

`core/common` 提供：

```kotlin
sealed interface GhResult<out T> {
    data class Ok<T>(val value: T) : GhResult<T>
    data class Err(val error: GhError) : GhResult<Nothing>
}

sealed class GhError(val cause: Throwable? = null) {
    data class Network(val reason: String) : GhError()
    data class Permission(val permission: String) : GhError()
    data class SensorUnavailable(val sensor: String) : GhError()
    data class ElevationUnavailable(val reason: String) : GhError()
    data class ImportError(val file: String, val entry: String?, val reason: String) : GhError()
    data class StorageFull(val requiredBytes: Long) : GhError()
    data class AmapNotInitialized(val reason: String) : GhError()
    data class Unknown(val t: Throwable) : GhError(t)
}
```

| 规则 | 说明 |
| --- | --- |
| 面向用户的错误文案 | 一律走 `strings_error.xml`，**必须包含可操作信息**（PRD `F-IO-34`：不许用「导入失败」四个字糊弄） |
| 错误 → 文案映射 | `core/common/error/ErrorTextMapper.kt`，`GhError` → `@StringRes` + 参数 |
| 禁止在 Repository 里弹 Toast / 打日志给用户看 | Repository 只返回 `GhResult`；提示由 ViewModel 发 `UiEffect` 决定 |

---

## 2. 模块与分层落地

### 2.1 模块清单与依赖方向

模块清单来自 PRD 4.6，本节补充**依赖方向**（PRD 未定义，属实现决策）。

```
                ┌─────────────────────────────────────────┐
                │                  :app                    │
                │   导航宿主、Application、DI 装配总入口    │
                └───────────────┬─────────────────────────┘
                                │ depends on
                ┌───────────────▼─────────────────────────┐
                │            :feature:*（7 个）             │
                │ home plan recording history media io set │
                └───────────────┬─────────────────────────┘
                                │ depends on
   ┌────────────────────────────▼────────────────────────────┐
   │                      :core:*（10 个）                     │
   │                                                          │
   │  model ──▶ common ──▶ resources(设计系统/字符串)          │
   │    │                                                     │
   │    ├──▶ database ──▶ datastore ──▶ data(Repository 实现)  │
   │    ├──▶ location ──▶ elevation ──▶ map                    │
   └──────────────────────────────────────────────────────────┘
```

**依赖规则（CI 用 `import` 静态检查强制）**

| 规则 | 说明 |
| --- | --- |
| `:core:*` **不得**依赖 `:feature:*` | 单向，违反即架构破坏 |
| `:feature:*` **之间不得互相依赖** | 跨 feature 通信走 `:core:data` 的 Repository 或导航参数 |
| `:core:model` **不得**依赖任何其他 core 模块 | 它是依赖图的最底层，只放纯 Kotlin 数据类 |
| `:core:common` 只依赖 `:core:model` | 工具与扩展函数，无 Android UI 依赖（`Context` 扩展除外） |
| 只有 `:core:data` 的 Impl 做跨模块编排 | 上层只依赖 Repository **接口**（接口放 `:core:data` 的 `repository/` 包，实现放 `impl/`） |
| `:core:resources` 依赖 `:core:designsystem` 的方向 | 资源（strings/color/dimen）→ 设计系统组件引用资源，单向 |

### 2.2 各 core 模块公开接口一览

| 模块 | 公开接口（供 feature 使用） | 内部实现 |
| --- | --- | --- |
| `:core:model` | 纯数据类：`Trip` / `TrackPoint` / `Marker` / `MarkerType` / `PlannedRoute` / `PlannedLeg` / `Waypoint` / `MediaItem` / `TripStats` / `AlertSettings` / `AppSettings` | 无 |
| `:core:common` | `GhResult` / `GhError` / `ErrorTextMapper` / `DispatchersProvider` / 单位换算 `UnitFormatter` / 时间格式化 `TimeFormatter` / `GeometryUtils` | 无 Android 依赖 |
| `:core:resources` | 所有 `strings_*.xml` / `plurals.xml` / `arrays.xml`（中英双语） | 无代码 |
| `:core:designsystem` | `GhTheme` / `GhColors` / `GhTypography` / 通用组件：`GhTopAppBar` `GhButton` `GhCard` `GhListItem` `GhSwitchItem` `GhSliderWithInput` `GhEmptyState` `GhToastHost` | 无 |
| `:core:database` | `GhDatabase` / **7 个 DAO**（§3.2：Trip / TrackPoint / Marker / PlannedRoute / Media / RecordingState / ElevationCache；MediaDao 同管 media_ref 与 media_index 两表）/ `Converters` | Room 注解代码 |
| `:core:datastore` | `SettingsRepository`（接口）→ `Flow<AppSettings>` + 各 setter | Preferences DataStore |
| `:core:location` | `LocationProvider` / `AltitudeFuser` / `StepCounterProvider` / `CoordinateConverter` / `TrackSampler` | 高德定位 + Fused 备源 + 传感器 |
| `:core:elevation` | `ElevationRepository`：`suspend fun query(points: List<LatLng>): GhResult<List<Double?>>` | 三级降级（见 §4.11） |
| `:core:map` | `GhMapView`（Composable）/ `MapController` / `AmapPrivacyGate` / `LayerRenderer`（轨迹、线路、标记、聚合） | 高德地图 SDK 封装 |
| `:core:data` | `TripRepository` / `PlannedRouteRepository` / `MediaRepository` / `ExportEngine` / `ImportEngine` / `RecordingSession`（单例 Holder） | 所有 Impl |

### 2.3 Hilt 依赖注入图

```kotlin
// :core:common
@Module @InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun default(): CoroutineDispatcher = Dispatchers.Default
    @Provides @ApplicationScope fun appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

| 绑定 | Scope | 说明 |
| --- | --- | --- |
| `GhDatabase` | `SingletonComponent` | `Room.databaseBuilder(...).addMigrations(*Migrations.ALL)` |
| 各 DAO | `SingletonComponent` | 从 `GhDatabase` 提取 |
| `SettingsRepository` | `SingletonComponent` | DataStore 单例 |
| `LocationProvider` | `SingletonComponent` | 高德为主、Fused 为备（运行时按 §6.2 切换） |
| `RecordingSession` | **`SingletonComponent`（关键）** | 记录过程中的**唯一可变状态源**，Service 与 ViewModel 共享（见 §6.1） |
| `ElevationRepository` | `SingletonComponent` | 内含 LruCache 与 DB 缓存 |
| `ExportEngine` / `ImportEngine` | `SingletonComponent` | 无状态，线程安全 |
| `Clock` / `IdGenerator` | `SingletonComponent` | 便于测试注入假实现 |
| 各 `XxxRepository` | 无 Scope（每次新建） | Impl 无状态，只持 DAO + 数据源 |

> **测试性要求**：所有 `Clock`、`IdGenerator`、`LocationProvider`、`StepCounterProvider`、`ElevationRepository`、`AltitudeFuser`、`AlertEngine` 必须可被替身（fake）替换，否则 §8.2 的单元测试无法写（AltitudeFuser / AlertEngine 有逐场状态，§8.8 必须有 Fake）。

### 2.4 线程与并发模型

| 场景 | 线程/调度器 | 说明 |
| --- | --- | --- |
| UI 渲染 | Main | Compose |
| Room 读写 | `Dispatchers.IO` | 全部挂起函数，无阻塞主线程 |
| 定位回调 | 高德回调线程 → `channelFlow` | 立即转成 Flow，不在回调里做重活 |
| 传感器回调 | `SensorManager` 回调线程 → `Flow` | 气压计、计步、加速度计 |
| 算法计算（融合、抽稀、聚类） | `Dispatchers.Default` | 数据量大，禁止在 Main |
| 导出/导入 | `Dispatchers.IO` + `LimitedParallelism(1)` | **串行**，避免并发写同一文件 |
| 地图覆盖物更新 | Main（高德 SDK 要求） | 数据在 Default 算好后 `withContext(Main)` 提交，**每帧最多一次批量提交** |

**单一可变状态源规则**：记录过程中的实时状态只有一个持有者 —— `RecordingSession`（`@Singleton`，内部 `MutableStateFlow<RecordingState>`）。前台服务、记录页 ViewModel、通知栏都**只读**它，只能通过它暴露的方法改。禁止各处各自维护一份计时器/距离。

---

## 3. 数据层设计

### 3.1 Room 数据库版本与迁移策略

| 项 | 值 |
| --- | --- |
| 数据库名 | `gohiking.db` |
| 版本 | **1**（v1.0 首发，不存在迁移历史） |
| 导出 schema | 开（`room.schemaLocation`），schema JSON 提交到仓库 `:core:database/schemas/` |
| 迁移策略 | **禁止 `fallbackToDestructiveMigration`**（PRD 9.5「数据属于用户」，销毁迁移不可接受） |
| 首次迁移时点 | v1.1 起，每加一次表/字段就 +1 版本并写一个 `Migration` |

```kotlin
val MIGRATIONS: Array<Migration> = arrayOf(
    // v1.1 时在此追加，例如：
    // object : Migration(1, 2) {
    //     override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE trip ADD COLUMN weather TEXT") }
    // }
)

Room.databaseBuilder(context, GhDatabase::class.java, "gohiking.db")
    .addMigrations(*MIGRATIONS)
    .build()
```

**迁移自测要求**：每次加迁移，必须同时加一个 `MigrationTestHelper` 测试（`MigrationTest_1_2`），用旧版本 schema JSON 建库 → 迁移 → 断言数据未丢失。

#### 3.1.1 对 PRD 7.1 的补充：新增三张表（＋设计补充）

PRD 7.1 定义了 8 张表，但有三处**没有表承载**，本节补齐（已登记 §9.3）：

**① `recording_state`（单行状态快照）** —— 承载 `F-REC-09`（崩溃恢复）与 PRD 9.6「每 10 秒持久化一次状态」

```
recording_state（恒为 1 行，id 固定 = 1）
├── id                INTEGER PK (恒为 1)
├── tripId            TEXT       未完成的记录 id
├── status            TEXT       RECORDING / PAUSED
├── startedAt         INTEGER
├── lastPausedAt      INTEGER    可空
├── accumulatedPausedMs INTEGER
├── currentSegment    INTEGER
├── distanceM         REAL       已累计距离（用于恢复后继续累加）
├── ascentAccumM      REAL       爬升累加器当前值
├── descentAccumM     REAL       下降累加器当前值
├── lastDistanceMarkM REAL       距离提醒上次触发的累计值
├── lastAscentMarkM   REAL       海拔（爬升）提醒上次触发的累计值
├── lastDescentMarkM  REAL       海拔（下降）提醒上次触发的累计值
├── stepAtStart       INTEGER    计步传感器基准值（-1 = 不可用）
├── stepSourceUsed    TEXT
├── hasBarometer      INTEGER
├── altitudeRefM      REAL       海拔融合基准 altRef
├── pressureRefHpa    REAL       气压计基准
├── statAnchorM       REAL       统计累加器锚点海拔（3m/10m 阈值法；恢复时回灌，否则恢复后统计有偏差）
├── markAnchorM       REAL       打点累加器锚点海拔（100m 阈值法；恢复时回灌）
├── movingSecBySegmentJson TEXT   逐段运动秒数 JSON（key=segmentIndex；暂停时长无法从轨迹点反推，必须逐段落库，见 §4.12）
└── updatedAt         INTEGER
```

> 恢复流程：App 启动时查 `recording_state`，存在则提示「检测到未结束的记录，是否恢复？」→ 恢复 = `RecordingSession.rebind(tripId)` 并新建 segment（单例不重建，见 §6.6）（**绝不把恢复点与中断点连线**，否则出现假直线，违反 PRD 9.6「不产生跳变直线」）。

**② `media_index`（媒体库扫描缓存）** —— 承载 `F-MEDIA-09`（扫描结果缓存，避免每次全量扫描）

PRD 7.1 的 `media_ref` 是「**已关联到某条记录**的媒体引用」，而 `F-MEDIA-01/09` 需要的是「**全库扫描索引**」，两者不是一回事。

```
media_index（全库媒体索引，独立于 trip）
├── mediaStoreId   INTEGER     MediaStore 的 _ID
├── mediaType      TEXT        IMAGE / VIDEO
├── dateModifiedMs INTEGER     MediaStore 的 DATE_MODIFIED（增量扫描指纹，见下方「扫描增量策略」）
├── uri            TEXT
├── dateTakenMs    INTEGER
├── latWgs84       REAL        可空（原始 WGS-84）
├── lngWgs84       REAL        可空
├── latGcj02       REAL        可空（转换后，供地图直接用）
├── lngGcj02       REAL        可空
├── isApproximate  INTEGER     是否模糊位置（未授 ACCESS_MEDIA_LOCATION）
├── durationMs     INTEGER     视频时长
├── sizeBytes      INTEGER
├── indexedAt      INTEGER
```
**主键：复合主键 `(mediaStoreId, mediaType)`** —— MediaStore 的 `_ID` 在 `images` 与 `video` 两张表里各自唯一、可能重号，必须带上 `mediaType` 才唯一。
**索引**：`media_index(dateTakenMs)`（时间筛选/缩略图条用）。`latGcj02, lngGcj02` 索引**暂不建**——§3.2 现有查询没有按坐标过滤的消费方，白付写入与存储成本；照片地图若做「视野范围查询」（v1.1+）再加回。

> **为什么必须是主键，不能只是唯一索引**：Room 的 `@Upsert` 依据**主键**判定「插入还是更新」。若把 `(mediaStoreId, mediaType)` 只声明为唯一索引，`@Upsert` 无法识别冲突，写入会抛约束异常。这是本项目自查发现的内部矛盾（见 §9.5-A2）。

**扫描增量策略**：以 `MediaStore` 的 `DATE_MODIFIED` 与已索引的 `(mediaStoreId, dateModifiedMs)` 比对，只处理新增/变更项；提供「重建索引」入口。

**WGS-84 与 GCJ-02 都存**：转换有成本，且地图展示要求 GCJ-02、导出要求按用户选择（PRD 4.3、`F-IO-08`）。两列都存，避免每次渲染都算。

**③ `elevation_cache`（高程查询缓存）** —— §4.10 的高程三级降级要求「结果落本地缓存」，且该节代码引用了 `ElevationCacheDao`，但 PRD 7.1 与本文档 §3 此前都没有对应表（自查发现的缺失，见 §9.5-A3）

```
elevation_cache
├── latKey     REAL       纬度取 5 位小数（约 1m 精度，作为缓存键）
├── lngKey     REAL       经度取 5 位小数
├── altitudeM  REAL       高程（米）
├── source     TEXT       REMOTE（第三方服务）/ DEM_TILE（本地 DEM 瓦片）
└── fetchedAt  INTEGER
```
**主键**：复合 `(latKey, lngKey)`。

> DEM 数据不会变，因此缓存**长期有效**，不设过期；仅在用户主动「清除高程缓存」或更换 DEM 数据源时清空。缓存键用 5 位小数（约 1m）而非原始精度，是为了让相近查询命中同一缓存项——登山线路上的相邻采样点常常落在同一格子。

### 3.2 DAO 接口定义

PRD 7.1 只给到「表 + 字段 + 索引」，本节给出方法签名（可直接照写）。

```kotlin
@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Update suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE status = 'FINISHED' ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip ORDER BY startTime DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<TripEntity>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun byId(id: String): TripEntity?

    @Query("SELECT * FROM trip WHERE id = :id")
    fun observeById(id: String): Flow<TripEntity?>

    @Query("SELECT COUNT(*) AS c, COALESCE(SUM(distanceM),0) AS d, COALESCE(SUM(durationSec),0) AS t, COALESCE(SUM(totalAscentM),0) AS a FROM trip WHERE status = 'FINISHED'")
    fun observeSummary(): Flow<TripSummaryRow>

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trip WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT id FROM trip WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT id FROM trip WHERE name = :name AND startTime = :startTime")
    suspend fun findSuspectedDuplicate(name: String, startTime: Long): List<String>
}
```

```kotlin
@Dao
interface TrackPointDao {
    /** 批量插入：必须在事务中调用（见 §3.4） */
    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY segmentIndex, seq")
    suspend fun allOf(tripId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY segmentIndex, seq")
    fun observeOf(tripId: String): Flow<List<TrackPointEntity>>

    /** 曲线图用：抽稀后交给图表，避免把 1 万个点塞进 Compose */
    @Query("SELECT timestamp, altitude, distanceM FROM track_point WHERE tripId = :tripId AND quality = 0 ORDER BY segmentIndex, seq")
    suspend fun chartPoints(tripId: String): List<ChartPointRow>

    @Query("SELECT COUNT(*) FROM track_point WHERE tripId = :tripId")
    suspend fun countOf(tripId: String): Int

    @Query("DELETE FROM track_point WHERE tripId = :tripId")
    suspend fun deleteOf(tripId: String)
}
```

> **注意**：`distanceM`（该点处的累计距离）是 PRD 7.1 的 `track_point` **没有的字段**，但 `F-HIS-23/25`（海拔曲线横轴用距离、分段表按公里）需要它。若每点重复计算累计距离会 O(n) 重算。**＋设计补充**：`track_point` 增列 `distanceM REAL`（该点为止的累计距离，写入时即算好）。
>
> **track_point 索引清单**（§8.3 `IndexQuery_UsesIndex` EXPLAIN 断言的依据；PRD 7.1 只列了 `(tripId, segmentIndex, seq)` 与 `(tripId, timestamp)`，此处补齐并新增一条）：`track_point(tripId, segmentIndex, seq)`（主查询）、`track_point(tripId, quality)`（`chartPoints` 的过滤列 `quality` 必须在索引内，否则该查询全表扫）。

```kotlin
@Dao
interface MarkerDao {
    @Insert suspend fun insert(marker: MarkerEntity)
    @Insert suspend fun insertAll(markers: List<MarkerEntity>)
    @Query("SELECT * FROM marker WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun allOf(tripId: String): List<MarkerEntity>
    @Query("SELECT * FROM marker WHERE tripId = :tripId AND type = :type ORDER BY sequence DESC LIMIT 1")
    suspend fun lastOfType(tripId: String, type: String): MarkerEntity?
    @Update suspend fun update(marker: MarkerEntity)
    @Query("DELETE FROM marker WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM marker WHERE tripId = :tripId") suspend fun deleteOf(tripId: String)
}
```

```kotlin
@Dao
interface PlannedRouteDao {
    @Insert suspend fun insert(route: PlannedRouteEntity)
    @Transaction
    @Query("SELECT * FROM planned_route ORDER BY createdAt DESC")
    fun observeAllWithLegs(): Flow<List<PlannedRouteWithLegs>>

    @Transaction
    @Query("SELECT * FROM planned_route WHERE id = :id")
    suspend fun withLegs(id: String): PlannedRouteWithLegs?

    @Update suspend fun update(route: PlannedRouteEntity)
    @Query("DELETE FROM planned_route WHERE id = :id") suspend fun deleteById(id: String)

    /** 「原路返回」用：把去程折线反向 */
    @Query("SELECT polylineJson FROM planned_leg WHERE id = :legId")
    suspend fun polylineOf(legId: String): String?
}
```

```kotlin
@Dao
interface MediaDao {
    // media_ref
    @Insert suspend fun insertRef(ref: MediaRefEntity)
    @Query("SELECT * FROM media_ref WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun refsOf(tripId: String): List<MediaRefEntity>
    @Query("DELETE FROM media_ref WHERE tripId = :tripId") suspend fun deleteRefsOf(tripId: String)

    // media_index
    /** 增量写入：由复合主键 (mediaStoreId, mediaType) 判定插入还是更新（见 §3.1.1） */
    @Upsert suspend fun upsertIndex(items: List<MediaIndexEntity>)
    @Query("SELECT * FROM media_index WHERE latGcj02 IS NOT NULL")
    suspend fun allLocated(): List<MediaIndexEntity>
    @Query("SELECT * FROM media_index WHERE dateTakenMs BETWEEN :from AND :to")
    suspend fun inTimeRange(from: Long, to: Long): List<MediaIndexEntity>
    @Query("SELECT mediaStoreId, dateModifiedMs FROM media_index")
    suspend fun indexFingerprint(): List<MediaFingerprintRow>
    @Query("DELETE FROM media_index") suspend fun clearIndex()
    @Query("SELECT COUNT(*) FROM media_index WHERE dateTakenMs BETWEEN :from AND :to")
    fun observeCountInRange(from: Long, to: Long): Flow<Int>
}
```

```kotlin
@Dao
interface RecordingStateDao {
    @Query("SELECT * FROM recording_state WHERE id = 1")
    suspend fun get(): RecordingStateEntity?

    @Upsert suspend fun save(state: RecordingStateEntity)

    @Query("DELETE FROM recording_state") suspend fun clear()
}
```

```kotlin
@Dao
interface ElevationCacheDao {
    @Query("SELECT latKey, lngKey, altitudeM FROM elevation_cache WHERE latKey IN (:latKeys) AND lngKey IN (:lngKeys)")
    suspend fun query(latKeys: List<Double>, lngKeys: List<Double>): List<ElevationCacheEntity>

    @Upsert suspend fun upsert(items: List<ElevationCacheEntity>)

    @Query("DELETE FROM elevation_cache") suspend fun clear()

    @Query("SELECT COUNT(*) FROM elevation_cache") suspend fun count(): Int
}
```

> **Batch 查询的注意点**：上面这种 `IN (..) AND IN (..)` 的写法在批量点较多时会退化成笛卡尔积、且受 SQLite 变量数上限（默认 999）限制。若实测性能不佳，改为按经度分桶后逐桶查询，或直接用 `(latKey, lngKey)` 组合键的 `IN` 元组写法。此处**不下结论，以实现时的基准测试为准**（见 §8.3）。**正确性硬规则**：`IN(latKeys) AND IN(lngKeys)` 会返回**跨点错配**的行（纬度属于 A 点、经度属于 B 点）——Repository 层必须把结果按 `(latKey, lngKey)` **精确回配**到请求点（用请求键集合过滤），并配单测「三点同批查询不串值」。

### 3.3 实体 ↔ 领域模型映射

| 层 | 类型 | 位置 | 说明 |
| --- | --- | --- | --- |
| 持久层 | `XxxEntity` | `:core:database/entity/` | 与表 1:1，字段名 = 列名 |
| 领域层 | `Trip` / `TrackPoint` / ... | `:core:model/` | 无 Room 注解，枚举用 Kotlin `enum class` |
| 导出层 | `XxxJson`（`@Serializable`） | `:core:data/io/json/` | 与 PRD 7.3 的 JSON 结构 1:1 |

**枚举必须双向映射且对未知值降级**（PRD `F-IO-55`、7.5）：

```kotlin
enum class MarkerType(val wire: String) {
    SUMMIT("SUMMIT"),
    ALERT_DISTANCE("ALERT_DISTANCE"),
    ALERT_ASCENT("ALERT_ASCENT"),
    ALERT_DESCENT("ALERT_DESCENT"),
    MANUAL("MANUAL"),
    UNKNOWN("UNKNOWN");   // 兜底：解析到不认识的值时用它，并保留原始字符串

    companion object {
        fun fromWire(raw: String?): MarkerType =
            entries.firstOrNull { it.wire == raw } ?: UNKNOWN
    }
}
```

> `UNKNOWN` 的**原始字符串必须保留**在 `MarkerEntity.extraJson` 里（PRD 7.5「降级为未知标记展示，**保留原始值不丢弃**」），否则再导出时会丢信息。

**难度枚举**：`Difficulty { EASY, MODERATE, HARD, CHALLENGING }`，wire 值同名，**渲染时才本地化**（PRD 6.2.3 / 9.7.4）。

### 3.4 批量写入与事务边界

| 操作 | 事务边界 | 说明 |
| --- | --- | --- |
| 记录中写轨迹点 | 每批 10 点 / 每 5 秒（先到者触发）一次事务（PRD 6.3.7）。**点随到随入库**；此时 `trip` 行尚未创建 → `track_point` 对 `trip` **不建外键**（见下） | `@Transaction suspend fun insertBatch(points)` |
| 停止记录保存 | **一个大事务**：insert trip → insert markers → delete recording_state。**track_points 不在此重插**——点在记录期已分批落库（上一行）；若保存时全量重插，同一批点会写两遍（主键冲突，或无主键时距离/统计翻倍） | 任一步失败全部回滚，不留半条记录 |
| 单条记录导入 | **一条记录一个事务**（PRD `F-IO-28`「单条失败不影响其他条」+ `F-IO-65`） | 逐条提交，前一条成功不因后一条失败而回滚 |
| 批量导入 | 逐条循环调用「单条导入」，外层不套事务（否则一条失败全废，违背 `F-IO-28`） | 进度按条上报 |
| 删除记录 | 一个事务：marker → media_ref → track_point → trip | 外键级联也可，但显式删除更可控 |

**外键策略（记录期落库的前提，2026-09-19 审阅修正）**：`track_point` / `marker` 对 `trip` **不建外键约束**——记录期分批落库时 `trip` 行还不存在（停止时才创建），建 FK 会让每批写入即违例。表生命周期由显式事务管理：保存 = 大事务补齐 `trip` 行与 `marker`；删除 = 按 marker → media_ref → track_point → trip 顺序显式删除（见上表）。

```kotlin
@Transaction
suspend fun saveFinishedTrip(trip: TripEntity, markers: List<MarkerEntity>) {
    tripDao.insert(trip)
    markerDao.insertAll(markers)
    recordingStateDao.clear()
    // 注意：这里【不再重插 track_points】——点在记录期已按 10 点/5 秒分批落库，
    // 保存时全量重插会把同一批点写两遍（审阅 H-1，2026-09-19 修正）。
}
```

**分批写入性能要求**：点在记录期持续落库（10 点/5 秒，均摊写延迟可忽略）；基准测试口径为「记录期 10800 点（3 小时）分批写入累计耗时 < 1.5 秒」。§8.3 有对应基准测试。

**写入节流**：`recording_state` 每 **10 秒** UPSERT 一次（PRD 9.6），轨迹点每批 10 点/5 秒，两者独立。

### 3.5 DataStore 设置项键表

PRD 6.7 给了设置项与默认值，本节给键名与类型（供实现直接照写）。

| 键 | 类型 | 默认 | 对应 PRD 项 |
| --- | --- | --- | --- |
| `app_language` | String（`system` / `zh-CN` / `en`） | `system` | `F-I18N-11` |
| `body_weight_kg` | Int | `65` | `F-SET-06`（卡路里估算用，范围 30–200，见 §4.12） |
| `alert_master_enabled` | Boolean | `true` | `F-ALERT-01` |
| `alert_voice_enabled` | Boolean | `true` | `F-ALERT-02` |
| `alert_distance_enabled` | Boolean | `true` | `F-ALERT-10` |
| `alert_distance_interval_m` | Int | `100` | `F-ALERT-11` |
| `alert_altitude_enabled` | Boolean | `true` | `F-ALERT-20` |
| `alert_altitude_interval_m` | Int | `100` | `F-ALERT-21` |
| `alert_vibrate_enabled` | Boolean | `true` | 6.7 打点震动反馈 |
| `record_location_mode` | String（`high` / `power_saving`） | `high` | 6.3.7 采样频率 |
| `record_auto_pause` | Boolean | `false` | `F-REC-10` |
| `record_keep_screen_on` | Boolean | `true` | `F-REC-18` |
| `record_background_enabled` | Boolean | `true` | 6.7 后台记录 |
| `unit_distance` | String（`km` / `mi`） | `km` | 6.7 单位 |
| `unit_altitude` | String（`m` / `ft`） | `m` | 6.7 单位 |
| `unit_pace_display` | String（`pace` / `speed`） | `pace` | 6.7 单位 |
| `map_type` | String（`normal` / `satellite`） | `normal` | `F-MAP-04` |
| `map_track_width` | String（`thin` / `medium` / `thick`） | `medium` | 6.7 地图 |
| `map_show_media_cluster` | Boolean | `true` | 6.7 地图 |
| `io_export_crs` | String（`GCJ-02` / `WGS-84`） | `GCJ-02` | 6.7 数据 |
| `io_extra_formats` | Set\<String\>（`GPX`） | 空 | `F-IO-04` |
| `io_backup_include_media` | Boolean | `false` | `F-IO-10` |
| `io_backup_include_checksum` | Boolean | `true` | 6.7 数据 |
| `io_import_conflict_policy` | String（`skip`/`overwrite`/`duplicate`/`ask`） | `skip` | `F-IO-42` |
| `privacy_agreed` | Boolean | `false` | PRD 9.5 |
| `barometer_hint_shown` | Boolean | `false` | `F-REC-62`（只弹一次） |
| `step_algorithm_warn_accepted` | Boolean | `false` | `F-REC-71` |
| `manual_altitude_calibration_m` | Double? | `null` | `F-REC-64` |

**阈值合法范围校验**（PRD `F-ALERT-12/22`、`F-SET-05`）集中在 `AppSettings.validate()`，UI 与 DataStore 写入前都过一遍：

| 项 | 范围 | 步进 | 无气压计时 |
| --- | --- | --- | --- |
| 距离阈值 | 50–5000 m | 50 | 不变 |
| 海拔阈值 | 10–1000 m | 10 | **下限提到 30 m**（`F-REC-63`） |

---

## 4. 关键算法规格

> 本章是本文档**价值最高的部分**。PRD 给的是算法思路，本章给的是**可直接实现、参数明确、可测**的规格。
> 所有参数集中定义在 `core/common/constant/AlgorithmDefaults.kt`，测试直接引用，避免文档与代码漂移。

### 4.1 坐标系转换（`core/location/crs/CoordinateConverter.kt`）

PRD 4.3 的依据；本节给完整规格。

```kotlin
object CoordinateConverter {
    private const val A = 6378245.0                  // 克拉索夫斯基椭球长半轴（米）
    private const val EE = 0.00669342162296594323    // 第一偏心率平方
    private const val PI = kotlin.math.PI
    private const val MAX_ITER = 10
    private const val EPS_DEG = 1e-9                 // 约 0.1 毫米

    /** 中国大陆粗略范围外不做偏移（PRD 4.3 第 5 条） */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        val dLatRaw = transformLat(lng - 105.0, lat - 35.0)
        val dLngRaw = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLat = (dLatRaw * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLng = (dLngRaw * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    /** GCJ-02 → WGS-84：解析不可逆，用迭代逼近。
     *  收敛因子约 1e-3，**预期** 3~4 次迭代即达 EPS。⚠ 这是预期而非实测结果，
     *  由单测 gcjToWgs_迭代收敛 断言「迭代次数 < 5」来落地（见 §4.11）。 */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wLat = lat; var wLng = lng
        repeat(MAX_ITER) {
            val (gLat, gLng) = wgs84ToGcj02(wLat, wLng)
            val dLat = gLat - lat; val dLng = gLng - lng
            if (abs(dLat) < EPS_DEG && abs(dLng) < EPS_DEG) return wLat to wLng
            wLat -= dLat; wLng -= dLng
        }
        return wLat to wLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 20.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
```

**调用点清单（谁在哪个方向转，写错就是几百米偏移）**

| 数据流 | 方向 | 位置 |
| --- | --- | --- |
| 高德定位 → 内部存储 | **不转**（已是 GCJ-02） | `AmapLocationSource` |
| `FusedLocationProviderClient` → 内部存储 | **WGS-84 → GCJ-02** ⚠ **最容易漏的一处** | `FusedLocationSource` |
| 照片 EXIF / MediaStore → 地图显示 | **WGS-84 → GCJ-02**（PRD `F-MEDIA-03`） | `MediaRepository`（写入 `media_index` 时一次转好） |
| GPX 导入 → 内部存储 | **WGS-84 → GCJ-02**（PRD `F-IO-32`） | `ImportEngine` |
| 导出（`io_export_crs = GCJ-02`） | 不转 | `ExportEngine` |
| 导出（`io_export_crs = WGS-84`） | **GCJ-02 → WGS-84** | `ExportEngine` |
| **GPX 导出** | **恒 GCJ-02 → WGS-84**，**不受 `io_export_crs` 影响**（GPX 标准要求 WGS-84；沿用 GCJ-02 会让文件在 Garmin/Strava 上静默偏移几百米） | `ExportEngine` |
| 照片与轨迹的距离比较（`F-MEDIA-41`） | 两边**必须都是 GCJ-02**：用 `media_index.latGcj02 / lngGcj02`，**绝不能用 `latWgs84`** | `MediaRepository` |
| DEM 高程查询 | GCJ-02 → WGS-84（DEM 是 WGS-84） | `ElevationRepository` |

### 4.2 距离与速度计算

```kotlin
object GeoMath {
    private const val EARTH_R = 6371008.8   // WGS-84 平均半径（米）

    /** Haversine。输入必须同坐标系 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1.toRadians(); val p2 = lat2.toRadians()
        val dp = (lat2 - lat1).toRadians(); val dl = (lng2 - lng1).toRadians()
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }
}
```

| 说明 | 内容 |
| --- | --- |
| 为什么用 Haversine 而不是 `Location.distanceTo` | 无需构造 `Location` 对象，纯函数易测；精度足够（球面近似在 10km 内误差 < 0.1%） |
| GCJ-02 下算距离为什么不影响 | GCJ-02 是对 WGS-84 的**低频平滑非线性偏移**，相邻两点偏移量近似相等，**差分距离**受影响很小，可接受。**已实测（§9.2.1 B4）**：1–5 km 段相对误差中位 **0.062%**、P95 0.31%、**最大 0.53%**；相邻点 100–500 m 段最大 0.50%；跨城 ~300 km 段最大 0.20%；绝对误差 ≤ **9.8 m / 5 km**。⚠ 原写「< 0.5%」**不成立**，相关断言与单测容差一律按 **≤ 1%** 设计 |
| **绝对禁止** | 拿 GCJ-02 的点与 WGS-84 的点做距离/方位计算 —— 会得到几百米的假距离 |
| 速度 | `speedMps = distanceMeters(prev, cur) / ((cur.ts - prev.ts) / 1000.0)`；优先用定位 SDK 返回的 `speed`，为 0 或缺失时用差分值 |

### 4.3 海拔融合（`core/location/altitude/AltitudeFuser.kt`）

依据 PRD 4.4.1 / 4.4.2。参数：

| 参数 | 有气压计 | 无气压计 | 说明 |
| --- | --- | --- | --- |
| `altRef` 标定窗口 | 开始后前 **10 秒** | 同 | 取该窗口 GPS 海拔**中位数** |
| 气压计采样 | `SENSOR_DELAY_NORMAL`，聚合到 1Hz 取均值 | — | |
| 中值滤波窗口 | **9** 点 | **9** 点 | 抑制单点跳变 |
| 垂直精度门控 | — | `getVerticalAccuracyMeters() > 20m` 的点**不参与海拔累计** | PRD 4.4.2 防线② |
| 持续确认时长 | — | 海拔变化需**连续稳定 10 秒**才确认 | PRD 4.4.2 防线③ |
| 漂移修正周期 | **60 秒** | **60 秒** | 用 GPS 海拔中位数修正 `altRef` |
| 漂移修正系数 | `k = 0.1`（一阶低通） | `k = 0.15` | `altRef += k * (gpsMedian - currentEstimate)` |
| MSL 海拔 | API 34+ 优先 `getMslAltitudeMeters()` | 同 | PRD 4.4.2 防线⑤ |
| 输出标记 | `BAROMETER_FUSED` | `GPS_ONLY` | 若用户手动校准过 → `MANUAL_CALIBRATED` |

```kotlin
class AltitudeFuser(private val hasBarometer: Boolean, private val clock: Clock) {
    private val medianBuffer = ArrayDeque<Double>(MEDIAN_WINDOW)
    private var altRef: Double? = null
    private var pressureRefHpa: Double? = null
    private var baroAltRef: Double? = null
    private var lastDriftFixAt = 0L
    private var manualOffset = 0.0

    fun onGpsFix(gpsAltM: Double?, verticalAccuracyM: Double?, mslAltM: Double? = null) { /* 标定 / 漂移修正 / GPS_ONLY 主数据源 */ }
    fun onPressure(hPa: Double) { /* 气压高度 = getAltitude(SEA_LEVEL_STANDARD_ATMOSPHERE, hPa) */ }

    /** 返回滤波后的海拔（米）；不可用时返回 null，UI 显示「—」（PRD 6.1 降级规则） */
    fun current(): Double?
}
```

**手动校准（`F-REC-64`）**：用户输入已知海拔 → `manualOffset = input - current()` → 后续输出加 `manualOffset` → `altitudeSource` 变 `MANUAL_CALIBRATED`。**校准必须在记录中或记录后都可用**，且写入记录后导出要体现 `MANUAL_CALIBRATED`。

#### 4.3.1 爬升/下降统计与海拔打点是**两个独立累加器**

> 这是最容易被实现错的一点。PRD 4.4.4 说统计阈值是 **3m（有气压计）/ 10m（无气压计）**；PRD `F-ALERT-21` 说打点阈值默认 **100m**。**不是同一个阈值，必须两点同时维护。**

```kotlin
/** 阈值法累加器：只有相对上一次确认点的变化超过阈值才计入，并重置锚点 */
class ThresholdAccumulator(private val thresholdM: Double) {
    private var anchor: Double? = null
    var ascent = 0.0; private set
    var descent = 0.0; private set

    /** @return 是否发生了计入（用于触发打点判断） */
    fun accept(altitudeM: Double): Boolean {
        val a = anchor ?: run { anchor = altitudeM; return false }
        val delta = altitudeM - a
        return when {
            delta >= thresholdM -> { ascent += delta; anchor = altitudeM; true }
            delta <= -thresholdM -> { descent += -delta; anchor = altitudeM; true }
            else -> false
        }
    }
}
```

| 累加器 | 阈值 | 用途 | 写入哪里 |
| --- | --- | --- | --- |
| **统计累加器** | 3m / 10m | `trip.totalAscentM` / `totalDescentM`（PRD `F-HIS-22`） | trip 表统计字段 |
| **打点累加器** | 用户配置（默认 100m，无气压计下限 30m） | 触发 `ALERT_ASCENT` / `ALERT_DESCENT` 标记 | marker 表 + `recording_state` 基准 |

两个累加器的 `anchor` **都必须在暂停/继续时保留**（PRD `F-ALERT-16` 同款要求），但恢复后不把中断段的位移计入。

### 4.4 计步四级降级（`core/location/step/`）

依据 PRD 4.5.3。工厂实现：

```kotlin
interface StepSource { val steps: Flow<Int>; val wire: String }

object StepSourceFactory {
    fun create(ctx: Context, sensorManager: SensorManager, hasPermission: Boolean): StepSource = when {
        !hasPermission -> UnavailableStepSource
        sensorManager.getDefaultSensor(TYPE_STEP_COUNTER) != null -> SensorCounterStepSource(...)   // ①
        sensorManager.getDefaultSensor(TYPE_STEP_DETECTOR) != null -> SensorDetectorStepSource(...) // ②
        sensorManager.getDefaultSensor(TYPE_ACCELEROMETER) != null -> AccelAlgorithmStepSource(...) // ③
        else -> UnavailableStepSource                                                               // ④
    }
}
```

| 级 | 实现要点 | wire 值 |
| --- | --- | --- |
| ① `TYPE_STEP_COUNTER` | 记录开始时读基准 `stepAtStart`；实时步数 = 当前 − 基准；**暂停期间不计步**（暂停时更新基准） | `SENSOR_COUNTER` |
| ② `TYPE_STEP_DETECTOR` | 每事件 +1，累加 | `SENSOR_DETECTOR` |
| ③ 加速度计自研 | 50Hz 采样 → 合加速度 → 低通去重力 → **带通 0.5–3 Hz** → 峰值检测 + 自适应阈值 + **最小步间隔 250ms**；**预热 25 步**内不计步（区间 20–30 中取定单一值，与 §4.11 参数表、测试断言对齐） | `ACCEL_ALGORITHM` |
| ④ 不可用 | `steps = -1`，UI 显示「—」，允许手动补录 | `UNAVAILABLE` / `MANUAL` |

**三级实现的滤波系数**（PRD 未给，本节定）：

| 项 | 值 | 说明 |
| --- | --- | --- |
| 低通（去重力） | 一阶 IIR，`α = 0.9` | `gravity = α * gravity + (1-α) * sample` |
| ⚠ 该组系数的性质 | **自创参数，无真机数据支撑** | PRD 4.5.4 只给了「低通去重力 → 带通 0.5–3Hz → 峰值检测 + 最小步间隔 250ms」的思路，**滤波器阶数与自适应阈值系数是本文档补的**。必须用真机步行数据（含平地/上坡/下坡/手持晃动）核定后才能固定；核定前这些值只当**起点**，不是结论（见 §9.5-B6） |
| 带通 | 0.5–3 Hz 二阶 Butterworth（双二次实现） | 对应 30–180 步/分钟 |
| 自适应阈值 | 预热期峰值的 **0.6 倍**，之后连续 50 步滑动更新 | |
| 最小步间隔 | 250 ms | 防抖 |

**计数器回退检测（`F-REC-72`）**：`current < lastRaw` → 判定设备重启 → 标记该段 `stepSource` 为 `UNAVAILABLE`，UI 提示，不再累加。

**精度提示（PRD 4.5.5）**：记录时长 > 6 小时时，详情页标注「设备长时间未重启，步数精度可能下降」。

### 4.5 轨迹抽稀（Douglas-Peucker）

PRD 6.3.7 要求「绘制时按缩放级别抽稀」。规格：

```kotlin
object PolylineSimplifier {
    /** @param epsilonM 容差（米），用局部等距投影近似 */
    fun simplify(points: List<LatLng>, epsilonM: Double): List<LatLng>
}
```

**容差按缩放级别的取值表**（本节定，`epsilonM`）：

| 层级 | 地图 zoom | epsilonM | 说明 |
| --- | --- | --- | --- |
| 全国/省 | < 10 | 40 | 只看轨迹轮廓 |
| 市/区 | 10 – 12 | 25 | |
| 街道 | 13 – 14 | 12 | |
| 详细 | 15 – 16 | 5 | |
| 最详 | ≥ 17 | **0（不抽稀）** | 用户要看清每个拐弯 |

| 规则 | 说明 |
| --- | --- |
| **抽稀只发生在渲染层** | 数据库**永远保留原始点**（PRD 6.3.7）。详情页「原始轨迹点数据表」（`F-HIS-32`）必须读原始点 |
| 分段抽稀 | 按 `segmentIndex` 分别抽稀，**跨 segment 不合并**（否则会把断开的两段连起来） |
| 缓存 | 以 `(tripId, epsilonM)` 为 key 做 `LruCache`，缩放变化时先查缓存 |
| 性能要求 | 10000 点抽稀耗时 < 30ms（Default 线程），保证 zoom 手势不掉帧（PRD 9.1：50 FPS） |

### 4.6 照片聚合（网格聚类）

PRD `F-MEDIA-12` 规定用网格聚类。

```kotlin
object MediaClusterer {
    /** 单元格边长（度）= 单元格像素 / 该层级全球像素 * 360。
     *  ⚠ cellPx 是【像素】：调用方必须先用 60.dp.toPx() 做密度换算再传入；
     *    直接写死 60f 在高密度屏会把格子画小、聚类过散（审阅 D8，2026-09-19 修正）。 */
    fun cellSizeDeg(zoom: Float, cellPx: Float): Double =
        cellPx / (256.0 * 2.0.pow(zoom.toDouble())) * 360.0

    fun cluster(items: List<MediaItem>, zoom: Float): List<Cluster>
}
```

| 参数 | 值 | 说明 |
| --- | --- | --- |
| 单元格边长 | **60dp → 密度换算成 px** 再换算成经纬度（随 zoom 动态，`F-MEDIA-12`） | 视觉大小恒定；**入参必须是换算后的像素**，不能写死 60f（高密度屏格子过小） |
| 桶 key | `floor(lat / cell) to floor(lng / cell)` | 速度 O(n) |
| 气泡直径分级 | 1–9 → 32dp；10–49 → 40dp；50–99 → 48dp；100+ → 56dp | `F-MEDIA-13` |
| 单点（簇内 1 个） | 直接显示圆形缩略图 | `F-MEDIA-14` |
| 视频点 | 缩略图 + 播放角标 | `F-MEDIA-15` |
| 重建时机 | zoom 变化结束时（**防抖 150ms**） | 避免手势中频繁重算 |

⚠ TODO(M0)：确认所用高德 3D 地图 SDK 版本是否自带 `ClusterOverlay`。**若有，优先用它**（官方实现含动画与点击回调）；若无，用上面的自研网格聚类 + `Marker` 批量提交。

### 4.7 线路评估与难度评级

依据 PRD 6.2.3。

```kotlin
object RouteEvaluator {
    private const val AVG_HIKING_SPEED_KMH = 3.5        // 预计耗时基准
    private const val ELEVATION_SAMPLE_INTERVAL_M = 50.0 // 沿线采样间距
    private const val DEM_ASCENT_THRESHOLD_M = 10.0      // DEM 噪声比传感器大，阈值 10m 起

    suspend fun evaluate(polyline: List<LatLng>): RouteMetrics
}

fun difficultyOf(distanceM: Double, ascentM: Double): Difficulty = when {
    distanceM < 5_000 && ascentM < 300 -> Difficulty.EASY
    distanceM < 10_000 && ascentM < 800 -> Difficulty.MODERATE
    distanceM < 15_000 && ascentM < 1500 -> Difficulty.HARD
    else -> Difficulty.CHALLENGING
}
```

| 项 | 规则 |
| --- | --- |
| 采样间距 | 沿折线每 **50m** 取一点（对折线做等距重采样） |
| 高程查询 | 批量提交给 `ElevationRepository`，**同一批最多 100 点**，结果落缓存 |
| 爬升计算 | 用 `ThresholdAccumulator(10.0)`（DEM 噪声大） |
| 缓存 key | 坐标取 **5 位小数**（约 1m 精度）后哈希，避免同一区域重复请求 |
| **禁止** | 高程不可用时编造数值或沿用上一点（PRD 6.1 降级规则）；UI 显示「估算」标注（`F-PLAN-12`） |

### 4.8 三条候选线路的生成策略

PRD 6.2.2 给了思路，本节给可执行规格。

| 候选 | 生成方式 |
| --- | --- |
| A（默认） | 高德步行规划，起点 → 终点直接请求 |
| B（最短） | 沿起终点连线取中点，向**垂直方向偏移** `d = clamp(连线长度 × 0.15, 200m, 1500m)`，作为途经点请求 |
| C（缓坡） | 同样取中点，向**另一侧**垂直偏移 `d` 请求；拿到结果后按「爬升最低」优先级排序 |

> ⚠ **定位说明（2026-09-19 起）**：上表 A/B/C 是「用途经点构造出三条候选」的旧策略。**高德原生多备选能力已在字节码层确认存在**（§4.8.1），故 A/B/C 的偏移构造法**只在原生多备选实跑后确认「差异不足 / 山区不可用」时才启用**，不再是默认实现。默认实现见 §4.8.1。

| 规则 | 说明 |
| --- | --- |
| 请求方式 | **串行**（非并发）。高德个人 Key 有 QPS 限制，并发易被限流 |
| 超时 | 单次 8 秒；超时按失败处理。**首条候选必须 ≤ 3 秒内上屏（PRD 6.2.7 / MA-1）**——拿到首条立即展示，其余候选后台补齐，8 秒上限只约束补齐阶段，不阻塞首条 |
| 去重 | 两条候选**距离差 < 5% 且爬升差 < 10%** → 视为重复，丢弃后一条 |
| 补足 | 去重后不足 3 条时，用不同偏移量再发一次请求；最多总共发 5 次 |
| 失败降级 | 全部失败 → 引导进入手动打点（`F-PLAN-20`），**不出现空白页**（PRD 6.2.7 验收） |
| `rCode` | **必须判 `rCode == 1000`**（PRD 6.2.1 第 3 条），其余按失败处理并给可读原因 |

#### 4.8.1 ✅ 已裁定（2026-09-19）：改用高德原生多备选，偏移构造法降为兜底

**原判断（PRD 6.2.2「高德步行规划单次只返回一条最优结果」）已被证伪。** 经反编译 SDK 字节码 + 核对官方文档，步行多备选是**官方原生能力**，且有三条路径（详见 PRD 6.2.2 的表）。按下列规则选型：

| 优先级 | 采用 | 理由 |
| --- | --- | --- |
| **1（默认）** | `RouteSearchV2.WalkRouteQuery.setAlternativeRoute(RouteSearchV2.AlternativeRoute.ALTERNATIVE_ROUTE_THREE)` | 与项目已有算路调用同一套依赖（`com.amap.api:search`），**不引入额外网络入口、不额外消耗 Key 配额**；语义与 Web API 的 `alternative_route=3` 完全对应 |
| 2（兜底 1） | `RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WALK_MULTI_PATH)` | 同依赖，V1 API；若 V2 在多路径上表现异常则退到这条 |
| 3（兜底 2） | Web API `GET /v5/direction/walking?alternative_route=3` | **注意 v3 无此参数**；引入它意味着自建一个网络入口，需评估与 SDK 并存时的 Key 复用、错误处理与限流 |
| 4（最后） | §4.8 的「垂直偏移途经点」构造法 | **仅当 1~3 实测都拿不到 3 条差异明显的方案时启用**。它本质是补一个不存在的缺陷，还会多消耗配额、产出用户无法理解的线路 |

**必须先确认的一件事（§9.2.2）**：山区/无路网坐标下，多备选**是否真返回 3 条差异明显**的线路（官方对导航 SDK 明确注明「并非所有路线均支持多路径规划」）。判据：三条方案的**距离差异 ≥ 10%**，否则视为「同一条线路返回三遍」，按优先级下探。

**废弃与保留**：`§4.8` 的 A/B/C 三候选**保留但降级**（仅在优先级 4 生效时使用）；`d = clamp(连线长度 × 0.15, 200m, 1500m)` 等偏移参数暂不删除，作为兜底参数保留。

### 4.9 提醒触发引擎

`core/data/alert/AlertEngine.kt`，纯函数式，便于单测。

```kotlin
class AlertEngine(private val settings: AlertSettings) {
    // ⚠ 距离打点的唯一持久基准是 lastDistanceMarkM（写入 recording_state，§6.6）。
    //   不要再用 ThresholdAccumulator 另立一套距离判定——双轨在暂停/恢复后
    //   「累加器锚点」与「n×interval 基准」会分叉，导致重复触发或漏触发（审阅 D2）。
    private var lastDistanceMarkM = 0.0
    private var lastAscentMarkM = 0.0
    private var lastDescentMarkM = 0.0

    /** 每收到一个有效采样点调用一次；返回需要落库的标记（可能为空） */
    fun onSample(sample: AlertSample): List<MarkerDraft>
}
```

| 规则 | 依据 |
| --- | --- |
| `settings.masterEnabled == false` → **不产生任何标记** | `F-ALERT-05` |
| `voiceEnabled` **只影响是否播报，不影响打点** | `F-ALERT-06` |
| 距离提醒：累计距离 ≥ `n × interval` 才触发，`n` 单调递增，**同一阈值不重复** | `F-ALERT-14/17` |
| 海拔提醒：爬升、下降**各自独立累计**，任一方向达 `n × interval` 触发 | `F-ALERT-24` |
| 暂停期间距离不计入，恢复后**从暂停前位置继续累加** | `F-ALERT-16` |
| 标记内容：序号 + 时间 + 经纬度 + 当时海拔 + 相对上一个**同向**标记的累计量 | `F-ALERT-27` |
| 触发后立即：① 写 marker ② 若 `voiceEnabled` 则 TTS 播报（`strings_tts.xml` 模板） | `F-ALERT-25/28` |
| 三个基准值（`lastDistanceMarkM` / `lastAscentMarkM` / `lastDescentMarkM`）**必须写进 `recording_state`** | 崩溃恢复后不重复触发 |

TTS 文案模板（`core/resources/.../strings_tts.xml`）：

| key | 中文 | 英文 |
| --- | --- | --- |
| `tts_alert_distance` | 已行进 %1$d 米 | %1$d meters covered |
| `tts_alert_ascent` | 已爬升 %1$d 米，当前海拔 %2$d 米 | Ascended %1$d meters. Current altitude %2$d meters |
| `tts_alert_descent` | 已下降 %1$d 米，当前海拔 %2$d 米 | Descended %1$d meters. Current altitude %2$d meters |

> 与屏幕显示文案**必须分开**（PRD 9.7.2）。屏幕上「↑300m」，播报念「已爬升 300 米，当前海拔 856 米」。

### 4.10 高程数据三级降级（`core/elevation/`）

PRD 6.1 定了三级路径，本节定实现与超时。

```kotlin
class ElevationRepository @Inject constructor(
    private val remote: ElevationRemoteSource,   // 路径 A：第三方公开服务
    private val localTiles: DemTileSource,       // 路径 C：本地预下载 DEM 瓦片
    private val cacheDao: ElevationCacheDao,     // 内存 + DB 缓存
) {
    suspend fun query(points: List<LatLng>): GhResult<List<Double?>>
}
```

| 顺序 | 数据源 | 超时 | 失败后 |
| --- | --- | --- | --- |
| 1 | DB / 内存缓存（5 位小数 key） | — | 未命中则下一级 |
| 2 | 本地 DEM 瓦片 + 双线性插值（路径 C） | — | 未覆盖则下一级 |
| 3 | 第三方公开高程服务（路径 A），批量 ≤100 点 | 6 秒 | 全部不可用 → 返回 `GhError.ElevationUnavailable` |
| 结果 | 成功的高程值写入 DB 缓存（长期有效，DEM 不会变） | | |

**降级行为（PRD 6.1 硬规则）**：不可用时**只显示经纬度、海拔显示「—」**，给可读原因；**绝不编造数值、绝不沿用上一点**。

**2026-09-19 实测（§9.2.1 T-07，脚本 `tools/m0-verify/12_elevation_services.py`）**

| 服务 | 本机网络连通性 | 批量能力 | 备注 |
| --- | --- | --- | --- |
| Open-Elevation | ✅ 单点 1.3–4.2 s | ✅ **一次 200 点 ≈ 1 s** | 社区公共实例，**配额与被限流的风险最高**，必须自带指数退避与缓存 |
| Open-Meteo elevation | ✅ 1.2–1.6 s | ✅ 逗号分隔批量 | 数据源 Copernicus DEM GLO-90；**当前首选**（稳定、有明确的非商用条款） |
| OpenTopoData | ✅ `srtm90m`/`srtm30m` 通；`aster30m` 超时 | ✅ | 同一域名下不同数据集可用性不一致，**要按数据集逐个探测**，不能整体判活 |

> ⚠ **连通性结论的适用范围**：以上测的是**本机宽带网络**，不等于手机蜂窝网络（尤其山区）。所以「服务不可达」的降级路径（`GhError.ElevationUnavailable` → 显示「—」）**照旧必须实现**。

> ⚠ **精度陷阱（实测发现，直接推翻「DEM 算爬升很准」的直觉）**：全球 90 m DEM 在**陡峭山峰会系统性低估**——泰山玉皇顶实测海拔 1532.7 m，SRTM 90 m 报 **1479 m**（低 54 m）；黄山光明顶实测 1860 m，报 **1725 m**（低 135 m）。
> 后果：**用 DEM 评估「单峰海拔」与「累计爬升」都会偏低**。因此：① 界面上的线路爬升评估必须标注为**估算值**（不写死数值、不宣称精确）——**已落地为 PRD `F-PLAN-46`（v1.13 新增）**；② 规划线路的爬升只用于**候选之间排序**，不作为绝对指标展示给用户对比实测数据；③ 若后续要提精度，优先换数据源（本地 30 m/12.5 m DEM 瓦片）而不是调算法。

### 4.11 算法参数总表 + 单元测试用例

**参数总表**（实现时以此为准，代码集中定义在 `AlgorithmDefaults.kt`）

| 参数 | 值 | 出处 |
| --- | --- | --- |
| 记录采样间隔（高精度） | 1 s | PRD 6.3.7 |
| 记录采样间隔（省电） | 3 s | PRD 6.3.7 |
| 最小位移过滤 | 3 m | PRD 6.3.7 |
| 精度门控 | accuracy > 50 m → 低质量 | PRD 6.3.7 |
| 速度门控 | > 30 km/h → 异常 | PRD 6.3.7 |
| 轨迹批量写入 | 10 点 或 5 s | PRD 6.3.7 |
| 状态持久化间隔 | 10 s | PRD 9.6 |
| 海拔中值滤波窗口 | 9 | PRD 4.4.2 |
| 垂直精度门控 | > 20 m 剔除 | PRD 4.4.2 |
| 无气压计持续确认 | 10 s | PRD 4.4.2 |
| 无气压计阈值下限 | 30 m | `F-REC-63` |
| 漂移修正周期 | 60 s | PRD 4.4.1 |
| 爬升统计阈值 | 3 m / 10 m | PRD 4.4.4 |
| 海拔打点阈值 | 100 m（默认） | `F-ALERT-21` |
| 距离打点阈值 | 100 m（默认） | `F-ALERT-11` |
| 计步预热 | **25 步**（在 PRD 4.5.4 的 20–30 步区间内取定单一值，测试断言才有唯一结果；真机核定后随 B6 调整） | PRD 4.5.4 |
| 计步最小间隔 | 250 ms | PRD 4.5.4 |
| 加速度计采样 | 50 Hz | PRD 4.5.4 |
| DEM 采样间距 | 50 m | §4.7 |
| DEM 爬升阈值 | 10 m | PRD 6.2.3 |
| 抽稀容差 | 见 §4.5 表 | PRD 6.3.7 |
| 聚合单元格 | 60 dp | PRD `F-MEDIA-12` |
| 停止按钮长按 | 1.5 s | `F-REC-06` |
| 照片关联时间窗 | ±30 min | `F-MEDIA-41` |
| 照片关联距离 | 500 m | `F-MEDIA-41` |
| 偏离计划提示 | 200 m | `F-PLAN-45` |

**单元测试用例清单**

| 用例 | 断言 |
| --- | --- |
| `outOfChina_东京_不偏移` | 输入输出**完全相同**（35.6762, 139.6503） |
| `outOfChina_边界值不抛异常` | (55.8271, 137.8347)、(0.8293, 72.004) 附近正常返回 |
| `wgsToGcj_境内偏移量合理` | 偏移距离落在 **30–800 m** 区间。**已实测（§9.2.1 B3，8928 点网格采样）**：40.9 ~ 686.3 m，中位 430.5 m，P5–P95 = 161 ~ 620 m。原写「300–800 m」与后来的「100–1000 m」**都不对**（有 1.0% 的采样点偏移 < 100 m）。区间取实测值外扩约 10 m 作余量；**若将来换用别的偏移算法，必须重跑 `tools/m0-verify/11_gcj02_numbers.py` 重新取范围** |
| `gcjToWgs_迭代收敛` | 断言「`eps=1e-9` 时迭代次数 ≤ 4、`eps=1e-12` 时 ≤ 6」，且**禁止用单次近似反解**（实测单步误差 0.59–3.40 m，不可接受）。实测数据见 §9.2.1 B2 |
| `gcjDistance_相对误差` | 1–5 km 段相对误差 ≤ **1%**（实测最大 0.53%）。⚠ 不要写成 ≤0.5%，会假失败 |
| `gcjToWgs_往返误差` | `gcjToWgs(wgsToGcj(p))` 与原点的距离 **< 1.5 m** |
| `仅GCJ转WGS后再转回_不变` | 幂等性（二次转换结果一致） |
| ⚠ TODO(M0) `基准点比对` | 在北京天安门、上海人民广场等固定点采集**真机高德地图实测坐标**作为基准，断言误差 < 2 m |
| `ThresholdAccumulator_噪声不累积` | 给 ±2m 抖动序列（有气压计阈值 3m），累计爬升为 **0** |
| `ThresholdAccumulator_阈值法重置` | 0→3→6 应得 ascent=6 且锚点=6 |
| `AscentAccumulator_双累加器独立` | 打点累加器（100m）触发 3 次时，统计累加器（3m）值 ≈ 实际爬升 |
| `Haversine_已知距离` | (0,0)→(0,1) ≈ **111.195 km**（±0.05 km；按 `EARTH_R = 6371008.8` 精确算得 111194.9 m。原写 111.32 km 是另一半径常量的结果，照抄会造成假红或掩盖回归） |
| `Haversine_同点` | 返回 0.0 |
| `StepCounter_回退检测` | 注入回退序列后 `wire == UNAVAILABLE` 且不再累加 |
| `AccelStep_合成信号` | 注入 2 Hz 正弦（模拟步频）100 个周期，计步误差 < 10% |
| `AccelStep_预热期不计步` | 前 25 步输出 0（预热期 = 25 步，取值唯一，与参数表一致） |
| `DouglasPeucker_共线点全删` | 直线上 100 点 → 只剩首尾 |
| `DouglasPeucker_拐点保留` | 保留显著拐点，误差 ≤ epsilon |
| `DouglasPeucker_分段不合并` | 两个 segment 分别抽稀，结果不跨段 |
| `MediaCluster_同格合并` | 同一小格内 5 张 → 1 个簇，`count=5` |
| `MediaCluster_缩放展开` | zoom 增大后，原 1 个簇拆成 5 个单点 |
| `Difficulty_边界值` | 4999m/299m → EASY；5000m/300m → MODERATE（左开右闭按 §4.7 的 `<`） |
| `AlertEngine_总开关关闭` | 不产生任何 `MarkerDraft` |
| `AlertEngine_关语音仍打点` | `voiceEnabled=false` 时标记照常产生 |
| `AlertEngine_不重复触发` | 距离停在 500m 反复采样，只触发 1 次 |
| `AlertEngine_双向独立` | 爬升 300m 后又下降 300m → 3 个 ascent + 3 个 descent |
| `CrsJson_未知枚举降级` | 解析 `type: "FOO"` → `MarkerType.UNKNOWN`，原始串保留 |
| `CrsJson_未知字段忽略` | 多出 `"newField": 1` 不报错 |
| `SchemaVersion_主版本过高` | `"2.0"` → 返回需升级错误，不抛异常 |

---

### 4.12 统计指标与分段计算（`core/data/stats/`）

口径的**唯一权威定义在 PRD 6.5.2 之后的「统计口径定义」小节**。本节只给实现落点，**不重复口径文字**——若本节与 PRD 口径不一致，以 PRD 为准。

```kotlin
object TripStatsCalculator {
    /**
     * @param points            已按 (segmentIndex, seq) 排序的原始轨迹点（含 quality 标记）
     * @param markers           全部标记，用于取登顶点做上下山切分
     * @param movingSecBySegment 每段的「运动秒数」——暂停时长无法从轨迹点推出，必须由外部传入
     */
    fun compute(
        points: List<TrackPoint>,
        markers: List<Marker>,
        movingSecBySegment: Map<Int, Long>,
    ): TripStats
}

object CalorieCalculator {          // PRD 6.5.2 公式 v1
    private const val MET_UPHILL = 7.0
    private const val MET_DOWNHILL = 4.0
    private const val MET_FLAT = 4.5
    private const val SLOPE_THRESHOLD = 0.03     // 净海拔斜率 ±3%
    private const val MIN_MOVING_SEC = 60L       // 样本下限，不足不出值

    /** 返回 kcal；运动时长 < 60s 返回 null（界面显示「—」，导出写 null） */
    fun estimate(points: List<TrackPoint>, bodyWeightKg: Int): Double?
}

object SplitsCalculator {
    fun byKilometer(points: List<TrackPoint>): List<KmSplit>
    fun byAltitudeGain(points: List<TrackPoint>, ascentThresholdM: Double): List<GainSplit>
}

object LegSplitter {
    /** 以**时间上最后一个** SUMMIT 标记为分界（PRD F-REC-37）；无登顶点返回 null */
    fun split(points: List<TrackPoint>, markers: List<Marker>): Legs?
}
```

| 实现要点 | 规则 |
| --- | --- |
| 有效点 | `quality == 0` 才参与距离、速度、海拔极值、卡路里计算；`quality != 0` 的点**仍参与绘制**（PRD 6.3.7） |
| 段内用时 | 段内用时 = 相邻有效点时间差之和，**且只在同一个 `segmentIndex` 内累加**；跨 segment 的时间差是暂停，不计入 |
| 为什么需要 `movingSecBySegment` 入参 | **暂停时长无法从轨迹点反推**（暂停期间根本没有点）。必须由 `RecordingSession` 在暂停时逐段记下时长并随记录落库（**承载结构：`recording_state.movingSecBySegmentJson`，§3.1.1**；崩溃恢复路径必须把该 JSON 读回，否则中断前各段运动时长永久丢失）；导入的历史数据若缺该信息，退化为「首尾点时间差」，并在详情页把该记录用时标注为估算 |
| 两个爬升累加器 | `totalAscentM` / `totalDescentM` 用 `ThresholdAccumulator`（3m / 10m，取值取决于 `trip.altitudeSource`），**与海拔打点累加器完全独立**（§4.3.1） |
| 尾段不足一格 | 照常输出，`distanceM` / `gainM` 写实际值（PRD 6.5.2） |
| 无登顶点 | `LegSplitter.split` 返回 `null`，详情页不显示上下山统计并给出说明（`F-REC-37`） |
| **`splits` / `legs` 不落库** | 二者都需要完整点序列，而详情页本来就要加载全部点。**按需计算**（O(n)，10800 点 < 20ms）并做内存 `LruCache`（key = `tripId`）。这样避免为它们各加一张表，也没有「表里的值与点不一致」的风险。若实测发现慢，再考虑落库（届时要加一致性校验） |
| 汇总统计要落库 | `trip` 表的统计字段在**保存记录时算一次**并写入；详情页只读不算，保证列表页无需加载轨迹点即可显示距离/爬升 |

---

## 5. 页面 × ViewModel 契约

### 5.1 通用约定

```kotlin
abstract class GhViewModel<S : Any, E : Any, F : Any>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<F>(Channel.BUFFERED)
    val effect: Flow<F> = _effect.receiveAsFlow()

    protected val current: S get() = _state.value
    protected fun setState(reduce: S.() -> S) { _state.update(reduce) }
    protected suspend fun send(effect: F) { _effect.send(effect) }

    abstract fun onEvent(event: E)
}
```

| 约定 | 说明 |
| --- | --- |
| 单向数据流 | UI 只发 `Event`，只读 `State`；一次性动作（导航、Toast、TTS）走 `Effect` |
| **UiState 必须不可变且可比较** | `data class`，避免 Compose 无效重组 |
| **UiState 不持有 Android 类型** | 不出现 `Uri` / `Context` / `Drawable`；用 `String` / 自定义值类 |
| **列表数据不在 State 里存 Entity** | 存 UI 模型（已格式化好的展示字符串 + 枚举），格式化在 ViewModel 做 |
| 耗时操作 | 一律 `viewModelScope.launch { withContext(io) { ... } }` |
| Compose 收集 | `collectAsStateWithLifecycle()`（不是 `collectAsState`），避免后台重组 |

### 5.2 页面契约表（P-01 ~ P-18）

| 页面 | ViewModel | 核心 UiState | 主要 Event | Effect |
| --- | --- | --- | --- | --- |
| **P-01 权限引导** | `PermissionViewModel` | `perms: Map<GhPermission, PermState>`、`allGranted: Boolean`、`privacyAgreed: Boolean` | `Request(p)`、`RequestAll`、`AgreePrivacy` | `OpenAppSettings(p)`、`ToHome` |
| **P-02 首页地图** | `HomeViewModel` | `camera: CameraState`、`plannedRoutes: List<RouteChip>`、`hasActiveRecording: Boolean`、`layer: MapLayer`、`mapReady: Boolean` | `MoveCamera`、`Locate`、`ToggleLayer`、`PlanClicked`、`StartRecordClicked` | `OpenPlan`、`OpenRecordingSetup`、`Navigate(DeepLink)` |
| **P-03 选点** | `PlanPickViewModel` | `pickTarget: START/END`、`start: PointUi?`、`end: PointUi?`、`query: String`、`suggestions: List<TipUi>`、`searching: Boolean`、`searchError: GhError?` | `MapTapped(p)`、`SearchChanged(q)`、`TipSelected(t)`、`PoiClicked(poi)`、`UseCurrentLocation`、`DragPoint(target, p)` | `Toast(msg)` |
| **P-04 推荐列表** | `PlanSuggestViewModel` | `legs: List<RouteCandidateUi>`（距离/耗时/爬升/难度/是否估算）、`selectedIndex: Int`、`loading: Boolean`、`elevationUnavailable: Boolean` | `Select(i)`、`Confirm`、`Replan`、`ManualMode` | `OpenManual`、`OpenSave`、`Toast` |
| **P-05 手动打点** | `PlanManualViewModel` | `waypoints: List<WaypointUi>`（序号）、`lineMode: STRAIGHT/SNAP`、`totalDistanceM: Double`、`canUndo: Boolean` | `MapTapped`、`Undo`、`DragWp(i, p)`、`DeleteWp(i)`、`ToggleLineMode`、`Confirm` | `OpenSave`、`Toast(超 50 点提示)` |
| **P-06 保存线路** | `PlanSaveViewModel` | `name: String`（默认取终点 POI 名）、`note: String`、`nameError: Int?` | `NameChanged`、`NoteChanged`、`Save` | `Back`、`Toast(已保存)` |
| **P-07 计划列表** | `PlanListViewModel` | `routes: List<RouteRowUi>`（名称/总距离/总爬升/创建时间） | `Open(id)`、`Rename(id)`、`Delete(id)`、`Export(id)`（`F-PLAN-43`）、`New` | `OpenPick`、`OpenExport(id)` |
| **P-08 记录页** | `RecordingViewModel` | 见 §5.3 | `Pause/Resume/Summit/DropMarker/Stop` | `Toast`、`Speak`、`Vibrate`、`ExitToSave` |
| **P-09 记录保存** | `RecordSaveViewModel` | `summary: TripSummaryUi`、`name: String`、`note: String`、`planLinked: Boolean` | `Save`、`Discard`、`NameChanged` | `Back`、`Toast` |
| **P-10 记录列表** | `HistoryListViewModel` | `trips: List<TripRowUi>`（含轨迹缩略图几何）、`summary: ListSummaryUi`、`groupByMonth: Boolean`、`empty: Boolean`、`swipedId: String?` | `Open(id)`、`Delete(id)`、`BatchDelete(ids)`、`BatchExport(ids)`、`Search(q)`、`Filter(f)` | `Toast`、`UndoDelete(id)` |
| **P-11 记录详情** | `HistoryDetailViewModel` | `trip: TripDetailUi`、`tab: OV/CHART/SPLIT/MARKER/PHOTO`、`altitudeCurve: List<ChartPoint>`、`paceCurve`、`splits: List<SplitUi>`、`legs: LegsUi`（登顶点切分）、`markers: List<MarkerRowUi>`、`media: List<MediaThumbUi>`、`cursorIndex: Int?`、`altitudeLowPrecision: Boolean` | `SelectTab`、`CursorMoved(i)`、`Rename`、`Delete`、`Export`、`Share`、`LocateMarker(i)`、`EditMarkerNote(i)`、`SaveTrackScreenshot` | `OpenMediaViewer(i)`、`OpenExport`、`Toast` |
| **P-12 照片地图** | `MediaMapViewModel` | `clusters: List<ClusterUi>`、`zoom: Float`、`selectedCluster: ClusterUi?`、`strip: List<MediaThumbUi>`、`stripIndex: Int`、`scanning: Boolean`、`scanProgress: Float`、`permissionMissing: Boolean`、`approximateLocation: Boolean` | `ZoomChanged`、`ClusterClicked`、`StripScrolled(i)`、`Rescan`、`SheetHeightChanged` | `OpenViewer(i)` |
| **P-13 媒体查看器** | `MediaViewerViewModel` | `index: Int`、`total: Int`、`current: MediaFullUi`、`isVideo: Boolean` | `Swipe(delta)`、`ShowOnMap`、`Share`、`Delete` | `Back`、`Toast` |
| **P-14 设置** | `SettingsViewModel` | `groups: List<SettingGroupUi>`（按 PRD 6.7 分组）、`barometerHint: Boolean`、`importRunning: Boolean` | `Toggle(key)`、`Select(key, value)`、`NumberChanged(key, v)`、`ResetToDefault`、`ExportOne`、`ExportAll`、`Import` | `PickExportDir(SAF)`、`PickImportFiles`、`OpenExportProgress`、`OpenImportPreview`、`Recreate`(语言切换)、`Toast` |
| **P-15 关于** | `AboutViewModel` | `versionName`、`versionCode`、`repoUrl`、`license`、`privacyUrl` | `OpenLink(k)` | `OpenBrowser(url)` |
| **P-16 导出进度** | `ExportProgressViewModel` | `phase: Phase`、`percent: Float`、`currentItem: String?`、`done: Boolean`、`resultPath: String?`、`error: GhError?` | `Cancel`、`OpenFolder`、`Share` | `OpenShareSheet(uri)`、`Toast` |
| **P-17 导入预览** | `ImportPreviewViewModel` | `sourceCount: Int`、`trips: Int`、`routes: Int`、`conflicts: List<ConflictRowUi>`、`suspected: List<ConflictRowUi>`、`crsConvert: CrsConvertUi?`、`policy: ConflictPolicy`、`applyToAll: Boolean`、`previewOnly: Boolean`、`restoreSettings: Boolean`（默认 `false`，`F-IO-36`）、`settingsDiff: List<SettingDiffUi>`（勾选后才计算，`F-IO-37`）、`oversizeWarning: Boolean`（ZIP > 500MB 置位，`F-IO-64`） | `PolicyChanged`、`ApplyToAllToggled`、`RestoreSettingsToggled`、`Confirm`、`Cancel` | `OpenImportProgress`、`Toast` |
| **P-18 导入结果** | `ImportResultViewModel` | `success: Int`、`skipped: Int`、`failed: Int`、`failures: List<FailureRowUi>`（文件 + 条目 + 原因） | `RetryFailed`、`Close` | `Back` |

### 5.3 P-08 记录页契约（最复杂，单列）

```kotlin
data class RecordingUiState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedSec: Long = 0,                 // 已格式化前的秒数
    val distanceM: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val currentAltitudeM: Double? = null,     // null → 显示「—」
    val altitudeSource: AltitudeSource = AltitudeSource.GPS_ONLY,
    val altitudeLowPrecision: Boolean = false, // 无气压计 → 「GPS 估算」角标（F-REC-61）
    val steps: Int = -1,                      // -1 → 显示「—」
    val stepSource: StepSourceKind = StepSourceKind.UNAVAILABLE,
    val stepAlgorithmWarning: Boolean = false, // ③ 加速度计算法 → 提示耗电（F-REC-71）
    val speedMps: Double = 0.0,
    val paceSecPerKm: Int = 0,
    val track: TrackRenderData = TrackRenderData.Empty,   // 已按 zoom 抽稀的渲染数据
    val markers: List<MarkerUi> = emptyList(),
    val plannedPolyline: List<LatLngUi> = emptyList(),     // 关联计划线路（F-PLAN-44）
    val alertsEnabled: Boolean = true,                     // 顶栏「提醒:开」指示（F-ALERT-04）
    val keepScreenOn: Boolean = true,
    val primaryMetric: PrimaryMetric = PrimaryMetric.DISTANCE, // 点击切换主位（F-REC-15）
    val gpsSearching: Boolean = false,                     // 「正在搜索卫星」（PRD 9.6）
    val majorMetric: String = "0.00",                      // 已格式化（单位随设置）
    val majorUnit: String = "km"
)

sealed interface RecordingEvent {
    data object Pause : RecordingEvent
    data object Resume : RecordingEvent
    data class Summit(val note: String?) : RecordingEvent
    data class DropMarker(val note: String) : RecordingEvent
    data object Stop : RecordingEvent            // UI 侧需长按 1.5s（F-REC-06）
    data object CyclePrimaryMetric : RecordingEvent
    data object CalibrateAltitude : RecordingEvent  // F-REC-64
    data class ManualSteps(val steps: Int) : RecordingEvent  // F-REC-73
}

sealed interface RecordingEffect {
    data class Toast(val textRes: Int, val args: List<Any> = emptyList()) : RecordingEffect
    data class Speak(val utterance: TtsUtterance) : RecordingEffect
    data object Vibrate : RecordingEffect
    data object ExitToSave : RecordingEffect
}
```

| 交互 | 规格 |
| --- | --- |
| 数据刷新 | **每秒一次**（`F-REC-16`）；UI 侧用 `flow { while(true){ emit(Unit); delay(1000) } }` 驱动，**不要**让数据源每秒重建 State |
| 暂停时 | 面板整体变灰 + 「已暂停」浮层（`F-REC-17`） |
| 停止按钮 | 长按 1.5s + 进度环反馈（`F-REC-06`） |
| 屏幕常亮 | `keepScreenOn = settings.recordKeepScreenOn`（`F-REC-18`），Compose 用 `DisposableEffect` 加 `FLAG_KEEP_SCREEN_ON` |
| 地图轨迹更新 | **不每秒重绘整条线**：增量 `addPolyline(points)` 追加末段；暂停后新建一条 Polyline（**不连接**，`F-REC-05`） |
| 提醒指示 | 顶栏显示当前提醒开关状态图标（`F-ALERT-04`） |

### 5.4 导航图

```kotlin
sealed class GhRoute(val route: String) {
    data object Permissions : GhRoute("permissions")
    data object Home : GhRoute("home")                                  // Tab
    data object PlanList : GhRoute("plan")                              // Tab
    data object PlanPick : GhRoute("plan/pick?routeId={routeId}&target={target}")   // target=start|end，缺省 start
    data object PlanSuggest : GhRoute("plan/suggest")
    data object PlanManual : GhRoute("plan/manual")
    data object PlanSave : GhRoute("plan/save?routeId={routeId}")
    data object RecordingSetup : GhRoute("record/setup")
    data object Recording : GhRoute("record/live")                      // 全屏覆盖，不入 Tab
    data object RecordSave : GhRoute("record/save")
    data object HistoryList : GhRoute("history")                        // Tab
    data object HistoryDetail : GhRoute("history/{tripId}")
    data object MediaMap : GhRoute("media?tripId={tripId}")
    data object MediaViewer : GhRoute("media/viewer?index={index}")
    data object Settings : GhRoute("settings")                          // Tab
    data object About : GhRoute("about")
    data object ExportProgress : GhRoute("io/export")
    data object ImportPreview : GhRoute("io/import/preview")
    data object ImportResult : GhRoute("io/import/result")
}
```

| 规则 | 说明 |
| --- | --- |
| 底部导航 | 只在 Home / PlanList / HistoryList / Settings 显示（PRD 8.2） |
| **P-08 记录页为全屏覆盖** | 用 `NavHost` 外层的全屏目的地，**不显示底部导航**（PRD 8.2） |
| 记录中退出 | 记录页返回键拦截：提示「记录仍在后台进行」而非直接停止（`F-REC-51`） |
| 深链 | 支持 `gohiking://` scheme（供分享导入 `F-IO-23`）；⚠ TODO(M0) 增加 debug-only 的 `gohiking://debug/pXX` 直达深链，便于对照原型与真机（原型已有 `#pXX`） |

---

## 6. 平台能力实现

### 6.1 前台服务与记录会话（`core/data/recording/`）

**核心设计：`RecordingSession` 是唯一可变状态源，`Service` 只管生命周期与通知。**

```kotlin
@Singleton
class RecordingSession @Inject constructor(
    private val locationProvider: LocationProvider,
    private val altitudeFuserFactory: AltitudeFuser.Factory,   // ⚠ 工厂注入，见代码块后说明
    private val stepSourceFactory: StepSourceFactory,
    private val alertEngineFactory: AlertEngine.Factory,       // ⚠ 工厂注入，见代码块后说明
    private val sampler: TrackSampler,
    private val stateDao: RecordingStateDao,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val state: StateFlow<RecordingSessionState>
    // extraBufferCapacity 必须显式声明（建议 64 + DROP_OLDEST + 丢点计数），见 §6.2
    val samples: SharedFlow<TrackSample>

    fun start(planRouteId: String?, name: String)      // F-REC-01/02
    fun pause()                                        // F-REC-04，结束当前 segment
    fun resume()                                       // F-REC-05，segmentIndex + 1
    fun markSummit(note: String?)                      // F-REC-30
    fun dropMarker(note: String)                       // F-REC-40
    fun stop(): TripDraft                              // F-REC-06/07
    suspend fun save(draft: TripDraft)                 // 大事务，见 §3.4
}
```

> **为什么 AltitudeFuser / AlertEngine 必须工厂注入（2026-09-19 审阅修正）**：两者都持有**逐场可变状态**（`altRef`、气压基准、三个打点基准、`ThresholdAccumulator` 锚点）。`RecordingSession` 是 `@Singleton`（冻结决策），若直接持有这两个协作者，**第二场记录会继承第一场的基准**——首场一切正常，测试极难发现。规则：
> - `start()` 时新建：`altitudeFuser = altitudeFuserFactory.create(hasBarometer)`；`alertEngine = alertEngineFactory.create(alertSettings)`
> - 两者必须提供 `restore(state)`：崩溃恢复时从 `recording_state` 回灌基准，否则恢复后提醒会重复触发（见 §6.6）
> - 对应 §9.3 的实现约束，§8.8 必须提供 `FakeAltitudeFuser` / `FakeAlertEngine`

| 项 | 规格 |
| --- | --- |
| 服务类型 | `android:foregroundServiceType="location"`（`FOREGROUND_SERVICE_LOCATION`，Android 14+ 必需） |
| 启动 | `ServiceCompat.startForeground(this, NOTIF_ID, notif, FOREGROUND_SERVICE_TYPE_LOCATION)`；**必须在 `startForegroundService` 后 5 秒内调用**，否则 ANR/崩溃 |
| `onStartCommand` 返回 | `START_STICKY` |
| `stopWithTask` | `false`（任务划掉后仍记录，`F-REC-51`） |
| 通知内容 | 「正在记录 · 距离 x.xx km」，**每 5 秒更新一次**（不是每秒，省电） |
| 通知动作 | 暂停 / 继续 / 停止（`F-REC-52`），用 `PendingIntent.getService` + Action |
| 停止条件 | 用户停止 + 保存/丢弃完成 → `stopSelf()` |
| 权限降级 | 无 `ACCESS_BACKGROUND_LOCATION` → 仅前台记录，并提示（`F-REC-53`） |
| 国产 ROM 引导 | 首次记录时检测厂商（`Build.MANUFACTURER`），给对应「电池优化白名单」引导文案（`F-REC-54`） |

### 6.2 定位（`core/location/`）

```kotlin
interface LocationProvider {
    val fixes: Flow<LocationFix>          // 仅 GCJ-02
    fun start(intervalMs: Long)
    fun stop()
}

data class LocationFix(
    val lat: Double, val lng: Double,     // 统一 GCJ-02
    val altitudeM: Double?,
    val verticalAccuracyM: Float?,
    val mslAltitudeM: Double?,            // API 34+
    val accuracyM: Float,
    val speedMps: Float,
    val bearing: Float,
    val timestampMs: Long,
)
```

| 主/备 | 实现 | 坐标系处理 |
| --- | --- | --- |
| 主 | `AmapLocationSource`（`AMapLocationClient`） | 已返回 GCJ-02，**不转** |
| 备 | `FusedLocationSource`（`FusedLocationProviderClient`） | 返回 WGS-84，**必须转 GCJ-02** ⚠ |

| 规则 | 说明 |
| --- | --- |
| 主备切换 | 高德连续 30 秒无回调或返回错误码 → 切 Fused，并记录日志；恢复后切回 |
| 采样间隔 | 高精度 1000ms / 省电 3000ms（PRD 6.3.7） |
| 高德回调线程 | **不在回调里做算法**：立即 `trySend` 进 **Channel(capacity = 64, onBufferOverflow = DROP_OLDEST)**，算法在 `Default` 处理；**丢点必须计数并打日志**——记录场景丢一个点 = 数据永久缺失，会话结束时 diagnostics 里丢点数 > 0 必须可见 |
| 暂停时 | **停止定位**（省电），恢复时重新 `start` |
| 高德定位模式 | 高精度模式 `Hight_Accuracy`；省电模式 `Battery_Saving` |

### 6.3 传感器（`core/location/sensor/`）

| 传感器 | 采样 | 说明 |
| --- | --- | --- |
| `TYPE_PRESSURE` | `SENSOR_DELAY_NORMAL`，聚合到 1Hz | 启动记录时 `getDefaultSensor()` 判空（`F-REC-60`），结果写 `trip.hasBarometer` |
| `TYPE_STEP_COUNTER` / `DETECTOR` | `SENSOR_DELAY_UI` | 见 §4.4 |
| `TYPE_ACCELEROMETER` | 50 Hz（仅 ③ 启用时） | 记录结束必须 `unregisterListener` |

| 规则 | 说明 |
| --- | --- |
| 注册时机 | **仅记录中注册**，暂停时注销（除计步，计步在暂停时需要维护基准） |
| 一律提供 `FakeSensorSource` | 供单元测试注入固定序列（§8.2 依赖） |
| 首次无气压计弹窗 | 只弹一次，`barometer_hint_shown` 控制（`F-REC-62`） |

### 6.4 地图封装（`core:map`）

```kotlin
@Composable
fun GhMapView(
    camera: CameraState,
    overlays: MapOverlays,
    onMapClick: (LatLngUi) -> Unit,
    onPoiClick: (PoiUi) -> Unit,
    onMapLongClick: (LatLngUi) -> Unit,
    onCameraIdle: (zoom: Float) -> Unit,
    modifier: Modifier = Modifier,
)
```

**高德 `MapView` 生命周期接入（Compose 里最容易出内存泄漏的地方）**

```kotlin
// ⚠ 用 remember 持有 MapView：原写法让 DisposableEffect 引用 factory 的局部变量（编译不过），
//   且 factory 与 onDispose 各调一次 onDestroy（双重销毁）。修正（审阅 D14）：
//   onDestroy 只在 onRelease 调用【一处】；旋转/后台丢状态由 onSaveInstanceState 桥接。
val mapView = remember { MapView(context) }

AndroidView(
    factory = { mapView.apply { onCreate(null) } },
    update = { /* 提交 overlays */ },
    onRelease = { it.onDestroy() },          // 销毁唯一出口
)

DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            ON_RESUME -> mapView.onResume()
            ON_PAUSE -> { mapView.onPause(); mapView.onSaveInstanceState() }
            else -> Unit
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }   // 不在此调 onDestroy
}
```

| 规则 | 说明 |
| --- | --- |
| 覆盖物批量提交 | 每帧最多一次：先在 `Default` 算好数据，`withContext(Main)` 里统一 diff 后增删 |
| 轨迹绘制 | 每个 `segment` 一条 `Polyline`，**段间不连线**（`F-REC-05`）；线宽用 `AMap` 的 `width`（dp），缩放时视觉一致（PRD 6.1 交互说明） |
| 地图语言 | 语言切换时调 `AMap.setMapLanguage(...)`（`F-I18N-20`）。⚠ TODO(M0) 确认 3D SDK 是否支持及常量取值 |
| 标记图标 | 用 `BitmapDescriptorFactory.fromBitmap()` 缓存 5 类图标（登顶/距离/爬升/下降/手动），**避免每帧新建** |
| 地图类型 | 标准 / 卫星（`F-MAP-04`）；深色主题切高德夜间样式（PRD 8.4） |
| 无 Key / 初始化失败 | 降级为占位提示，**记录功能仍可用**（PRD 9.6） |
| 依赖图 | ⚠ TODO(M0) 若改用 MapLibre（`FEASIBILITY-TERRAIN.md` 的真 3D 路线），本节需重写；v1.0 不走这条路 |

### 6.5 语音播报（`core/location/tts/`）

| 项 | 规格 |
| --- | --- |
| 引擎 | Android `TextToSpeech`，`onInit` 成功后 `setLanguage(Locale)`（`F-I18N-30`） |
| 音频焦点 | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（`F-ALERT-42`），播报后 `abandonAudioFocusRequest` |
| 队列 | `QUEUE_FLUSH`（新播报打断旧播报，登山场景不需要排队） |
| 引擎不可用 | `onInit == ERROR` 或语言不可用 → **静默降级，只打点不报错**（`F-ALERT-43`） |
| 语言包缺失 | 提示下载，并降级为静默打点（`F-I18N-32`） |
| 语言切换后 | `shutdown()` → 重新 `TextToSpeech(...)` 初始化（`F-I18N-33`） |
| 文案来源 | **`strings_tts.xml`**，与显示文案分离（`F-I18N-31`） |
| 生命周期 | 与 `Application` 同生命周期，记录结束时不必销毁，但**必须在语言变化时重建** |

### 6.6 崩溃恢复（`F-REC-09`）

```
每 10 秒（PRD 9.6）
  └─ RecordingSession 把当前状态 UPSERT 到 recording_state（含 3 个提醒基准 + 双累加器锚点 + 逐段运动时长 JSON + 海拔基准 + 计步基准）
       │
App 启动
  └─ 查 recording_state
       ├─ 无 → 正常首页
       └─ 有 → 弹「检测到未结束的记录，是否恢复？」
            ├─ 恢复 → RecordingSession.rebind(tripId)（沿用 tripId、segmentIndex+1；⚠ 单例【不存在「重建」】——
            │         rebind 重置会话内可变状态，并对 AltitudeFuser / AlertEngine 调 restore(state) 回灌基准，
            │         否则恢复后提醒重复触发、海拔出现跳变）
            │         ⚠ 恢复点与中断点**不连线**（PRD 9.6「不产生跳变直线」）
            └─ 丢弃 → 清 recording_state（已落库的轨迹点保留还是删除？→ **删除**，
                      因为该记录未完成、未命名，留在库里用户看不懂；删除前二次确认）
```

---

## 7. 构建与 CI

### 7.1 Gradle 关键配置

```kotlin
// app/build.gradle.kts
android {
    namespace = "com.gohiking.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gohiking.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"          // 与 PRD 7.3 的 generator.versionName 一致
        // ⚠ 冲突提示：限定 locale 会连带影响伪本地化，而 F-I18N-40 明确要求用 en-XA 检查硬编码文案。
        //    必须实测「限定 locale 后，en-XA 伪语言在 debug 变体下是否仍可切换」，否则本行需改为
        //    「仅 release 限定、debug 放开」。另：AGP 新版本已用 androidResources.localeFilters 取代本 API，
        //    具体用哪个取决于最终 AGP 版本（见 §9.5-D5、D6）。
        // ⚠ AGP 8.8 起 resourceConfigurations 已弃用（改用 androidResources { localeFilters }）。
        //    最终写法由 M0 锁定的 AGP 版本决定：< 8.8 用下面这行；≥ 8.8 见文件末尾的替代写法。
        //    另：限定 locale 与 F-I18N-40 依赖的 en-XA 伪本地化的关系仍需实跑确认（T-18）。
        resourceConfigurations += listOf("en", "zh-rCN")   // 不打包其他语言，减体积
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["AMAP_KEY"] = amapKey
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // ⚠ kotlinOptions 自 Kotlin 2.0 起已弃用；叠加 §7.4 的 warningsAsErrors = true 会【直接编译失败】。
    //    改用顶层 kotlin { compilerOptions { ... } }，见本节末尾的「Kotlin 编译选项写法」。

    buildTypes {
        debug {
            // 2026-09-20 起：不加 applicationIdSuffix，debug/release 共用包名 com.gohiking.app，
            // 高德单 Key 同时绑定 Debug/Release 两个 SHA1 即可（PRD 4.2 已同步）。
            isMinifyEnabled = false
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_DEBUG").orEmpty()
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_RELEASE").orEmpty()
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

// PRD 7.1：导出 Room schema，供迁移测试使用。
// ⚠ KSP 扩展必须注册在【项目级顶层】（与 android {} 平级）——写在 android {} 内会
//   Unresolved reference: ksp（照抄即编译失败）；schema 不导出还会连带废掉 §3.1 的
//   「schema 提交仓库 + MigrationTestHelper 迁移测试」整条链路（审阅 H-2，2026-09-19 修正）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

> **debug 变体加 `applicationIdSuffix` 的副作用**：包名变成 `com.gohiking.app.debug`，与高德 Key 绑定的包名不一致 → **必须单独申请一个 debug Key**（PRD 4.2 已要求 Debug/Release 各一个）。这一点必须在 README 的高德 Key 申请说明里写清楚，否则开发者第一步就卡住。

**`proguard-rules.pro` 要点**

```proguard
# ✅ 2026-09-19 已实测（§9.2.1 T-17）：高德 jar 内【没有任何】proguard/consumer 规则
#   （整包只有 META-INF/MANIFEST.MF），因此【必须手写】下面的保留规则。
#   只走 debug 不混淆是发现不了这问题的——它只在 release 变体现形。

# ---------- 高德 SDK（实测无内置规则，全部手写）----------
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# 保留反射/序列化用到的注解与内部类（高德大量使用内部类与匿名类做回调）
-keepattributes *Annotation*, InnerClasses, Signature

# 以下为 App 自身需要的补充规则。

# 保留 @Serializable 生成的多态序列化器（导入旧文件时的未知子类降级依赖它）
# （keepattributes 已在上方统一声明一次，不再重复——收紧时才不会漏改一处）
-keepclassmembers class com.gohiking.** {
    *** Companion;
}
-keepclasseswithmembers class com.gohiking.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留 Room 生成的实现
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
```

> ⚠ 上面的高德 keep 规则是**保守写法**（`com.amap.api.** { *; }` 会保留整个包，增大混淆后体积）。
> M0 打 release 包实跑全流程后可再收紧；**收紧前后都必须跑一遍完整记录 + 规划 + 定位流程**，不能只看编译通过。

**Kotlin 编译选项写法（替代已弃用的 `kotlinOptions`）**

```kotlin
// 顶层（与 android {} 平级），Kotlin 2.0+ 的写法
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 需要时再加：freeCompilerArgs.add("-opt-in=...")
    }
}
```

**若最终锁定 AGP ≥ 8.8，`resourceConfigurations` 换成：**

```kotlin
android {
    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }
}
```

> 两种写法的取舍完全由 M0 锁定的 AGP 版本决定（§9.2.1 T-19）：**< 8.8 用 `resourceConfigurations`，≥ 8.8 用 `localeFilters`**。
> 叠加 §7.4 的 `warningsAsErrors = true`，用错版本会**直接让 CI 变红**，不会静默通过。


#### 7.1.1 JDK 与构建工具链版本要求（2026-09-19 已核实）

> **一句话**：**装 JDK 17 是硬门槛；但「用哪个 JDK 去跑 Gradle」另有一道天花板，天花板高度由 Gradle 版本决定 —— 这两件事必须一起定，否则会出现「JDK 没错、Gradle 也没错，组合起来直接构建失败」。**

| 层 | 要求 | 依据 |
| --- | --- | --- |
| **AGP 要求** | AGP **8.x 与 9.x 都要求最低 JDK 17**（AGP 7.x 才是 11） | Android 官方 AGP 兼容表（AGP 8.7 → JDK 17；AGP 9.0 → JDK 17） |
| **本项目字节码级别** | `sourceCompatibility` / `targetCompatibility` = 17，`jvmTarget` = `JVM_17`（见本节配置）→ **JDK 17 就是自然基线**；更高的 JDK 也能编出 17 字节码 | 本节配置 |
| **CI** | §7.3 已固定 `actions/setup-java` 用 **temurin 17** | §7.3 |
| **「跑 Gradle 的 JDK」上限** | 见下表 —— **拿一个比 Gradle 版本更新的 JDK 去跑，会直接失败**，而且报错信息常常指向不明 | Gradle 官方 Java 兼容矩阵 |

**「运行 Gradle 的 JDK」↔「最低 Gradle 版本」**（Gradle 官方兼容矩阵，查于 2026-09-19）

| 运行 Gradle 的 JDK | 最低 Gradle 版本 |
| --- | --- |
| 17 | 7.3 |
| 21 | 8.5 |
| 22 | 8.8 |
| 23 | 8.10 |
| 24 | 8.14 |
| **25** | **9.1.0** |
| 26 | 9.4.0 |

> 另注：**Gradle 9.0 起把 daemon 的最低 JVM 抬到了 Java 17**（此前是 Java 8）；Gradle 10 预计要求 Java 21。

**本机实测（2026-09-19）**

| 位置 | 实测结果 |
| --- | --- |
| `C:\Program Files\Android\Android Studio\jbr`（Android Studio 自带 JBR） | **JDK 25.0.2** |
| `C:\Users\hxzha\.jdks\jbr-17.0.14` | **JDK 17.0.14**（`bin/java.exe` 可用，可直接指定） |
| `%JAVA_HOME%` | **未设置**；`java` 不在 PATH |
| `C:\Users\hxzha\.gradle\jdks` | **空**（只有 CACHEDIR.TAG）→ 从未用过 Gradle toolchain 自动下载 |

> ⚠ **本机已存在的真实冲突**：Android Studio 默认会拿它自带的 **JDK 25** 去跑 Gradle。若沿用本节初始基线 **AGP 8.7.3 + Gradle 8.9**，由于 Gradle 8.9 **不支持 JDK 25**（上表：JDK 25 需 Gradle ≥ 9.1.0），**同步 / 构建会直接失败**。必须把 **Gradle JDK 显式换成 17**。

**M0 必须二选一并锁死（与 §9.2.2 T-14 的依赖基线决策合并做，不要分两次定）**

| 方案 | AGP / Gradle | 运行 Gradle 的 JDK | 代价 |
| --- | --- | --- | --- |
| **A（稳，建议起步）** | AGP 8.7.3 + Gradle 8.9 | **JDK 17**（本机已有 `jbr-17.0.14`） | 无需额外安装；依赖整体停在 2024 年末，与 §1.3 现状一致 |
| **B（新）** | AGP 9.x + Gradle 9.1+ | JDK 21 或 25（可直接用 Android Studio 自带 JBR，省一次安装） | AGP 9 只保留新 DSL：`org.jetbrains.kotlin.android` 与内置 Kotlin 不兼容、`applicationVariants` 等旧 API 全部移除、`resourceConfigurations` 必须改 `localeFilters`；Hilt / KSP / ktlint 等第三方插件要逐个确认兼容 |

另两条已核实：

- **`compileSdk = 35` 需要 AGP ≥ 8.6.0**（API 36 需 AGP ≥ 8.9.1，API 37 需 ≥ 9.1.1）—— 上面两个方案都满足。
- **不要在 `gradle.properties` 里写死 `org.gradle.java.home` 的绝对路径**：团队各人安装位置不同，一提交就互相打架。推荐做法：Android Studio 里把 **Gradle JDK 设为 `GRADLE_LOCAL_JAVA_HOME`**（读项目内 `.gradle/config.properties`，各人一份、不入库；`.gitignore` 已排除 `.gradle/`），或设为 `JAVA_HOME`；CI 显式设 `JAVA_HOME`（§7.3 已做）。

> **为什么现在不写 `jvmToolchain(17)`**：`jvmToolchain` 在本机找不到对应 JDK 时会**尝试联网下载**（需 Foojay resolver 插件 + 网络），离线或 CI 首跑会失败；本机 `~/.gradle/jdks` 为空，说明这条路从未走过。当前写法（`compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }` + **Gradle JDK = 17**）效果等价且不引入下载行为。若将来要固定到 21/25，再考虑加 toolchain 并同步配 `foojay-resolver-convention`。

### 7.2 签名配置

```properties
# keystore.properties（已在 .gitignore 中）
storeFile=../keystore/gohiking.jks
storePassword=***
keyAlias=gohiking
keyPassword=***
```

```kotlin
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
android {
    signingConfigs {
        if (ksProps.isNotEmpty()) {
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")   // 本地缺失时优雅跳过
        }
    }
}
```

| 规则 | 说明 |
| --- | --- |
| 仓库只放 `keystore.properties.example` | `keystore.properties`、`*.jks`、`*.keystore` 全部进 `.gitignore`（PRD 11 M0 已要求） |
| CI 签名 | release 签名走 GitHub Secrets（Base64 keystore + 三个口令），**不提交文件** |
| 无签名配置时 | `assembleRelease` 应能产出 unsigned APK 给贡献者验证编译，不报错 |

### 7.3 CI 流水线（GitHub Actions）

```yaml
name: CI
on:
  push: { branches: [main, develop] }
  pull_request: { branches: [main, develop] }

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4

      - name: 生成占位 local.properties（CI 无真实 Key，只跑编译与测试）
        run: |
          echo "AMAP_KEY_DEBUG=ci-placeholder" >> local.properties
          echo "AMAP_KEY_RELEASE=ci-placeholder" >> local.properties

      # ---- 静态检查 ----
      - run: ./gradlew ktlintCheck detekt

      # ---- 单元测试 + 覆盖率 ----
      - run: ./gradlew testDebugUnitTest koverXmlReport

      # ---- 门禁：Lint（含 HardcodedText / MissingTranslation）----
      - run: ./gradlew lintDebug

      # ---- 门禁：中英资源键对齐（F-I18N-09）----
      - run: ./gradlew checkStringKeys

      # ---- 编译 ----
      - run: ./gradlew assembleDebug

      - uses: actions/upload-artifact@v4
        with: { name: app-debug, path: app/build/outputs/apk/debug/*.apk }
      - uses: actions/upload-artifact@v4
        with: { name: reports, path: '**/build/reports/**' }
```

| 规则 | 说明 |
| --- | --- |
| 四条**门禁**（失败即红） | `ktlintCheck`、`detekt`、`lintDebug`（含两条 i18n 规则）、`checkStringKeys` |
| 单元测试失败 | 直接红，不允许 `continue-on-error` |
| PR 必须过 CI 才可合并 | 分支保护规则 |
| 构建时间目标 | 冷缓存 < 8 分钟 |

### 7.4 Lint 与 detekt 门禁配置

```kotlin
// app/build.gradle.kts（各模块同理）
android {
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "OldTargetApi")   // 不因「有新版本可用」红
        // 关键：这两条是 PRD F-I18N-41 要求的门禁，必须为 error
        error += setOf("HardcodedText", "MissingTranslation", "ExtraTranslation")
    }
}
```

| 规则 | 依据 | 级别 |
| --- | --- | --- |
| `HardcodedText` | `F-I18N-41` | **error** |
| `MissingTranslation` | `F-I18N-41` | **error** |
| `ExtraTranslation` | 补充：多余译文同样说明键不对齐 | **error** |
| `UnusedResources` | 补充 | warning |
| detekt `UnsafeCallOnNullableType` | §1.2 | error |

### 7.5 i18n 资源键对齐校验（`F-I18N-09`）

Lint 只能查「漏译」，查不出「文件级不齐」与「多余键」。补一个 Gradle 任务：

```kotlin
// core/resources/build.gradle.kts
val checkStringKeys by tasks.registering {
    group = "verification"
    description = "校验 values/ 与 values-zh-rCN/ 的字符串键完全对齐（F-I18N-09）"
    val defaultDir = layout.projectDirectory.dir("src/main/res/values").asFile
    val zhDir = layout.projectDirectory.dir("src/main/res/values-zh-rCN").asFile
    doLast {
        fun keysOf(dir: File): Map<String, Set<String>> =
            (dir.listFiles { f -> f.extension == "xml" } ?: emptyArray())
                .associate { f ->
                    f.name to Regex("""name="([^"]+)"""").findAll(f.readText())
                        .map { it.groupValues[1] }.toSet()
                }
        val en = keysOf(defaultDir)
        val zh = keysOf(zhDir)
        val problems = buildList {
            (en.keys + zh.keys).forEach { file ->
                val missing = (en[file] ?: emptySet()) - (zh[file] ?: emptySet())
                val extra = (zh[file] ?: emptySet()) - (en[file] ?: emptySet())
                if (missing.isNotEmpty()) add("$file 缺中文键: ${missing.sorted()}")
                if (extra.isNotEmpty()) add("$file 多余中文键（英文侧没有）: ${extra.sorted()}")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("i18n 资源键不对齐：\n" + problems.joinToString("\n"))
        }
        logger.lifecycle("i18n 资源键对齐检查通过（${en.size} 个文件）")
    }
}
tasks.named("check") { dependsOn(checkStringKeys) }
```

**另外两项人工检查（M4 执行，无法自动化）**

| 检查 | 做法 | 依据 |
| --- | --- | --- |
| `en-XA` 伪本地化 | Android Studio 切伪语言运行全流程，找出未走资源的硬编码文案 | `F-I18N-40` |
| 英文布局走查 | 英文模式逐页检查按钮撑破、文字截断、重叠 | PRD 9.7.6 验收要求 |

### 7.6 Git 规范

**`.gitignore` 必须包含**

```
local.properties
keystore.properties
*.jks
*.keystore
.idea/
*.iml
build/
.cxx/
# ⚠ 注意：**不要**在这里无脑排除 app/libs/*.aar。
#    若 T-01 最终选择「下载 aar 放 app/libs」的引入方式，一旦排除，
#    新克隆的仓库与 CI 将**直接编译不过**。二选一（见 §9.5-A4）：
#      (a) 确认可用 Maven 坐标 → 本行可保留
#      (b) 保留 aar 且确认可分发性 → 提交进仓库，**删掉本行**
#    若 aar 不可再分发、又没有 Maven 坐标 → 必须提供下载脚本或明确文档指引，
#    并让 CI 在缺失时给出可读的失败提示，而不是抛一句编译错误。
```

| 项 | 约定 |
| --- | --- |
| 分支 | `main`（可发布）、`develop`（集成）、`feature/<域>-<简述>` |
| 提交信息 | `feat: / fix: / docs: / refactor: / test: / chore:` + 中文简述，必要时在正文引用需求编号（如 `fix: segment 未断开 (F-REC-05)`） |
| 文档改动 | 改 PRD 必升版本号 + 补修订记录；改本文档同理（见 §10） |
| 提交前 | 本地跑一次 `./gradlew ktlintCheck detekt testDebugUnitTest`（可用 pre-commit 钩子） |

---

## 8. 测试计划

### 8.1 测试分层与职责

| 层 | 工具 | 占比目标 | 覆盖内容 |
| --- | --- | --- | --- |
| **L1 纯单元测试** | JUnit5 + Kotlin test | ~60% | 坐标转换、距离、海拔融合、阈值累加器、计步算法、抽稀、聚类、难度评级、提醒引擎、JSON/GPX 序列化、schema 迁移、单位换算、时间格式化、冲突判定 |
| **L2 数据层测试** | Room in-memory + Robolectric | ~20% | DAO 查询、事务回滚、批量插入性能、迁移测试、DataStore 读写 |
| **L3 UI 测试** | Compose UI Test | ~10% | 关键页面的状态渲染（空状态、暂停态、无气压计角标、英文长文案布局） |
| **L4 仪器/集成测试** | AndroidX Test（真机） | ~10% | 前台服务生命周期、定位与传感器真实回调、SAF 导出与导入、崩溃恢复 |
| **L5 手工验收** | 人工 | — | PRD 各节验收标准（§8.6） |

> **L4 尽量少**：真机测试慢且不稳定。凡能用替身验证的逻辑一律下沉到 L1（这也是 §2.3「所有平台能力必须可替换」的原因）。

### 8.2 L1 单元测试用例清单

§4.11 列出 **30 个用例 = 29 个必写 + 1 个 ⚠ TODO(M0)「基准点比对」**，此处不重复。补充：

| 模块 | 用例 | 断言 |
| --- | --- | --- |
| 导出 | `ExportJson_字段完整` | 生成的 JSON 含 `crs` / `altitudeSource` / `stepSource` / `schemaVersion`，且 `trackPoints.fields` 与 `values` 每行长度一致（`F-IO-08`） |
| 导出 | `ExportJson_紧凑编码可还原` | 导出 → 解析 → 与原始点列表逐点相等（容差 1e-6） |
| 导出 | `ExportZip_结构正确` | ZIP 内含 `manifest.json` / `README.txt` / `trips/<id>.json`（PRD 7.4） |
| 导出 | `ExportZip_checksums正确` | manifest 里的 SHA-256 与实际文件一致 |
| 导出 | `Export_FilenameDedup` | 同名文件 → 自动追加 `_1`（`F-IO-13`） |
| 导入 | `ImportZip_路径穿越被拒` | 含 `../../evil.txt` 的 ZIP → 拒绝该条目，**临时目录内无任何写出**（`F-IO-60`） |
| 导入 | `ImportZip_ZipBomb被拒` | 声明解压后 > 2GB 或条目 > 10000 → 拒绝（`F-IO-61`） |
| 导入 | `ImportZip_超限确认` | ZIP > 500MB → 预览页先提示体积与预估耗时，确认后才导入（`F-IO-64`） |
| 导入 | `ImportZip_流式不OOM` | 1000 条记录的 ZIP 导入，峰值堆 < 128MB（`F-IO-62`） |
| 导入 | `Import_幂等` | 同一 JSON 导入两次，第二次全部 skip，记录数不变（`F-IO-31`） |
| 导入 | `Import_冲突四策略` | skip / overwrite / duplicate / ask 各自行为符合 PRD 6.8.4 |
| 导入 | `Import_疑似重复不自动合并` | `name`+`startTime` 相同但 id 不同 → 标记 suspected，**未合并**（`F-IO-41`） |
| 导入 | `Import_单条失败不影响其他` | 3 条里 1 条损坏 → 成功 2 / 失败 1，成功的已入库（`F-IO-28`） |
| 导入 | `Import_校验和不匹配跳过` | 跳过该文件计入失败报告，继续处理其余（PRD 7.5） |
| 导入 | `Import_crs缺失按WGS84` | 无 `crs` → 按 WGS-84 转换并提示（PRD 7.5） |
| 迁移 | `Migration_1_2_数据不丢` | 用旧 schema 建库写入 → 迁移 → 记录数与关键字段一致 |
| 单位 | `UnitFormatter_公里英里` | 8.2345km ↔ 5.1165mi（±0.001） |
| 单位 | `UnitFormatter_米英尺` | 943.7m ↔ 3096.1ft（±0.1） |
| i18n | `Difficulty_本地化` | 枚举 `MODERATE` 在 zh/en 下分别渲染「中等」/「Moderate」 |
| i18n | `MarkerLabel_渲染不读库值` | 库中 `label` 为英文时，中文界面仍渲染中文（PRD 9.7.4） |

### 8.3 L2 数据层测试

| 用例 | 断言 |
| --- | --- |
| `TrackPointDao_BatchInsertPerformance` | 10800 点插入 < 1.5 秒（`chunked(500)` + 单事务，§3.4） |
| `TripDao_ObserveSummary` | 汇总的 count/sum 与插入数据一致 |
| `SaveFinishedTrip_原子性` | 注入一个插入失败 → 全部回滚，trip / marker 均无残留、recording_state 未清（点已随记录期落库，不参与本事务） |
| `DeleteTrip_Cascade` | 删除 trip 后，其 track_point / marker / media_ref 全部清空 |
| `IndexQuery_UsesIndex` | `EXPLAIN QUERY PLAN` 命中 `track_point(tripId, segmentIndex, seq)` 索引 |
| `RecordingStateDao_UpsertSingleRow` | 反复 UPSERT 后表内恒为 1 行 |
| `SettingsRepository_Defaults` | 首次读取全部返回 §3.5 表里的默认值 |
| `SettingsRepository_AltitudeThresholdClamp` | 无气压计时写入 10 → 实际生效 30（`F-REC-63`） |

### 8.4 L3 Compose UI 测试

| 页面 | 用例 | 断言 |
| --- | --- | --- |
| P-08 记录页 | 暂停态渲染 | 显示「已暂停」浮层，数据面板变灰（`F-REC-17`） |
| P-08 记录页 | 无气压计角标 | `altitudeLowPrecision=true` → 海拔旁出现「GPS 估算」（`F-REC-61`） |
| P-08 记录页 | 步数不可用 | `steps=-1` → 显示「—」（`F-REC-73`） |
| P-08 记录页 | 停止长按 | 短按不触发 `Stop`；长按 1.5s 后触发（`F-REC-06`） |
| P-14 设置 | 英文长文案 | `en` 下「备份包含媒体文件」行不截断、不重叠（PRD 9.7.6） |
| P-10 列表 | 空状态 | 空库显示引导文案与插图（`F-HIS-10`） |
| P-17 导入预览 | 坐标系提示 | 文件是 WGS-84 时出现转换提示（`F-IO-26`） |
| P-18 导入结果 | 失败清单 | 每条失败含文件 + 条目 + 原因（`F-IO-34`） |
| P-01 权限页 | 按钮层级 | 只有「全部允许」是实心主按钮，其余为描边（原型已修正，须保持一致） |

### 8.5 L4 仪器测试（真机）

| 用例 | 前置 | 断言 |
| --- | --- | --- |
| `ForegroundService_SurvivesBackground` | 授予后台定位 | 切后台 10 分钟后回到 App，轨迹**无断点**（`F-REC-51`，PRD 6.3.9） |
| `PauseResume_NoStraightLine` | — | 暂停 5 分钟再继续 → 轨迹中间**无直线**（`F-REC-05`） |
| `CrashRecovery` | 记录中 `adb shell am force-stop` | 重开后提示恢复，恢复后轨迹与已落库点一致（`F-REC-09`） |
| `AltitudeAlert_Integration` | 阈值 100m | 注入海拔序列爬升 350m → 出 3 个 `ALERT_ASCENT`（PRD 6.6.6） |
| `DistanceAlert_Integration` | 阈值 100m | 注入 1000m 轨迹 → 10 个 `ALERT_DISTANCE` |
| `ExportImport_RoundTrip` | 42 条记录 | 导出 ZIP → 清库 → 导入 → 记录数/点数/标记数/统计值**全部一致**（PRD 6.8.7） |
| `SafExport_NoPermission` | 未授媒体权限 | 导出仍成功（导出不依赖媒体权限） |
| `MediaLocation_Offset` | 已知 GPS 的测试照片 | 照片在高德地图上的落点与实际位置偏差 < 30m（PRD 6.4.6） |
| `Battery_3h` | 真机 3 小时 | 耗电 < 25%（PRD 9.2）；此项为**长跑测试**，每周跑一次 |

### 8.6 手工验收清单（映射 PRD 验收标准）

| 编号 | 来源 | 验收内容 |
| --- | --- | --- |
| MA-1 | PRD 6.2.7 | 真实有路网起终点 3 秒内返回 ≥1 条线路且 3 条可对比；无路网区域**自动引导到手动打点**，不空白不死循环 |
| MA-2 | PRD 6.2.7 | 手动打 5 个点 → 序号正确、连线正确、总距离正确；「原路返回」后两段几何对称 |
| MA-3 | PRD 6.3.9 | 锁屏 10 分钟轨迹完整；暂停恢复无直线；爬升 100m 出海拔提醒点；登顶红旗海拔正确；杀进程可恢复；3 小时耗电 < 25% |
| MA-4 | PRD 6.4.6 | 500 张带位置照片 5 秒内扫描聚合完成；气泡数与实际一致；缩放正确合并/散开；缩略图条滑动地图跟随；照片点无偏移 |
| MA-5 | PRD 6.5.3 | 列表排序正确；详情页轨迹与标记完整；海拔曲线与数据一致、游标联动；登顶点正确切分上下山 |
| MA-6 | PRD 6.6.6 | 距离阈值 100m 走 1000m → 10 次提醒；海拔阈值 100m 爬 350m → 3 次；关语音仍打点；关总开关**不打任何点**；暂停期间不计距离 |
| MA-7 | PRD 6.8.7 | 跨设备导入数据完全一致；`jq` 能解析出全部点与标记；同 ZIP 导两次幂等；恶意 ZIP 被拒且无写出；`schemaVersion 2.0` 提示升级不崩溃；导入 1000 条不 OOM；3 小时记录导出 ZIP < 5MB |
| MA-8 | PRD 9.3 | **全程飞行模式**完成一次记录 → 正常记录、正常保存、正常导出 |
| MA-9 | PRD 11 总标准 | 完整走一次真实爬山：计划 → 记录 → 提醒 → 保存 → 导出 → 回顾，全流程无阻断；无内存泄漏、无 ANR |
| MA-10 | PRD 9.7.6/9.7.8 | `en-XA` 硬编码清零；英文模式无截断/撑破/重叠 |

### 8.7 覆盖率目标与工具

| 范围 | 目标（行覆盖） | 说明 |
| --- | --- | --- |
| `core/location/crs`、`altitude`、`step`、`core/common/geo` | **≥ 90%** | 算法层，错一点就是几百米偏移或错误爬升 |
| `core/data/io`（导入导出） | **≥ 85%** | 安全边界 + 数据完整性，PRD 重点 |
| `core/data`（整体）、`core/database` | **≥ 70%** | |
| 全局 | **≥ 60%** | Compose 界面代码不计入分母（用 L3 覆盖） |

工具：Kover（`koverXmlReport`），CI 输出到 PR 评论。**覆盖率下降超过 3% 视为回归**，需说明理由。

### 8.8 测试替身（Fakes）清单

必须提供的 Fake（放 `*/src/test/` 或 `src/debug/`，禁止用 mock 框架模拟传感器时序）：

| Fake | 能力 |
| --- | --- |
| `FakeClock` | 可控时间推进，`advanceBy(Duration)` |
| `FakeIdGenerator` | 确定性 UUID 序列，导出结果可比对 |
| `FakeLocationProvider` | 回放预设 `List<LocationFix>`（含时间戳序列） |
| `FakePressureSensor` | 回放气压序列（含噪声、跳变） |
| `FakeStepSensor` | 回放步数序列（含计数器回退场景） |
| `FakeElevationRepository` | 按坐标返回预设高程 / 模拟不可用 |
| `FakeAltitudeFuser` | 回放海拔序列（含漂移、跳变、手动校准场景）；AltitudeFuser 有逐场状态，必须可替身 |
| `FakeAlertEngine` | 按预设累计值返回标记草稿，断言触发/不重复触发逻辑 |
| `FakeTtsEngine` | 记录播报文本，供断言（不真的发声） |
| `FakeAmapRouteSearch` | 返回预设候选线路 / 模拟失败与超时 |

---

## 9. 需求映射与待办

### 9.1 需求域 → 模块 → 里程碑

| 需求域 | 主要模块 | 里程碑 | 关键设计章节 |
| --- | --- | --- | --- |
| `F-MAP` 地图基础 | `:core:map`、`:feature:home` | M0 | §6.4 |
| `F-PLAN` 计划线路 | `:core:map`（RouteSearch 封装）、`:feature:plan`、`:core:elevation` | M2 | §4.7、§4.8、§6.4 |
| `F-REC` 登山记录 | `:core:location`、`:core:data`（RecordingSession）、`:feature:recording` | M1 | §4.1–§4.5、§6.1–§6.3、§6.6 |
| `F-MEDIA` 照片视频 | `:core:data`（MediaRepository）、`:core:map`、`:feature:media` | M4 | §4.6、§3.1.1（`media_index`） |
| `F-HIS` 运动记录 | `:core:data`、`:feature:history` | M1 / M4 | §3.2、§4.5 |
| `F-ALERT` 提醒 | `:core:data`（AlertEngine）、`:core:location`（TTS） | M3 | §4.9、§6.5 |
| `F-SET` 设置 | `:core:datastore`、`:feature:settings` | M3 | §3.5 |
| `F-IO` 导入导出 | `:core:data`（Export/ImportEngine） | M3 | §3.3、§3.4、§8.2 |
| `F-I18N` 国际化 | `:core:resources`、全模块 | M0 建结构 / M4 校对 | PRD 9.7、§7.4、§7.5 |
| 权限清单（PRD 10） | `:feature:home`（P-01）、各模块按需 | M0 / 各里程碑 | §1.5、§6.1–§6.3 |

> **P2 暂缓登记（DoD-1 的边界）**：五条 P2 需求——`F-HIS-29`、`F-ALERT-31`、`F-ALERT-41`、`F-MEDIA-42`、`F-IO-32`——在本文档暂无实现落点，属**有意暂缓**（v1.0 不做），不是遗漏；M4 收尾时统一复核。DoD-1 的「逐条可追」对这五条以本登记为准。

### 9.2 ⚠ TODO(M0) 实测与查证清单（**动工前必须清空**）

> **2026-09-19 复评：按「能不能先在 PC 上做掉」重新分组。**
>
> 原 21 项（T-01 ~ T-21）＋ 9 项 B 类参数，共 **30 项**。原则：**能在 PC 上查清的一律先查清**。
> **§9.2.1（15 行）** 已实测完毕并回填正文——覆盖 **12 个 T 项**（T-01/05/06/07/10/11/13/14/15/16/17/19）与 **5 个 B 项**（B1/B2/B3/B4/B9）；
> 其中 **T-14 / T-15 / T-17 三项各还剩一半**（分别缺「版本锁定」「实际返回几条」「release 运行期」），这半截连同 T-18 / T-09 落在 **§9.2.2（6 行）**；
> **§9.2.3（9 行）** = 必须真机（T-02/03/04/08/12 + B5~B8），无捷径。
> 验证脚本在 **`tools/m0-verify/`**，可复跑。
>
> 分组的意义：**别再把能在电脑上查清的事留到真机阶段**——真机调试的成本远高于跑一个脚本。

#### 9.2.1 ✅ 已在 PC 端完成（无需真机、无需 Key、无需构建）

| 编号 | 事项 | 实测结论（可直接当事实用） | 回填位置 |
| --- | --- | --- | --- |
| T-01 | 高德 SDK 引入方式与可用版本 | **公网 Maven 只发布 `jar`、没有 `aar`**（`3dmap-10.0.600.aar` → 404，`.jar` → 18.0 MB 正常）。四个坐标都可解析：`3dmap:10.0.600`、`location:6.4.9`（另有 11.2.100 新线）、`search:9.7.1`、`navi-3dmap:10.0.800_3dmap10.0.800`。**地图/搜索/导航三个包的公网版本都停在 2024 年**，比官网下载页落后。→ 两个后果：jar 不带清单（权限与 Key 的 `meta-data` 要自己声明）、官网 aar 没有 Maven 坐标 | §1.3 |
| T-05 | 高德是否自带聚合 `ClusterOverlay` | **没有**。3dmap 10.0.600 的 1174 个类里没有任何 `Cluster*` → §4.6 的自研网格聚类是唯一选择，不是备选 | §4.6 |
| T-06 / T-16 | `updatePrivacy*` 的确切签名 | **与 §1.5 写的完全一致**：`MapsInitializer.updatePrivacyShow(Context, boolean, boolean)`、`updatePrivacyAgree(Context, boolean)`；`AMapLocationClient` 的同名方法签名也一致。**两处调用写法可以照写，不用改** | §1.5 |
| T-13 | 定位模式常量拼写 | **`Hight_Accuracy` 确实是高德原文拼写**，但它是**枚举常量**而非字符串：`AMapLocationClientOption.AMapLocationMode.Hight_Accuracy`（同级还有 `Battery_Saving`、`Device_Sensors`），经 `setLocationMode(...)` 传入 | §6.2 |
| T-17 / B9 | 是否内置 consumer-proguard 规则 | **没有**。jar 内只有 `META-INF/MANIFEST.MF`，无任何 proguard 规则 → **必须手写 keep 规则**（§7.1 已给初稿，且必须 release 实跑验证） | §7.1 |
| T-19 | AGP / Kotlin 弃用 API 现状 | `kotlinOptions` **自 Kotlin 2.0 起弃用** → 改 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` 或 `jvmToolchain(17)`；`resourceConfigurations` **自 AGP 8.8 起弃用** → 改 `androidResources { localeFilters }`。**叠加 §7.4 的 `warningsAsErrors = true`，写错会直接让 CI 变红** | §7.1 |
| T-07 | 高程服务连通性 / 批量 / 精度 | 三个候选**都能连通**：Open-Elevation（单点 1.3–4.2 s，**200 点一次 ≈ 1 s**）、Open-Meteo（1.2–1.6 s，逗号批量）、OpenTopoData（`srtm90m`/`srtm30m` 通、`aster30m` 超时）。**⚠ 但 DEM 90 m 在陡峭山峰系统性低估**：泰山玉皇顶实测 1532.7 m / SRTM 报 1479 m，黄山光明顶实测 1860 m / 报 1725 m → 爬升评估必须标「估算」（落地为 PRD `F-PLAN-46`） | §4.10 |
| T-10 | Vico v2（beta）是否可用 | artifact 存在（`2.0.0-beta.2` 可解析），**但 v3 已是稳定线（最新 3.3.1）** → **不必再准备 MPAndroidChart 兜底**，直接用 v3 | §1.3 |
| T-11 | 地图 SDK 与定位 SDK 的版本兼容 | **导航 SDK 的版本号直接编码了配套地图 SDK 版本**（`10.0.800_3dmap10.0.800`）→ 引入导航 SDK 时两者必须成对锁定；定位 SDK 与地图 SDK 相互独立 | §1.3 |
| T-14 / B1 | 版本号是否真实存在 | §1.3 那 20 个「凭经验填的」版本号 **全部真实存在**（含三个插件 marker）。但它们整体是 **2024 年末~2025 年初**的版本，仓库最新已到 AGP 9.4.1 / Kotlin 2.4.20 / Compose BOM 2026.09.00 → **需要一次「旧稳定基线 vs 新版本」的决策**，锁定留到 M0 | §1.3 |
| B2 | GCJ→WGS 迭代收敛次数 | `eps=1e-9` → **≤4 次**；`1e-8` → ≤4；`1e-12` → **≤6**。还原精度：`1e-9` 时中位 0.003 mm。**单次近似反解误差 0.59–3.40 m，不可用** | §4.11 |
| B3 | GCJ-02 偏移量区间 | 中国大陆 8928 点网格采样：**40.9 ~ 686.3 m**，中位 430.5，均值 407.1，P5–P95 = 161 ~ 620 m；**1.0% 的点偏移 < 100 m**。原写「300–800 m」与后来的「100–1000 m」**都不成立** | §4.11、§4.2 |
| B4 | 用 GCJ-02 算距离的误差 | 1–5 km 段：中位 **0.062%**、P95 0.31%、**最大 0.53%**；相邻点 100–500 m 段最大 0.50%；跨城 ~300 km 段最大 0.20%；绝对误差 ≤ 9.8 m / 5 km。**「< 0.5%」不成立，按 ≤1% 设计** | §4.2、§4.11 |
| T-15（API 面） | 步行多备选到底有哪些官方能力 | ① **`RouteSearchV2.WalkRouteQuery.setAlternativeRoute(int)` + `AlternativeRoute.ALTERNATIVE_ROUTE_ONE/TWO/THREE`**（已确认）；② `RouteSearch.WALK_MULTI_PATH` 与两个构造签名 `(FromAndTo)` / `(FromAndTo, int)`（已确认）；③ 步行 Web API **v5** 的 `alternative_route`（官方文档明确列出，**v3 没有**）；④ 导航 SDK `TravelStrategy.SINGLE/MULTIPLE` + `getNaviPaths()` + `setMultipleRouteNaviMode(boolean)`（已确认） | §4.8、§4.8.1、PRD 6.2.2 |
| 附带 | PRD 6.2.1 搜索选点的类到底在哪 | **`PoiSearch` / `PoiSearch.Query` / `PoiSearch.SearchBound` / `Inputtips` / `InputtipsQuery` / `PoiItem` 全在 `search` 包里**（`services.poisearch` / `services.help` / `services.core`），**不在 3dmap 里** → §1.3 原先漏了 `com.amap.api:search` 这个依赖，已补 | §1.3 |

> 复跑方式见 `tools/m0-verify/README.md`；这些脚本只发 HTTP 请求、只解包下载的 jar，不改仓库任何内容。

#### 9.2.2 🔑 PC 端可验证，但还缺一样东西（Key / 构建产物）

| 编号 | 事项 | 缺什么 | 拿到后验证方式 |
| --- | --- | --- | --- |
| T-15③ | 步行 Web API `alternative_route=3` **实际**返回几条、方案差异大不大 | **高德 Web 服务 Key**（本人申请，不入库） | 取 2~3 组**山区**起终点（如香山—八大处、泰山红门—玉皇顶），分别用 `alternative_route` 不传 / =2 / =3 各请求一次，记录 `count` 与各 `paths[].distance` 的离散度 |
| T-15①④ | SDK 多路径**实际**返回几条（V2 `ALTERNATIVE_ROUTE_THREE` / `TravelStrategy.MULTIPLE`） | M0 骨架（要能跑 APK） | 同上坐标组，真机打 Log 输出 `getPaths().size()` 与各方案距离/时长 |
| T-14 | 依赖版本最终锁定（**含 AGP / Gradle / JDK 三者组合**，见 §7.1.1） | M0 骨架 | `./gradlew :app:dependencies` 全绿后回填 §1.3，并把 AGP 版本号代入 T-19 的结论选 `localeFilters` 还是 `resourceConfigurations`；同时按 §7.1.1 锁定「运行 Gradle 的 JDK」 |
| T-18 | 限定 locale 后 `en-XA` 伪本地化是否仍可切换 | debug 变体构建产物 | debug 包切 `en-XA` 看硬编码文案是否变形；不可用则改为「仅 release 限定、debug 放开」 |
| T-17（运行期） | release 混淆后高德全流程是否正常 | release 包 | 跑完整流程：地图加载 → 定位 → 搜索选点 → 线路规划 → 记录保存，**不能只看编译通过** |
| T-09 | 坐标转换的**权威**基准点 | 真机高德或 Web 服务坐标转换 Key | 采 8~10 个固定点（含山区），把 高德返回值与 `tools/m0-verify/11_gcj02_numbers.py` 的算法值逐点比对；偏差稳定则说明算法一致，可作为单测基准写入测试数据 |

#### 9.2.3 📱 必须真机（PC 无法替代，无捷径）

| 编号 | 事项 | 为什么 PC 做不了 |
| --- | --- | --- |
| T-02 | `setMapLanguage` 是否真的把地图地名切成英文 | 需要地图渲染；且受高德数据源限制，可能**功能正常但数据不支持** |
| T-03 | 选点态点底图 POI 时 `OnMapClickListener` 是否同时触发 | 需要真机地图交互 + Log 观察两个回调顺序 |
| T-04 | 山区/低缩放级别下的 POI 覆盖度 | 需要真实山区的地图数据表现（决定 `F-PLAN-08` 是否保持 P1） |
| T-08 | 气压计 / 计步传感器机型覆盖 | `adb shell dumpsys sensorservice` + 记录页实测，需旗舰/中端/入门各 ≥1 台 |
| T-12 | `getMslAltitudeMeters()` 机型覆盖率 | API 34+ 真机抽查 |
| B5 | 「10800 点插入 < 1.5 s」「10000 点抽稀 < 30 ms」 | 是本文档自拍的目标值，必须真机基准测试；**达标与否不作为验收门禁** |
| B6 | 加速度计滤波器阶数（2 阶 Butterworth）与自适应阈值系数（峰值 0.6 倍） | 需要真机步行数据（平地/上坡/下坡/手持晃动）才能核定 |
| B7 | 海拔漂移修正系数 `k = 0.1 / 0.15` | 需要在真机海拔序列上验证收敛性与跟随速度 |
| B8 | 聚合单元格 60 dp、DEM 采样间距 | 60 dp 是设计取值；DEM 采样间距**已发现与数据分辨率不匹配**（GLO-90 是 90 m 栅格，按 50 m 取点等于过采样、拿不到更多信息）——M0 起应改为**按数据源分辨率自适应（≥ 数据栅格边长）**，最终取值随数据源确定 |

### 9.3 ＋设计补充登记（PRD 未提出、本文档补充）

| # | 补充项 | 理由 | 是否影响 PRD |
| --- | --- | --- | --- |
| D-01 | 新增表 `recording_state` | PRD 7.1 无表承接 `F-REC-09` 与 PRD 9.6 的「每 10 秒持久化」 | 不影响需求；PRD 7.1 可考虑在 v1.9 补一行 |
| D-02 | 新增表 `media_index` | PRD 7.1 的 `media_ref` 是「记录关联引用」，无法承接 `F-MEDIA-09` 的全库扫描缓存 | 同上 |
| D-03 | `track_point` 增列 `distanceM` | `F-HIS-23/25` 需要「该点为止的累计距离」，否则每次渲染都要 O(n) 重算 | 同上 |
| D-04 | 明确「统计累加器（3m/10m）」与「打点累加器（100m）」是两个独立实例 | PRD 4.4.4 与 `F-ALERT-21` 的阈值不同，PRD 未显式区分 | 实现澄清，不改需求 |
| D-05 | 全部算法参数集中定义（滤波系数、抽稀容差、DEM 采样 50m 等） | PRD 只给思路，不给可实现参数 | 实现补充 |
| D-06 | 模块依赖方向规则（core 不得依赖 feature 等） | PRD 4.6 只给模块清单，未给依赖约束 | 架构补充 |
| D-07 | `RecordingSession` 单一可变状态源架构 | 避免 Service / ViewModel / 通知各自维护计时器与距离 | 架构补充 |
| D-08 | 高德定位主 / Fused 备的自动切换（30 秒无回调） | PRD 4.1 提到二者，但未定义切换策略 | 实现补充 |
| D-09 | 候选线路的偏移量公式与串行请求策略 | PRD 6.2.2 只给思路 | 实现补充（**已降级为兜底策略**，见 §4.8.1） |
| ~~D-10~~ | ~~debug 变体 `applicationIdSuffix` 与独立 debug Key~~ | **已废弃（2026-09-20）**：改为单 Key 方案——debug 不加 `.debug` 后缀，debug/release 共用 `com.gohiking.app`，一个 Key 绑定双 SHA1（见 §7.1.1 末注） | — |
| D-11 | `gohiking://debug/pXX` 直达深链（debug-only） | 便于对照 `prototype/` 与真机做回归（原型已有 `#pXX`） | 开发效率补充 |
| D-12 | **线路爬升评估标注为「估算」**：DEM 在陡峭山峰**系统性低估**海拔（实测泰山玉皇顶低 54 m、黄山光明顶低 135 m，是偏差不是随机误差），评估值只用于**候选之间排序**，不作为绝对值展示 | 实测发现（`tools/m0-verify/12_elevation_services.py`）；PRD 6.1 只写了「不可用时显示『—』」，**没写「可用但数值偏低」时怎么办** | **✅ 已落地 PRD**：`F-PLAN-46`（PRD v1.13 新增，P1） |

| D-13 | `media_ref` 增列 `fileName` / `note`、`planned_route` 增列 `source`、`trip` 增列 `avgPaceSecPerKm` | 导出 JSON（PRD 7.3）引用了这些字段，但 PRD 7.1 的表无对应列——导出实现时要么临时加列（Room 版本 +1）、要么破坏 schema（审阅 H-5 / P9） | **影响 PRD：✅ 已落地**（PRD v1.14 在 7.1 补列） |
| D-14 | `planned_waypoint` 列 `order`（PRD 7.1）落库为 **`orderIndex`** | `order` 是 SQLite 关键字，Room 手写查询里每次都要转义、易踩坑；实体属性 `orderIndex` + `@ColumnInfo(name = "orderIndex")`。导出 JSON 字段名不受影响（按 7.3 schema 独立命名） | 实现补充（2026-09-20，M1 数据层落地时） |
| D-15 | **不引入独立的 `amap-location` 依赖**：定位能力统一来自 `amap-3dmap`（10.0.600 内嵌完整定位实现，`AmapLocationClient` 可直接使用） | 单独引入 location 6.4.9 会与 3dmap 内嵌实现产生**重复类冲突**（`checkDuplicateClasses` 直接失败，实测 col/3l 602 类重复）；libs.versions.toml 中 `amap-location` 已标记 deprecated | 实现补充（2026-09-20，M1 记录切片落地时） |
| D-17 | **高德 SDK 改用官方三合一 Maven 包 `com.amap.api:3dmap-location-search:10.0.700_loc6.4.5_sea9.7.2`**（3dmap 10.0.700 + location 6.4.5 + search 9.7.2） | 单独引 `3dmap:10.0.600` + `search:9.7.1` 会在 `checkDuplicateClasses` 失败：两个 jar 各自内嵌 `com.amap.apis.utils.core.api.{NetProxy, AMapUtilCoreApi}` 公共库（实测），且 Gradle 无法按类排除、社区验证 `exclude core-api` 无效；官方三合一是唯一无冲突的 Maven 途径。版本取与 M0 基线最接近的组合（地图/搜索各 +1 patch 号，RouteSearchV2/Inputtips API 面不变）。替代方案（自切 jar 去重）被否：维护成本高且破坏 maven 版本基线 | 实现补充（2026-09-20，M2 选点切片落地时） |
| D-16 | **传感器类型常量用 `Sensor.TYPE_*`，不是 `SensorManager.TYPE_*`**：气压计探测写法为 `getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null` | `TYPE_PRESSURE` 等常量定义在 `android.hardware.Sensor` 上（对照官方 platform-35 android.jar 逐字节核验）；`Context.PRESSURE_SERVICE` 是隐藏 SystemApi，公开代码拿 `SensorManager` 应走 `SENSOR_SERVICE` / `getSystemService(Class)` | 实现补充（2026-09-20，M1 记录切片落地时） |

**登记规则**：任何实现层新增项都要在此登记。若某项实质上改变了用户可感知的行为，**必须先回改 PRD**，不能只登记在这里。

### 9.4 里程碑完成定义（DoD）

每个里程碑收尾必须全部满足：

| # | 条件 |
| --- | --- |
| 1 | 该里程碑对应的 PRD 需求已全部实现，且**在本文档 §9.1 的映射表里逐条可追** |
| 2 | §8.2 / §8.3 中与该里程碑相关的测试用例**全部通过** |
| 3 | 覆盖率不低于 §8.7 目标，且无下降回归 |
| 4 | CI 四道门禁全绿（ktlint / detekt / lint / checkStringKeys） |
| 5 | 该里程碑涉及的 PRD 验收标准（MA-* 与各节验收）已手工走通，**结论记入 `docs/` 下的里程碑验收记录** |
| 6 | 新增的 §9.2 待办已闭环：要么验证完成，要么转为明确的技术决策并记录 |
| 7 | 本文档中与该里程碑相关的 `[待定]` 已拍板，`⚠ TODO` 已回填 |

---

### 9.5 未确定但被当作已确定的事项（**动手前必读**）

> 本节是 2026-09-19 对全文做的一次专项自查结果。判断标准只有一条：**这句话是查证过的，还是我猜的？**
> A 类已在本文档内修掉；B 类已把「断言」改写成「待验证」；C / D 类无法在本文档内解决，已分别落到 PRD 修订需求与 §9.2 的 TODO 项。

#### A 类：文档内部矛盾与缺失（可直接修，**已修**）

| # | 位置 | 问题 | 处理 |
| --- | --- | --- | --- |
| A1 | §3.1.1 vs §3.2 | `media_index` 定义了 `indexedAt`，但 §3.2 的 `indexFingerprint()` 查的是 `dateModifiedMs` —— **该列根本不存在**，照写会编译不过 | 已补列 |
| A2 | §3.1.1 vs §3.2 | `(mediaStoreId, mediaType)` 只声明为**唯一索引**，而 §3.2 用 `@Upsert` —— Room 的 `@Upsert` 按**主键**判定冲突，单靠唯一索引会抛约束异常 | 已改为复合主键，并补上理由 |
| A3 | §4.10 vs §3 | §4.10 构造函数引用 `ElevationCacheDao`，§2.3 还说它「内含 LruCache 与 DB 缓存」，但全篇**没有定义高程缓存表与 DAO** | 已补 `elevation_cache` 表与 `ElevationCacheDao` |
| A4 | §7.6 vs §1.3 T-01 | `.gitignore` 排除 `app/libs/*.aar`，而 T-01 又把「下载 aar 放 app/libs」列为候选引入方式 —— **二者互斥**：一旦排除，新克隆与 CI 直接编译不过 | 已改为条件化说明，并把「T-01 必须闭环」写成硬要求 |
| A5 | §5.4 vs §5.2 | `PlanPick` 路由只带 `routeId`，但 P-03 需要知道当前在选起点还是终点（`PlanPickViewModel.pickTarget`） | 已补 `target` 参数 |

#### B 类：把猜测写成了事实（**已改为待验证**）

| # | 位置 | 原文怎么写的 | 风险 | 处理 |
| --- | --- | --- | --- | --- |
| B1 | §1.3 | 列出一整套依赖版本号（AGP 8.7.3 / Kotlin 2.1.0 / Compose BOM 2024.12.01 / Hilt 2.53.1 / Room 2.6.1 / Vico 2.0.0-beta.2 / KSP 2.1.0-1.0.29 …） | **全部是凭经验填的**，没核对是否真实存在、是否互相兼容 | **✅ 已核实（2026-09-19）**：20 个版本号**全部真实存在**，但整体偏旧（最新 AGP 已 9.4.1、Kotlin 2.4.20）；Vico 已改 3.3.1。详见 §9.2.1 |
| B2 | §4.1 | 代码注释「收敛快，**实测** < 4 次即达 EPS」 | **没人测过**，却写成了实测结论 | 改为「预期」，并指向单测断言 |
| B3 | §4.11 | 断言 GCJ-02 偏移「**300–800 m**」 | 该区间未采样验证 | **✅ 已采样实测（2026-09-19）**：真实区间 **40.9–686.3 m**（中位 430.5，1% 的点 < 100 m）。单测边界与说明已按实测值改写为 **30–800 m**；原「100–1000 m」的放宽值也已作废 |
| B4 | §4.2 | 「差分距离受影响 **< 0.5%**」 | 未实测 | **✅ 已实测（2026-09-19）**：最大 **0.53%** → 「< 0.5%」**不成立**；已改为「中位 0.062%、最大 0.53%，按 ≤1% 设计」 |
| B5 | §3.4 / §4.5 | 「10800 点插入 **< 1.5 秒**」「10000 点抽稀 **< 30ms**」 | 这两个性能指标是**本文档自己拍的**，不是 PRD 要求，也没有基准数据 | 视为「待基准测试确定的目标值」——达标与否本身不作为验收门禁，PRD 9.1/9.2 的指标才算 |
| B6 | §4.4 | 加速度计算法「自适应阈值 = 预热期峰值 **0.6 倍**」「带通 **二阶** Butterworth」 | PRD 4.5.4 只给了 0.5–3Hz 与「低通去重力」，**滤波器阶数与阈值系数是本文档补的**，无真机数据支撑 | 已加注：仅当起点，须用真机步行数据核定 |
| B7 | §4.3 | 海拔漂移修正系数 `k = 0.1`（有气压计）/ `0.15`（无气压计） | 自创参数，未在真机海拔序列上验证过收敛性与跟随速度 | 同上，标注为待核定 |
| B8 | §4.6 / §4.7 | 聚合单元格「60dp」、DEM 采样间距「50m」 | 自创参数 | 60 dp 仍为可调设计值；**DEM 采样间距已发现问题**：GLO-90 是 90 m 栅格，按 50 m 取点是过采样，应改为按数据源分辨率自适应（见 §9.2.3 B8） |
| B9 | §7.1 | 注释「高德 SDK 官方已内置 consumer rules，一般无需手写」 | **未查证** | **✅ 已证伪（2026-09-19）**：jar 内无任何 proguard 规则 → **必须手写**；§7.1 已补 keep 规则初稿 |

#### C 类：PRD 层面未定义或自相矛盾（**已于 PRD v1.11 全部裁定并落地**）

| # | 问题 | 具体冲突 / 空白 | 影响 |
| --- | --- | --- | --- |
| C1 | **`trip.status` 取值两处不一致** | PRD 6.3.1 的状态机是 `IDLE / RECORDING / PAUSED / STOPPED`（保存或丢弃后回到 IDLE）；PRD 7.1 表里却是 `RECORDING/PAUSED/FINISHED`。**一条已保存的记录在库里到底是什么值？** | 本文档 §3.2 按 `FINISHED` 写（照抄 7.1），但这等于**替 PRD 做了裁决**。若不澄清，查询「所有已完成记录」的条件就无人能确认 |
| C2 | **`caloriesKcal` 没有计算公式** | PRD 7.1 定义了字段、`F-HIS-22` 要求显示，但 PRD 全文没给算法 | 一个已定型字段配一个不存在的算法，无法实现也无法验收 |
| C3 | **平均速度 / 配速无定义** | `avgSpeedMps` 是 `distance / movingDuration` 还是 `distance / duration`（含暂停）？`avgPaceSecPerKm` 同理。PRD 未说 | 同一条轨迹会算出两个不同的「平均速度」，导出文件里的值也无从对齐 |
| C4 | **分段表（`splits`，`F-HIS-25`）无切分规则** | 按公里分段时，横跨暂停的那一公里如何计时？`byAltitudeGain` 的 100m 边界如何对齐（累计爬升的整数倍？） | 分段表是详情页的核心展示，规则不定则每公里配速不可比 |
| C5 | **多登顶点时的上下山切分规则缺失** | `F-REC-36` 说「登顶点是去程与返程的分界点」，`F-REC-34` 又说支持多个登顶点（连穿山头）。**多个登顶时怎么切？** 取首个 / 取最高 / 逐段切？ | 直接决定 `legs.outbound` / `legs.return` 与 `F-HIS-26` 分段统计的结果 |
| C6 | **`F-MEDIA-41` 的照片关联判距未指明坐标系** | 规则是「位置在轨迹 500m 范围内」，但照片原始是 WGS-84、轨迹是 GCJ-02 | 若不先统一坐标系，500m 判距会变成「几百米系统偏移 + 500m 容差」，**照片会大面积错关联或不关联**。本文档 §4.2 已禁止跨坐标系混算，但 PRD 没写明，实现时极易踩 |
| C7 | **GPX 导出的字段映射与丢弃规则未给** | GPX 1.1 表达不了步数、标记类型、暂停分段、计划线路、照片引用。导出时是**丢弃**还是写**自定义扩展命名空间**？PRD 7.2 只说「GPX 表达能力弱」 | 影响 `F-IO-04` 的实际价值——用户拿 GPX 去 Garmin，到底会丢什么，没人说得清 |
| C8 | **ZIP 内 `settings.json` 的导入行为未定义** | PRD 7.4 说该文件可选、`F-IO-03` 说备份含设置，但**导入时是否覆盖本机设置、是否提示、能否选择性跳过**，PRD 与本文档都没写 | 这是**覆盖用户本机设置**的破坏性动作，规则未定就实现，等于埋一个静默篡改设置的坑 |

> **原处置建议**（已执行完毕）：C1 ~ C5 由产品侧一次拍板后统一修订 PRD（对应 §9.2 的 T-20）；C6 ~ C8 属规格补全，并入 PRD 下一版（对应 T-21）。

**裁定结果（PRD v1.11，新增 12 条需求，总数 257 → 269）**

| # | 裁定 | 新增编号 |
| --- | --- | --- |
| C1 | `trip.status` 只取 `RECORDING` / `PAUSED` / `FINISHED`；`IDLE` / `STOPPED` / `SAVED` / `DISCARDED` 是**会话状态、不落库**；**只有 `FINISHED` 进列表与统计**，另两个仅供崩溃恢复 | 无（6.3.1 补说明） |
| C2 | 卡路里改用 **MET 分段估算法 v1**（上坡 7.0 / 下坡 4.0 / 平地 4.5，按净海拔斜率 ±3% 判定），新增**体重**设置项（默认 65kg），界面标注「估算」，公式版本写入 `README.txt` | `F-HIS-35`、`F-SET-06` |
| C3 | 平均速度 = `距离 ÷ 运动时长`（**不含暂停**）；平均配速同理；距离 < 50m 时显示「—」、导出写 `null` | `F-HIS-33`、`F-HIS-34` |
| C4 | 分段表按**累计运动距离每 1km** / **累计爬升每 100m** 切分；**暂停时间不计入任何段**；尾段照常列出 | `F-HIS-36` |
| C5 | 上下山分界取**时间上最后一个登顶点**（不取最高点——「先登主峰、再连穿山头、最后从另一侧下撤」时按最高点会把下撤前的山脊行走错算进返程） | `F-REC-37` |
| C6 | 照片判距**必须先把照片转 GCJ-02** 再与轨迹比距离，禁止跨坐标系混算 | `F-MEDIA-43` |
| C7 | **GPX 恒用 WGS-84、不受导出坐标系设置影响**（沿用 GCJ-02 会静默偏移几百米）；每个 segment 一个 `<trkseg>`；marker 用 `<wpt><type>` 写枚举原文；**不使用自定义扩展**；导出后必须提示丢弃项 | `F-IO-14/15/16` |
| C8 | `settings.json` **默认不导入**；用户显式勾选后须列出将被改变的项并二次确认；**语言始终不导入**（设备偏好 + 会触发界面重建、掩盖导入结果） | `F-IO-36/37` |

> **实现落点**：C2 / C3 / C4 / C5 的实现规格见本文档 §4.12；C6 的坐标系规则见 §4.1 调用点清单；C7 映射规则见 PRD 7.2.1；C8 行为见 PRD 7.4 与 §5.2 的 P-17 契约。
>
> **这一轮的价值在于**：这 8 项此前都是「字段已定型、算法不存在」或「规则看似清楚、实则没说坐标系」，不澄清就会各自实现出不同结果，且要到联调或验收时才暴露。**在动手前一次性裁定，成本最低。**


#### D 类：第三方 API / 平台行为未经查证（已在 §9.2 落成 TODO）

| # | 未经查证的内容 | 落在哪 |
| --- | --- | --- |
| D1 | `MapsInitializer.updatePrivacyShow/updatePrivacyAgree` 的签名与参数含义（§1.5 写成了既定调用） | T-16（与 T-06 合并核对） |
| D2 | 高德 SDK 是否内置 consumer-proguard 规则（§7.1 写成事实） | **✅ 已查（T-17）**：无内置规则，须手写 |
| D3 | 高德 SDK 的 Maven 坐标 `com.amap.api:3dmap` / `com.amap.api:location` 是否可用（§1.3 的 toml 里已写成事实） | T-01（已存在，但 **toml 里的写法本身也是假设**，需同步修正） |
| D4 | Vico 2 的 artifact 坐标 `com.patrykandpatrick.vico:compose-m3` 是否正确 | 并入 T-10 |
| D5 | 限定 locale 后 `en-XA` 伪本地化是否仍可用（直接关系 `F-I18N-40`） | T-18 |
| D6 | AGP / Kotlin 的弃用 API 现状（`kotlinOptions` → `compilerOptions`、`resourceConfigurations` → `localeFilters`）；叠加 §7.4 的 `warningsAsErrors = true`，弃用警告会让 CI 直接变红 | **✅ 已查（T-19）**：`kotlinOptions` 自 Kotlin 2.0 弃用、`resourceConfigurations` 自 AGP 8.8 弃用；§7.1 已补两种正确写法 |

#### 自查结论

本文档中**每一个具体数值**都要分成两类看：「PRD 给的」还是「本文档补的」。后者（滤波系数、抽稀容差、采样间距、性能目标、偏移量区间）**都是待核定参数，不是结论**。§4.11 的参数总表已标注出处列，但出处为 PRD 的那些是**继承来的**，其正确性取决于 PRD 本身的可靠性——这也是为什么 PRD 里的技术断言（如原 6.2.2）需要同样严格地查证。

**2026-09-19 收尾状态**：A 类 5 项已修；B 类 9 项已改为待验证；C 类 8 项已由 PRD v1.11 裁定并落地；D 类 6 项全部落成 §9.2 的待办（T-16 ~ T-21）。**剩余未闭环的只有 §9.2 的 21 项实测/查证项，以及 B 类中需真机数据核定的参数**——这两类都必须等 M0 骨架起来、能在真机上跑之后才能推进。

---

## 10. 修订记录

| 版本 | 日期 | 修订人 | 说明 |
| --- | --- | --- | --- |
| v1.0 | 2026-09-19 | — | 初稿。基于 PRD v1.8 编写，覆盖六块：① 全局工程基线（依赖基线、代码规范、隐私合规启动时序、错误约定）；② 模块与分层落地（模块清单与依赖方向、各 core 模块公开接口、Hilt 依赖图、线程与并发模型、单一状态源）；③ 数据层设计（Room 版本与迁移策略、DAO 方法签名、批量写入与事务边界、DataStore 键表，并补充 `recording_state` / `media_index` 两张表与 `track_point.distanceM` 列）；④ 关键算法规格（坐标转换含完整实现与调用点清单、距离与速度、海拔融合与**双累加器分离**、计步四级降级、Douglas-Peucker 抽稀容差表、网格聚类、线路评估与难度、候选线路生成、提醒引擎、高程三级降级，共 29 个单元测试用例 + 参数总表）；⑤ 页面 × ViewModel 契约（MVI 基类、P-01~P-18 全量契约表、P-08 详细契约、导航图）；⑥ 平台能力实现（前台服务与记录会话、定位主备切换、传感器、地图封装与生命周期、TTS、崩溃恢复）；⑦ 构建与 CI（Gradle 配置、签名、GitHub Actions 流水线、Lint 与 detekt 门禁、i18n 资源键对齐脚本、Git 规范）；⑧ 测试计划（五层分层、用例清单、覆盖率目标、测试替身清单、手工验收映射 PRD 各节验收标准）；⑨ 需求映射与待办（需求域→模块→里程碑映射、14 项 `⚠ TODO(M0)` 实测清单、11 项设计补充登记、里程碑 DoD）。**本文档不复制 PRD 需求正文，一律以需求编号引用 PRD。** |
| v1.1 | 2026-09-19 | — | **新增 T-15：高德是否提供「智能算路 / 多备选路线推荐」能力**（用户提出）。初步查证发现两项与 PRD 6.2.2 既有假设冲突的线索：① 地图 SDK `RouteSearch` 除 `WALK_DEFAULT` 外还有 **`WALK_MULTI_PATH`（步行多路径）** 模式；② 步行 Web API `/v5/direction/walking` 有 **`alternative_route` 参数**（1/2/3，不传默认一条）。PRD 6.2.2 写的「高德步行规划单次只返回一条最优结果」因此**可能不成立**。据此：§9.2 的 TODO 清单由 14 项增至 **15 项**；新增 §4.8.1，列出四个待测候选接口（地图 SDK 多路径模式 / 旧构造签名兼容性 / Web API `alternative_route` / 导航 SDK `TravelStrategy.MULTIPLE` + `getNaviPaths()`）与「四种实测结论分别对应哪种选型」的**判定规则**，并声明若原生能力可用则 §4.8 的「构造垂直偏移途经点」策略**应整体废弃**。**本条暂不改 PRD**——待 M0 实测出确定结论后再回改 PRD 6.2.2（按 §0.1 的边界规则，PRD 不承载未经验证的技术假设，但也不因猜测而改动）。 |
| v1.2 | 2026-09-19 | — | **全文专项自查「未确定但被当作已确定」的事项，共 28 项，并同步修订 PRD 至 v1.10**：<br>**A 类（文档内部矛盾，5 项，已修）**——`media_index` 缺 `dateModifiedMs` 列却已被查询；`(mediaStoreId, mediaType)` 只声明唯一索引与 `@Upsert` 冲突（Room 按主键判定）；`ElevationCacheDao` 在 §4.10 被引用但全篇无表定义；`.gitignore` 排除 `app/libs/*.aar` 与 T-01 的 aar 引入方式互斥（会导致新克隆与 CI 编译不过）；`PlanPick` 路由缺 `target` 参数。<br>**B 类（猜测写成事实，9 项，已改为待验证）**——§1.3 整套依赖版本号未经核对；§4.1「实测 < 4 次收敛」是编的；§4.11 的 GCJ-02 偏移「300–800m」断言未采样且会导致测试假失败；§4.2「< 0.5%」未实测；§3.4/§4.5 的两个性能目标是自拍而非 PRD 要求；§4.4 滤波器阶数与自适应阈值系数、§4.3 漂移修正系数均为自创参数、无真机数据；§7.1「高德官方已内置 consumer rules」未查证（若否，Release 会崩溃且只在 release 变体现形）。<br>**C 类（PRD 层面空白/矛盾，8 项，本文档无权裁）**——`trip.status` 在 PRD 6.3.1 与 7.1 两处取值不一致；`caloriesKcal` 有字段无公式；平均速度/配速无定义；`splits` 分段无切分规则；多登顶点时上下山切分规则缺失；`F-MEDIA-41` 照片关联判距未指明坐标系（混算会大面积错关联）；GPX 导出字段映射与丢弃规则未给；ZIP 内 `settings.json` 的导入是否覆盖本机设置未定。<br>**D 类（第三方 API 未查证，6 项）**——已全部落成 §9.2 的 T-16 ~ T-21。<br>**结构变更**：新增 §9.5「未确定但被当作已确定的事项（动手前必读）」；§3.1.1 由「新增两张表」扩为「新增三张表」（补 `elevation_cache`）；§9.2 TODO 清单由 15 项增至 **21 项**；§0.2 导航表同步。**本版未改任何需求，PRD 同步升至 v1.10（撤回 6.2.2 未验证的技术断言）。** |
| v1.3 | 2026-09-19 | — | **§9.5 C 类 8 项待裁定项全部裁定落地，PRD 同步升至 v1.11（新增 12 条需求，257 → 269）**：C1 `trip.status` 语义（会话态不落库、只有 FINISHED 进列表与统计）；C2 卡路里 MET 分段估算公式 v1 + 体重设置项；C3 平均速度/配速口径（分母用运动时长）；C4 分段表切分规则（按运动距离/累计爬升，暂停不计入）；C5 多登顶点取**时间上最后一个**作为上下山分界；C6 照片判距必须先转 GCJ-02；C7 **GPX 恒用 WGS-84、不受导出坐标系设置影响** + 字段映射与丢弃规则；C8 `settings.json` 默认不导入、语言始终不导入。<br>**本文档配套变更**：新增 **§4.12 统计指标与分段计算**（`TripStatsCalculator` / `CalorieCalculator` / `SplitsCalculator` / `LegSplitter`，含「`splits`/`legs` 不落库、汇总统计落库」的存储决策与「暂停时长无法从轨迹点反推，必须逐段记录并随记录落库」这一关键约束）；§4.1 调用点清单补 GPX 恒定 WGS-84 与照片判距两行；§3.5 补 `body_weight_kg`；§5.2 的 P-17 契约补 `restoreSettings` / `settingsDiff`；§9.2 的 T-20 / T-21 标注为已裁定；§9.5 C 类补「裁定结果」表。**条数复核**：需求表逐行重算为 269 条（P0 131 / P1 121 / P2 17），修正初稿误记的 270。<br>**当前剩余未闭环项**：§9.2 的 21 项实测/查证，以及 B 类中需真机数据核定的参数——均需等 M0 骨架能在真机运行后推进。 |
| v1.4 | 2026-09-19 | — | **把 §9.2 里「能在 PC 上验的」全部验掉，清单按可验证性重排为 §9.2.1（15 行，已完成）/ §9.2.2（6 行）/ §9.2.3（9 行）；PRD 同步升至 v1.12（只澄清技术背景，需求 269 条不变）**。验证脚本落在 `tools/m0-verify/`（可复跑，含一个纯 Python 的 Java class 解析器）。<br>**结论落地**：<br>**① 高德（T-01/T-05/T-06/T-13/T-16/T-17）**——公网 Maven **只有 jar 没有 aar**，四件套版本都停在 2024 年；`updatePrivacy*` 签名与 §1.5 **完全一致**（不用改）；**无 `ClusterOverlay`**（自研聚类是唯一选择）；`Hight_Accuracy` 是高德原文拼写但属**枚举常量**；**jar 内无任何 proguard 规则 → keep 规则必须手写**（§7.1 补初稿）。<br>**② 补上一个漏掉的依赖**：`PoiSearch`/`Inputtips`/`RouteSearch` **都不在 `3dmap` 包里**，在 `com.amap.api:search` 里 → §1.3 依赖清单原先缺这一项，已补 `amap-search`，并在「关键约束」里写明高德是四件套。<br>**③ 多路径（T-15 API 面 + PRD 6.2.2）**——确认 `RouteSearchV2.AlternativeRoute.ALTERNATIVE_ROUTE_ONE/TWO/THREE` + `setAlternativeRoute(int)` 存在（首选），`WALK_MULTI_PATH` 与两个构造签名存在，步行 Web API **v5** 的 `alternative_route` 由官方文档确认（v3 没有），导航 SDK 的 `TravelStrategy.SINGLE/MULTIPLE` + `getNaviPaths()` 存在。**PRD 6.2.2「单次只返回一条」正式作废**，「构造垂直偏移途经点」由主策略降为兜底。<br>**④ 三个自拍参数改为实测值（B2/B3/B4）**——GCJ-02 偏移真实区间 **40.9–686.3 m**（原写 300–800 错、放宽后的 100–1000 也错，单测边界改为 30–800）；用 GCJ-02 算距离最大相对误差 **0.53%**（原写「< 0.5%」不成立，改按 ≤1%）；迭代收敛 `eps=1e-9` → ≤4 次、`1e-12` → ≤6 次，并明确禁止单步近似反解（误差 0.59–3.40 m）。<br>**⑤ 高程（T-07）**——三个候选服务本机网络均可连通、支持批量；**但实测发现 DEM 90 m 在陡峭山峰系统性低估海拔**（泰山低 54 m、黄山低 135 m）→ §4.10 补精度陷阱说明与三条应对；DEM 采样间距 50 m 与 90 m 栅格不匹配，改为按数据源分辨率自适应（B8）。<br>**⑥ 构建配置（T-19）**——`kotlinOptions` 自 Kotlin 2.0 弃用、`resourceConfigurations` 自 AGP 8.8 弃用，**叠加 `warningsAsErrors = true` 会直接编译失败**；§7.1 给出 `compilerOptions` 与 `localeFilters` 两种正确写法与版本取舍规则。<br>**⑦ 新增登记项 D-12**（首次登记时误编为与他项重复的 D-10，已在 v1.5 订正）：线路爬升评估须标注为「估算」（DEM 低估是系统性偏差，不是随机误差），**建议新增 PRD `F-PLAN-46`，当时待确认**。<br>**仍未闭环**：§9.2.2 的 6 行（需要高德 Key 或 M0 构建产物）与 §9.2.3 的 9 行（必须真机）。 |

| v1.5 | 2026-09-19 | — | **D-12 落地 PRD：新增 `F-PLAN-46`（爬升 / 最高海拔标注「估算」），PRD 同步升至 v1.13（269 → 270 条需求）**。<br>**落地内容**：PRD 6.2.3 补「需求」表并写明标注义务与实测依据（DEM 在陡峭山峰低估 54~135 m，是**偏差**不是随机误差）；6.1 的说明性文字改为指向 `F-PLAN-46`；附录数量 F-PLAN 41 → 42、合计 269 → 270（P1 121 → 122）；里程碑 M2 验收条目补该编号。<br>**同时订正上一版遗留的三处错误**：① §9.3 曾把新增登记项编成 **D-10，与原有的「debug 变体 / debug Key」项撞号** → 改为 **D-12** 并追加到表末；② 头部「剩余 12 项」与 §9.2 引言「剩下 12 项」与三张子表实际行数（15 / 6 / 9）对不上 → 统一按行数陈述；③ v1.4 记录里「§9.2.3 的 6 项」笔误 → 9 行。<br>**教训**：新增编号型条目（D-xx、T-xx、F-xxx-nn）前必须**先扫全表最大号再自增**，不能凭印象挑一个「看起来是下一个」的号。 |

| v1.7 | 2026-09-19 | — | **按《文档审阅报告-2026-09-19》修复 DEV 侧全部发现（H-1~H-4、D1~D22、X1/X2/X3/X6），PRD 同步升至 v1.14、FEASIBILITY 升至 v1.2。**<br>**高危**：① **H-1 双写矛盾**——记录期分批落库为唯一事实，`saveFinishedTrip` 去掉 `track_points` 全量重插（改为只补 `trip` + `marker` + 清状态），并补「track_point/marker 对 trip 不建外键」的外键策略（D4）；② **H-2** `ksp {}` 移出 `android {}` 到项目级顶层；③ **H-3** AltitudeFuser / AlertEngine 改**工厂注入**（逐场状态不得被 @Singleton 持有），补 `restore(state)`、§6.6「重建」改 `rebind(tripId)`（D5/D6）；④ **H-4** `recording_state` 增 `movingSecBySegmentJson`（逐段时长落库的承载结构）。<br>**中危**：D1 Haversine 期望值改 111.195 km；D2 AlertEngine 距离打点只以 lastDistanceMarkM 为唯一基准（删双轨）；D3 高程批量查询补「按 (latKey,lngKey) 精确回配」硬规则；D7 recording_state 增双累加器锚点列；D8 聚类入参必须密度换算；D9 合并重复用例 `gcjToWgs_迭代收敛`；D10 AltitudeFuser/AlertEngine 入可替身清单 + §8.8 补 Fake；D11 DAO 计数改 7 个；D12 手动校准键语义（null=键缺席、仅本场有效）；D13 计步预热定死 25 步；D14 MapView 生命周期重写（remember 持有 + onDestroy 唯一出口 + onSaveInstanceState）；D15 Channel/SharedFlow 显式容量与丢点计数；D16 用例计数 30=29+1；D17 合并重复 keepattributes；D18 删无消费的 media_index 坐标索引；D22 补 track_point 索引清单；X1 首条候选 ≤3s 上屏、8s 仅约束补齐；X2 P-07 补 Export；X3 P-17 补 oversizeWarning + 用例；X6 §9.1 补 P2 暂缓登记。<br>**低危（同批顺手修）**：D19/D20/D21 措辞订正；§8.3 原子性用例口径随 H-1 更新。<br>**配套登记**：§9.3 新增 D-13（PRD 7.1 补列，已落地 PRD v1.14）。**本版不改需求条数（270 条不变）**。 |
| v1.6 | 2026-09-19 | — | **新增 §7.1.1「JDK 与构建工具链版本要求（已核实）」——补上全文一处空白：本文档原来只写了 `sourceCompatibility/targetCompatibility = 17` 与 `jvmTarget = JVM_17`，却从未定义「开发机需要什么 JDK」「用哪个 JDK 去跑 Gradle」，也没有 Gradle wrapper 版本。**<br>**核实结论**：① AGP **8.x / 9.x 都要求最低 JDK 17**（AGP 7.x 才是 11），本项目字节码级别就是 17 → **JDK 17 是硬门槛**；② 但「运行 Gradle 的 JDK」另有一道上限，由 Gradle 版本决定（JDK 21→Gradle ≥ 8.5 / 22→8.8 / 23→8.10 / 24→8.14 / **25→9.1.0** / 26→9.4.0），**错误组合会直接构建失败且报错指向不明**；③ **本机已存在该冲突**：Android Studio 自带 JBR 是 **JDK 25.0.2**，而 `~/.jdks` 下另有 **JDK 17.0.14**；沿用初始基线 AGP 8.7.3 + Gradle 8.9 时，若让 Gradle 用自带的 JDK 25 跑就会挂，必须把 Gradle JDK 显式设为 17；④ 给出 A（AGP 8.7.3 + Gradle 8.9 + Gradle JDK 17，稳）/ B（AGP 9.x + Gradle 9.1+ + JDK 21 或 25，新）两套组合，要求与 T-14 的依赖基线决策**合并锁定**；⑤ 明确不写死 `org.gradle.java.home` 绝对路径，改用 `GRADLE_LOCAL_JAVA_HOME` / `JAVA_HOME`；⑥ 说明为何暂不使用 `jvmToolchain(17)`（会触发联网下载，而本机 `~/.gradle/jdks` 为空）。**本版不改 PRD**——构建工具链属实现细节，不影响用户可感知行为。 |
