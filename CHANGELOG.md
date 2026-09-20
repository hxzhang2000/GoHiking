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
