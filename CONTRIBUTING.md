# 贡献指南（CONTRIBUTING）

感谢关注「去爬山 GoHiking」！提交代码前请先读完这份指南——项目有一套**刻意收紧的工程约束**，遵守它们能让每个 PR 的评审成本低得多。

## 1. 开发环境

| 项 | 要求 |
| --- | --- |
| JDK | **17**（运行 Gradle 的 JDK 有版本上限，由 Gradle 8.9 决定；不要用 Android Studio 默认的 JBR 25） |
| Android SDK | 35 |
| 高德 Key | `local.properties` 注入 `AMAP_KEY_DEBUG`（见 README「快速开始」），**不得提交仓库** |

## 2. 硬性规则（改错会被打回）

1. **文档先行**：需求变更必须先改 `docs/PRD.md`（升版本号 + 补修订记录），实现细节进 `docs/DEV-DESIGN.md`；两者冲突以 PRD 为准。PRD 不承载实现细节，一律用需求编号（`F-XXX-nn`）引用。
2. **需求编号不复用**：废弃编号保留占位；新增编号前**先扫全表最大号再自增**（历史上撞号过一次）。
3. **实现层新增项登记**：凡 PRD 没写但实现必须做的决定，登记到 `docs/DEV-DESIGN.md` §9.3（D-nn 编号）。
4. **版本基线锁定**：AGP 8.7.3 / Gradle 8.9 / JDK 17 / Kotlin 2.1.0（DEV §7.1.1）。升级依赖前先确认 Kotlin metadata 兼容性（vico 3.x 因需 Kotlin 2.4 被拒之门外，见 §9.2.1 T-10 修订）。
5. **坐标系统一 GCJ-02**：照片 EXIF / GPX / Fused 定位是 WGS-84，显示与判距前必须转换；**禁止跨坐标系混算**（PRD F-MEDIA-43 红线）。
6. **数据不编造**：传感器/网络不可用显示「—」并降级，绝不伪造数值。
7. **i18n 一等公民**：字符串集中在 `core/resources`，`values/` 英文兜底、`values-zh-rCN/` 中文；枚举值不翻译。`checkStringKeys` 门禁强制两目录键集合对齐。
8. **高德 SDK 隐私红线**：`updatePrivacyShow/updatePrivacyAgree` 必须在用户同意后才调用，且先于任何 MapView 创建。

## 3. 提交前门禁（全绿才算完成）

```bash
JAVA_HOME=<jdk17> ./gradlew assembleDebug :app:lintDebug :core:resources:checkStringKeys \
  :core:data:testDebugUnitTest :core:datastore:testDebugUnitTest \
  :core:location:testDebugUnitTest :core:common:testDebugUnitTest :core:elevation:testDebugUnitTest
```

- 算法层（抽稀/统计/聚类/坐标转换/IO 编解码）**必须带单元测试**；
- UI 层改动请附截图或简述真机验证结论；
- **改了 Room 实体或 `Migrations.kt` 必须跑迁移校验**（不需要 Android，几秒完成）：

  ```bash
  python tools/db-verify/verify_room_migrations.py
  ```

  它会按 `N-1.json` 建库、执行 `Migrations.kt` 里的 SQL，再与 `N.json` 逐项比对
  （表名/列/类型/NOT NULL/主键/索引名与列顺序）。Room 运行时做的就是这件事，
  不一致会抛 `Migration didn't properly handle`，用户升级即崩溃 —— 最常见的翻车是
  **索引名对不上**（Room 规则：`index_<表名>_<列名>`，复合索引按声明顺序用 `_` 连接）。
  新增的 schema JSON（如 `3.json`）是构建产物，**记得一并提交**。

## 4. 提交规范

- commit message：`type(scope): 描述`，正文说明动机与关键决策，附需求编号（如 `feat(media): 照片网格聚类（F-MEDIA-10~13，DEV §4.6）`）；
- type 取 `feat` / `fix` / `refactor` / `docs` / `test` / `chore`；
- 版本号**不要**在 PR 里改（维护者按里程碑批次统一升 `gradle.properties` 并整理 CHANGELOG）。

## 5. 分支与发布

- 主分支 `main`；功能分支 `feat/<主题>`；
- 打 `v*` tag 推送即触发 CI 构建 release APK 并创建 GitHub Release（说明自动取自 CHANGELOG 对应小节）。

## 6. 协议

提交即表示同意以 [Apache-2.0](LICENSE) 协议授权你的贡献。
