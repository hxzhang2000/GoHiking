# 更新日志（CHANGELOG）

所有对外可感知的变更记录在此。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 版本管理规则（2026-09-20 建立）

1. **版本号唯一来源**：`gradle.properties` 的 `gohiking.versionName` / `gohiking.versionCode`。
   `app/build.gradle.kts` 只读取，不硬编码——升版本**只改这一处**。
2. **语义化版本** `MAJOR.MINOR.PATCH`：
   - `0.x.y` = 开发期（首个正式发布为 `1.0.0`）；
   - MINOR 位对应里程碑批次（0.1.0=M0 骨架 / 0.2.0=M1 记录 / 0.3.0=M2 计划线路）；
   - PATCH 位用于开发期内的修复回填。
3. **升版本必做**：
   - 改 `gradle.properties`；
   - 在本文件 `[Unreleased]` 下补条目（发布时改为版本号 + 日期）；
   - 若 PRD 7.3 的 `generator.versionName` 引用应用版本，随 release 同步。
4. **versionCode**：每次对外分发的 APK 递增 +1；开发期本地构建不递增。
5. 里程碑定义见 `docs/PRD.md` §10 路线图；文档修订另见各文档自身的版本记录。

## [Unreleased]

> 本节用于记录尚未定版的变更。任何改动先写在这里，发布时改为版本号 + 日期。

## [v0.5.2] - 2026-09-21

> UI 原型对标批次：按 `prototype/` 交互原型（P-01 ~ P-18）完成 18 页页面级对齐与底部
> Tab 导航，两批提交（`ecffc6a` + `8adc865`）。打 `v0.5.2` tag 推送后，CI 自动提取本节
> 生成 GitHub Release 说明。

### Added
- **底部 Tab 导航**：地图 / 计划 / 记录 / 我的 四主 Tab（原型 tabbar），主 Tab 页（P-02/07/10/14）常驻、子页隐藏；系统返回在非地图 Tab 先回地图 Tab
- **P-01 权限引导页**：首次同意隐私后展示一次，逐项说明用途，单项/一键授权；定位仍由开始记录流程兜底（F-REC-53 不变）
- **P-15 关于页**：品牌头 / 运行时版本 / 描述 / 仓库与 Issue 入口（可点击）/ Apache-2.0
- **P-04 候选卡方案名**：方案 A·最优 / B·最短 / C·缓坡 / 备选（DEV §4.8 语义；爬升为异步补齐字段，补齐后自动刷新）
- **P-02 图层菜单**：标准 / 卫星 / 夜间 / 导航四种底图；定位按钮复用全局 LocationProvider（8s 超时取首个 fix 居中，防抖 + 失败提示）

### Changed
- **主题**：Material 色板整体对齐 PRD 8.4 与 `prototype/styles.css`（主色 #2F80ED、中性/标签色全套、12dp 圆角，深色 scheme 推导），全部页面自动继承
- **P-02 首页**：搜索条（→选点）、照片地图入口、底部「计划线路 / 开始记录」双按钮；M0 地图验证壳退役，点击冲突回归观测保留为提示 chip
- **P-03~P-06 计划流**：appbar（返回/标题/起终点互换）+ 起终点 seg + 候选纵向卡（选中描边/对勾/难度标签）+ 手动打点 pickbar + 保存对话框（名称/备注/全程汇总，note 落库）
- **P-07 计划列表**：整页重写（appbar 新建 + 缩略图卡列表 + 难度 + 空态插画）
- **P-08/P-09 记录页**：appbar 胶囊（记录名 + 提醒标签）、暂停顶部胶囊、大数字统计卡、四键控制条（标记/暂停·继续/登顶/长按停止）；保存确认改 2×2 汇总 + 名称/备注可编辑
- **P-10 记录列表**：四列汇总卡、行程行轨迹缩略图 + 箭头、空态「开始记录」
- **P-11 记录详情**：地图固定 170dp + 概览/图表/分段/标记/照片 五 Tab，操作栏常驻底部；分段/标记/照片空态独立文案
- **P-14 设置**：按原型分组卡重构（通用/提醒/记录/单位/地图/数据/关于），数据操作行式入口

### Docs
- 新增 `docs/PROTOTYPE-ALIGNMENT.md`：18 页对标状态总览与 D1~D4 形态偏离裁定（P-12 装饰项、P-13 Dialog 形态、P-14 设置项分期、P-16/17/18 对话框承载）
## [v0.5.1] - 2026-09-21

> 第二轮深度审查的**打磨项**收尾批次：LOW 18 项 + 三处门禁加固（i18n 键对齐、CI Release 说明、
> 备份完整性校验）。无新功能、无里程碑。打 `v0.5.1` tag 推送后，CI 自动提取本节生成 GitHub Release 说明。

### Fixed — 《代码审查报告-2026-09-21》（第二轮深度审查）修复落地

**阻断级（CRITICAL）**
- N-01 首次同意隐私政策后的**每一次冷启动**点「开始记录」必抛异常：`PrivacyConsent` 是进程内内存态，冷启动必复位，而持久化的同意状态从不回灌 → 定位 SDK 初始化在 `assertAgreed()` 处崩溃
- N-02 行程详情页分段表嵌套 `LazyColumn`，任何有真实数据的行程打开即崩：改回 `Column`（纵向 LazyColumn 的 item 以 `maxHeight = Infinity` 测量，内层再放 LazyColumn 命中 Compose 断言；`userScrollEnabled=false` 绕不过）

**高危（HIGH）**
- N-03 崩溃恢复后无气压计设备海拔永久 null（`restoreRef` 未回灌 `confirmedAlt`、未预热中值窗口）
- N-04 距离统计被 GPS 跳变点污染（`quality≠0` 的点只拦了入边，出边仍参与累加）
- N-05 计步级③预热阈值写死 0.8，轻步态/背包放置时预热永不完成、步数恒为 0（改为自适应 + 20s 时间兜底）
- N-06 主源不可用切备源的两条通路同时断开（`onPrimaryUnavailable` 从未接线；watchdog 判据用「回调时间」而非「有效定位时间」）
- N-07 单位/配速设置切换后历史列表不刷新（`DisplayUnits` 由 `@Volatile var` 改为 `StateFlow`，ViewModel 订阅重算）
- N-08/N-09 `runCatching` / `catch(Exception)` 吞掉 `CancellationException`（高德路径规划与搜索）
- N-10 照片地图 marker 永不渲染（缺「数据就绪」触发 + 相机去重只看 zoom 不看 target）
- N-11 targetSdk 35 强制 edge-to-edge，7 个页面补 `statusBarsPadding()` / `navigationBarsPadding()`
- N-12 `SettingsViewModel` 持 Activity 回调（泄漏 + 第二次语言切换静默失效）→ 改一次性事件流
- N-14 CI 产出的 release APK 高德 Key 为空 → 新增 Key 注入步骤 + 构建期断言（`-PamapKeyOptional=true` 可豁免）
- N-15 `gradle-wrapper.properties` 提交了开发者本机 `file:///` 路径 → 改回官方地址
- N-16 高德搜索 SDK 合规接口 `ServiceSettings` 从未调用
- N-17 `RecordingSession` 可变状态无同步（`bufferLock` / `markersLock` + 采集链异常兜底）
- N-18 单次距离跳变引发「提醒风暴」（基准一次性 `floor()` 对齐到档位）
- N-19 5 处 MapView 生命周期补 `pause → destroy` 配对
- N-27/N-45/N-46 高德客户端改持 `applicationContext`、`inputTips` 校验 `rCode`、`core:map` 补日志
- N-28 `ACTIVITY_RECOGNITION` 已声明却从未动态申请 → 计步静默失效
- N-29 首页返回键被拦截为 `moveTaskToBack`，改为 2 秒内连按两次退出
- N-31 `TtsSpeaker` 音频焦点泄漏（忽略 `speak()` 返回值、未覆写 `onStop`）
- N-33 详情页行程不存在时永久停在「加载中…」→ 补 not-found 终态 + 返回按钮
- N-38 `AltitudeFuser.current()` 每个 fix 被调用两次且有副作用
- N-41 watchdog 协程无异常兜底

**中危（MEDIUM）**
- N-20 ZIP 解压把 `media/` 条目整体读入内存（只用到文件名）→ 改为只推进流不落内存
- N-21 导入把无坐标标记写成 `(0,0)` → 跳过并记日志
- N-22 导入重算 `distanceM` 每段清零，与记录链路全局累加口径不一致
- N-23 媒体权限判定三套口径归一到 `MediaRepository.hasMediaAccess`
- N-24 Android 14「部分照片访问」（`READ_MEDIA_VISUAL_USER_SELECTED`）判成拒绝 → 授权死循环
- N-25 ViewModel 协程普遍无异常兜底（设置写入/详情页读写失败会崩溃）
- N-26 「屏幕常亮」（P0）等死设置：记录页改为跟随设置即时生效
- N-30 崩溃恢复不恢复标记 → 标记即时落库 + 恢复时读回（配套 `MarkerDao.upsert`）
- N-32 `START_STICKY` 重启后服务立即自杀 → Idle 且有快照时自动恢复继续采
- N-34 `trip` 表加索引（Room 迁移到 v3）
- N-39 高程批量查询 `IN` 参数超限（Android ≤ 11 变量上限 999）→ 分块查询
- N-40 单批高程请求超时即全量否决整条线路 → 按连续已知段累计 + `elevationPartial` 标注
- N-23 媒体扫描无逐表容错：任一表查询失败会连累另一表，**且**删除同步会把仍存在的
  媒体索引全部清掉（最坏清空整张 `media_index`）→ 逐表独立容错 + 查询失败时跳过删除同步
- N-42 无 `DATE_TAKEN` 的照片永远关联不上（截图/下载图/不写 EXIF 的相机）：DAO 的
  `dateTakenMs BETWEEN ? AND ?` 在 SQL 三值逻辑下对 NULL 求值为 NULL，行直接被过滤，
  `MediaTripMatcher` 的 `dateModifiedMs` 兜底成了死代码 → 改为 `COALESCE(dateTakenMs, dateModifiedMs)`
- N-35 Room 迁移零测试覆盖（`MIGRATION_1_2` 此前无任何验证，`MIGRATION_2_3` 同理）：
  新增 `tools/db-verify/verify_room_migrations.py`，用 Python + sqlite3 复现 Room 的
  schema 校验（按 N-1.json 建库 → 执行迁移 SQL → 与 N.json 逐项比对），
  **不需要 Android SDK**，已接进 CI 与提交前门禁
- N-36 `PolylineJson` 无法编写测试且 `decode` 无防御：改用 kotlinx.serialization（纯 Kotlin，
  脱离 Android 框架可单测），解码逐元素容错（坏点不连累好点），调用点从此不会因损坏数据崩溃
- N-37 同一份 `polylineJson` 磁盘格式存在两套独立编解码（`IoCodecs` 与 `PolylineJson`），
  且服务于同一条数据的两端（导入写库 / 记录页读库）→ `IoCodecs` 改为委托到 `PolylineJson`，单一实现
- N-44 导入未校验 `status`：非 FINISHED 的记录「导入成功」却永远不可见
- N-56 `TripDetailViewModel` 以 `tripId` 为 key 常驻，删除后的 `deleted=true` 是粘性状态：
  再次打开同一行程会命中同一实例并被历史事件立刻触发导航 → **页面秒退**。改为一次性事件流
- N-47 `PolylineSimplifier` 的「10000 点 < 30ms」性能门禁是空转的：测试输入恰好让 DP 去掉
  99% 的点且切分平衡，永远测不出最坏 O(n²)。→ 加扫描量上界（1024n）作为防掉帧安全网，
  并按区间长度优先处理以保证降级时形状均匀；测试补上真正触发 n² 的用例
- N-52 移除零使用、零申请的 `ACCESS_BACKGROUND_LOCATION`
- N-53 `versionCode` 恒为 1，而 `v0.3.0` 已打 tag 对外分发 → 旧版用户无法覆盖升级（改为 3）
- N-54 `gradle.properties` 中 `org.gradle.caching` 重复定义
- N-55 记录通知距离硬编码 km → 跟随「距离单位」设置
- N-57 `PhotoMapViewModel` 扫描无 try/finally，异常时 `scanning` 永久 true
- N-58 计划页搜索框清空后 `searching` 不复位

**低危（LOW）**
- L-02 `AmapLocationSource.release()` grep 零命中 → `AMapLocationClient` 的原生资源与后台定位线程从不释放，`stop()` 时销毁
- L-04 崩溃恢复后 state 里的 `accumulatedPausedMs` 用快照值、内部字段用已结算值，两者不一致：暂停期间崩溃会让运动时长偏大
- L-05 `start()` 先启动采集后置位 Active：首点回写的 `distanceM` 被随后的 `Active(0.0)` 覆盖、该点触发的提醒标记丢失（恢复路径同样问题）
- L-06 导入缺名计划线路时 core 层硬编码英文 `"Imported route"`（与「core 只产出码」相悖）→ 落中性哨兵 `UNNAMED_ROUTE_PLACEHOLDER`，由 UI 本地化
- L-07 单条导入的 `catch (Throwable)` 吞掉 `CancellationException` → 取消后仍返回报告并凭空多一条 FAILED
- L-08 包内**任意** `*.json` 都被当待导入文件，无关 JSON 以 `SCHEMA_UNKNOWN` 出现在「失败」列表（自有测试名写着 ignored 却从未断言）
- L-09 完整性校验可被绕过：条目缺失即 `continue`，且 `settings.json` / `README.txt` 的校验和从未被核验（只查了 `trips/` `planned_routes/`）
- L-10 `BackupBuilder.Result.manifest` 比包内 manifest 多一项自身校验和，用它做重放比对永远对不上 → 只构造一次并取 `checksums` 快照
- L-11 `TripJson.legs` 声明但从不导出，与 PRD 7.3 schema 不符 → 复用 `LegSplitter` 填充（无登顶点或无海拔样本时保持 null，不编造）
- L-12 `resetToDefault()` 清空 `privacy_agreed` → 设备态同意记录被一并抹掉（合规证据丢失 + 重新撞隐私门），与 `EXCLUDED_KEYS` 同口径保留
- L-13 导出完成对话框标题复用「正在处理…」，与正文「完成，共处理 N 项」自相矛盾 → 新增 `io_done_title`
- L-14 搜索无结果显示「还没有已完成的行程」；删除弹窗标题误用底部 tab 名「记录」→ 新增 `history_no_result` / `history_delete_title`
- L-15 无障碍：历史列表与详情页返回按钮 `contentDescription = null`；记录页停止按钮只有长按手势，TalkBack 完全不可达 → 补描述与等价的无障碍动作
- L-16 历史列表 `items(grouped.size)` 未用 key（同项目 `PlanListScreen` 已有正确先例）→ 改 `items(items, key = …)`
- L-19 硬编码 `"—"` / `"?"` 与资源约定 `common_stat_unknown` 不一致（PhotoMapScreen 两处、PlanScreen POI 无名）
- L-21 `checkStringKeys` 门禁可被静默绕过：注释掉的键仍会被正则匹配 → 先剥离 XML 注释，并新增重名键检测
- L-22 `FormattersTest` 6 用例 32 断言全在默认公制下，H-02 新增的英制分支零测试（`resetToMetric()` 标注「仅供测试」却无人调用）
- L-24 CI 的 awk 分支 A 会把 `[Unreleased]` 段一起写进 Release 说明（实测 25 行输出，首行即 `## [Unreleased]`）→ 两分支统一按 tag 定位小节
- L-25 `tools/m2-verify/verify_amap_arrow.py` 硬编码本机绝对路径 → 改为参数/环境变量/Gradle 缓存自动定位；失效的一次性脚本 `stage2_fix.py` 加显式护栏

### Changed
- `RouteMetrics` 新增 `elevationPartial`（默认 false）：高程部分可用时爬升为「按已知段累计」的下界，界面需标注不完整
- `MarkerDao` 新增 `upsert` / `upsertAll`（`REPLACE`）：支持标记即时落库且保存时幂等覆盖
- Room 数据库版本 2 → 3（`MIGRATION_2_3`：trip 表三个索引）
- 通知文案 `rec_notif_distance` 由 `距离 %1$.2f km` 改为 `距离 %1$s`（单位由格式化器决定）
- `MediaDao.inTimeRange` 的时间口径改为 `COALESCE(dateTakenMs, dateModifiedMs)`（N-42）
- `PolylineJson` 由 `org.json` 改为 kotlinx.serialization；`decode` 入参改为可空 `String?`，
  且**不再抛异常**（N-36）。`IoCodecs.encodePolyline/decodePolyline` 现为委托实现（N-37）
- `TripDetailViewModel.deleted`（state 字段）移除，改为 `deletedEvent: SharedFlow<Unit>`（N-56）
- `PolylineSimplifier.simplify` 引入扫描量上界 `SCAN_BUDGET_FACTOR = 1024n`：预算未耗尽时
  输出与改动前**逐点一致**（DP 的 keep 结果与处理顺序无关），仅在最坏 O(n²) 输入下生效
- `BackupBuilder.Result.manifest` 现在是**包内 manifest 的同一个实例**（取 `checksums` 快照），
  可用于「导出后重放比对」；此前它比包内多一条 `manifest.json` 自身校验和（L-10）
- `BackupReader` 的校验和核验范围扩大到 `settings.json` / `README.txt`；`manifest.json`
  自指无法核验（结构性限制，见源码注释），其余条目缺失时显式报 `CHECKSUM_UNVERIFIED`（L-09）
- `resetToDefault()` 保留 `app_language` 与 `privacy_agreed`（与 `SettingsBackupMapper.EXCLUDED_KEYS` 同口径）（L-12）
- 导入缺名线路的落库值由英文 `"Imported route"` 改为中性哨兵 `UNNAMED_ROUTE_PLACEHOLDER`（L-06）
- `AmapLocationSource.stop()` 现在会 `release()` 掉 `AMapLocationClient`（L-02）
- `Formatters.formatTime`（PhotoMapScreen）改为 `@Composable`，占位符走资源（L-19）

### Added
- 字符串 `io_done_title` / `history_no_result` / `history_delete_title` / `plan_unnamed_route` /
  `imp_warn_checksum_unverified`（中英双语）
- `UNNAMED_ROUTE_PLACEHOLDER`（core:database）：跨模块共用的「线路无名称」哨兵值
- `ImportWarningCode.CHECKSUM_UNVERIFIED`：manifest 声明了校验和但包内条目缺失
- 字符串 `hist_detail_not_found` / `hist_detail_back`（中英双语，详情页 not-found 终态）
- README「已知限制：16 KB 页对齐设备」小节：高德 SDK native 库未 16KB 对齐，App 侧无法补救
- `tools/db-verify/verify_room_migrations.py`（N-35）：Room 迁移校验脚本，纯 Python 无需 Android
- CI 新增 `room-migrations` job；`CONTRIBUTING.md` 补「改 Room 必须跑迁移校验」

### 测试
- `RouteEvaluatorTest`：原「部分点缺失视为不可用」断言固化的是 N-40 缺陷，改为「按已知段累计并标注 partial」+ 新增「已知点太少仍判不可用」
- `TripJsonRoundTripTest`：原「distanceM recomputed per segment」固化的是 N-22 缺陷，改为「跨段累加」
- `PolylineSimplifierTest`：新增「最坏 O(n²) 输入仍在预算内」与「预算不改变正常轨迹输出」（N-47，原性能用例是空转的）
- `MediaTripMatcherTest`：新增「无 dateTaken 且在窗口外仍应拒绝」，并注明该纯函数测试**守不住** N-42（坑在 DAO 的 SQL，需插桩/真机验证）
- 新增 `PolylineJsonTest`（core:common）：往返、历史整数格式兼容、15 种损坏输入不抛异常、
  坏点不连累好点（N-36，此前该模块结构下根本无法测试）
- 新增 `PolylineInteropTest`（core:data）：两套 API 对同一份字节的解释必须一致、
  导入写入的数据必须能被记录页读出（N-37）
- `BackupSecurityTest` 新增 3 条：返回值 manifest 与包内 manifest 逐项一致（L-10）、
  条目缺失时显式报 `CHECKSUM_UNVERIFIED`（L-09）、非数据目录的 JSON 不进失败列表（L-08，
  原用例名写着 ignored 却只断言了 `trips.size`）
- `FormattersTest` 新增 4 条英制与配速显示偏好用例（L-22），并补 `@After resetToMetric()`
  防止进程内单例污染同批次其它用例
- `checkStringKeys` 门禁负向验证：把中文侧某个键注释掉后门禁确实失败（L-21，修复前会被静默放过）

## [v0.5.0] - 2026-09-21

> **两轮审查报告的修复收尾批次**（代码审查 2026-09-20 + 文档审阅 2026-09-19），**不含新里程碑**——
> M0~M4 的交付内容已全部完成，本版是把它修到「可真机回归」的状态。打 `v0.5.0` tag 推送后，CI 自动提取本节生成 GitHub Release 说明。

### Added
- 导入体积超限确认（F-IO-64，文档审阅 X3）：导入 > 500 MB 时预览页先提示体积与预估耗时，确认按钮改为「仍要导入」（DEV §9.3 D-27）
- 高程缓存精确回配（文档审阅 D3）：配对逻辑抽纯函数 `ElevationCacheMatcher`，消除 `IN(lat) AND IN(lng)` 笛卡尔积造成的跨点错配，补 4 条单测（D-28）
- `AltitudeFuser` 接口化 + 测试替身（文档审阅 H-3 / D10）：`AltitudeFusion` 接口与 Real/Fake 工厂，`RecordingSession` 每场新建实例（D-29）

### Changed
- 计划线路爬升标注「估算」（文档审阅 X8 / F-PLAN-46）：原型 P-04 推荐列表、P-06 保存页汇总、P-07 计划线路列表三处的爬升值补 `(估算)` / `(est.)`；P-11 记录详情页为实测值，保持原样
- `avgPaceSecPerKm` 明确为派生字段（文档审阅 X7）：DEV §4.12 注明其由 `movingDurationSec` 与 `distanceM` 派生、随汇总统计落库、`< 50 m` 写 `null`（D-30）

### Fixed
- 《代码审查报告-2026-09-20》修复落地：保存失败不再留白屏（C-01）、崩溃恢复真正接线（C-02）、定位 SDK 隐私合规 Gate（C-03）、系统返回键分发（C-04）
- 导入冲突 ASK 决策真正生效、单位/体重设置不再是死设置（H-01/H-02）、定位降级可恢复（H-03）、计步监听配对注销（H-04）
- 高程不可用时写 `null` 不写 0（H-06，配套 Room 迁移到 v2）；core 层硬编码中文改为「原因码 + 参数」（H-09）；坐标转换系数修正，14 城实测偏差降到 13.4 m 以内（H-10）

## [v0.4.0] - 2026-09-20

> M3 提醒与数据交换 + M4 照片地图与打磨里程碑。打 `v0.4.0` tag 推送后，CI 自动提取本节生成 GitHub Release 说明。

### Added
- 计划线路方向箭头（M2 补录，F-PLAN-37）：候选/选定/返程/手动线沿线 chevron 箭头指示行进方向；记录页关联蓝线同步（D-23）
- 记录中关联计划线路（M2 补录，F-PLAN-44）：蓝线与红线同屏对比，可更换/取消关联；关联后未命名记录采用计划名（F-REC-08）
- 提醒触发引擎（M3-A，F-ALERT-01~26）：距离/海拔打点与上下山判定，三基线随 recording_state 持久化，进程被杀恢复后不重复触发（DEV §4.9）；同时修复 `RecordingSession.start()` 未置 Active 导致记录页永不进入的 P0 缺陷
- TTS 语音播报（M3-B，F-ALERT-40~44）：跟随系统语言、音频焦点让行、未就绪静默；记录页提醒开关指示（F-ALERT-04）
- 设置页 + DataStore 设置存储（M3-C，F-SET-01~06）：6 分组 27 键即时读写，阈值范围/步进校验（F-ALERT-12/22、F-SET-05）、无气压计海拔间隔下限 30m 提示（F-REC-63）、语言切换重启生效（F-I18N-11）、恢复默认（F-SET-03）
- 导出/导入引擎 + 设置页数据操作入口（M3-D，PRD 7.2~7.5）：批量导出全部记录 JSON（SAF 选目录）、全量 ZIP 备份（manifest + SHA-256 校验和 + settings.json）、GPX 1.1 恒 WGS-84 导出（F-IO-04/14/15）；导入 JSON/ZIP 多选，预览（数量/冲突/坐标系转换告知）、四种冲突策略、逐条事务、结果报告（F-IO-20~31/40~44）；ZIP Slip / Zip Bomb / 流式解压三道安全防线（F-IO-60~63）；settings.json 默认不导入（F-IO-36/37）
- 计划线路单文件导出（M3-E，F-PLAN-43）：计划列表页每条线路可导出为 gohiking.planned_route JSON（含两段折线与途经点，坐标系随导出设置）
- 详情页图表与分段表（M4-A，F-HIS-23~25）：海拔曲线（降采样 ≤600 点）+ 每公里配速柱状图 + 公里/爬升分段表（Vico 2.1.4——3.3.1 需 Kotlin 2.4，与构建基线冲突，修订 T-10）
- 详情页照片条（M4-B1，F-HIS-28 / F-MEDIA-40~43）：媒体库全量扫描缓存（MediaStore + EXIF GPS，WGS-84→GCJ-02 入库一次转换，ACCESS_MEDIA_LOCATION 未授标记模糊；±30min + 500m 同系判距，增量比对 + 删除同步）
- 照片地图页（M4-B2，P-12，F-MEDIA-10~15/20~23/25/30~34）：网格聚类聚合气泡（zoom 防抖 150ms 重建、四级直径、单点缩略图）、BottomSheet 缩略图条（拍摄时间 + 逆地理位置，离线「—」）、选择定位地图、全屏查看器（双指缩放/双击放大/翻页/序号时间）；媒体权限进页按需申请（F-MEDIA-04/05）；视频系统播放器
- 详情页单条记录导出按钮（F-IO-01/F-HIS-30）
- CI：GitHub Actions 打 `v*` tag 自动构建 release APK 并创建 GitHub Release，Release 说明自动取自 CHANGELOG 对应小节（参照 NASMusicTV）；另含 test / lint 两个阻塞门禁 job

### Changed
- 日期格式化统一迁移到 `java.time.format.DateTimeFormatter`（替代 `ThreadLocal<SimpleDateFormat>`，消除 K2 可空接收者告警）
- 图表库由 Vico 3.3.1 降为 2.1.4（Kotlin metadata 2.4.0 与 §7.1.1 Kotlin 2.1.0 基线不兼容，编译失败实测）

## [v0.3.0] - 2026-09-20

> M2 计划线路里程碑。打 `v0.3.0` tag 推送后，CI 自动提取本节生成 GitHub Release 说明。

- 选点与搜索（P-03）：地图点选 / 输入联想 / 底图 POI / 当前位置为起点，POI 气泡与拖动调整（F-PLAN-01~09）
- 自动推荐 3 条步行线路（P-04）：候选折线三色渲染、点击高亮、选定落库（F-PLAN-10~16）
- 爬升/难度估算：`core:elevation` 高程三级降级（内存/DB 缓存 → DEM 瓦片占位 → Open-Meteo 远程批量），`RouteEvaluator` 50m 重采样 + 10m 爬升阈值 + 难度评级（D-18）
- 计划确认态：去程/返程两段（返程默认原路返回、可独立重新规划），全程汇总，两段同事务落库（F-PLAN-30/34/35/36/38/40）
- 手动打点兜底（P-05）：依次加途经点、直线虚线/路径吸附、撤销/拖动/删除、上限 50、同样评估（F-PLAN-20~29）
- 计划列表管理（P-06）：列表展示、重命名、删除（F-PLAN-41/42）
- 高德 SDK 切换官方三合一包 `3dmap-location-search`（D-17）

## [v0.2.0] - 2026-09-20 — M1 记录与历史（已完成）

- 记录页：开始/暂停/继续/结束、实时折线与配速、`RecordingSession` 单一状态源、前后台服务
- 统计：距离/时长/配速/爬升（气压计可选，五级降级）/卡路里 MET 估算
- 历史列表与详情（P-10）：Room 持久化、按 segment 抽稀渲染
- 数据层：`recording_state` 每 10 秒持久化（D-01）、`track_point.distanceM`（D-03）

## [v0.1.0] - 2026-09-19 — M0 工程骨架（已完成）

- 18 模块工程（10 core + 7 feature + app）、Hilt/KSP/Room 基线
- 高德地图接入：隐私合规门（同意后才初始化）、地图语言切换、POI/空白点选回调实测
- 构建基线锁定：AGP 8.7.3 + Gradle 8.9 + JDK 17 + Kotlin 2.1.0（DEV §7.1.1）
- 单元测试基线（core:common / core:location / core:data）
