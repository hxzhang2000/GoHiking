package com.gohiking.core.data.io

/**
 * 导入/导出校验的**原因码**（H-09：core 层不得直接产出用户可见文案）。
 *
 * 此前 [ImportEngine] / [BackupBuilder] 直接拼中文字符串并以 List<String> 交给 UI 渲染，
 * 英文界面会直接弹出中文。改为「码 + 参数」后在 feature:io 侧用 stringResource 本地化，
 * 与 DEV 决策 8（字符串集中在 core/resources）一致。
 */
enum class ImportReasonCode {
    /** 计划线路 id 已存在 */
    ROUTE_EXISTS,

    /** 计划线路冲突，等待用户逐条决策（policy = ASK 且未提供 decisions） */
    ROUTE_CONFLICT_WAIT,

    /** 记录冲突，等待用户逐条决策 */
    CONFLICT_WAIT,

    /** 记录 id 已存在（按 SKIP 跳过） */
    TRIP_EXISTS,

    /** 同名同时开始但 id 不同，未自动合并（F-IO-41） */
    DUPLICATE_SUSPECTED,

    /** 单条导入抛异常（F-IO-28：不影响其他条） */
    IMPORT_FAILED,

    SCHEMA_VERSION_INVALID,
    SCHEMA_TOO_NEW,
    SCHEMA_UNKNOWN,
    PARSE_FAILED,
    CHECKSUM_MISMATCH,
    UNSAFE_ENTRY,
}

/** 非阻断性提示（仍会继续处理） */
enum class ImportWarningCode {
    /** 文件未声明 crs，按 WGS-84 处理（PRD 7.5） */
    CRS_MISSING,

    /** 疑似重复（F-IO-41） */
    SUSPECTED_DUPLICATE,

    MANIFEST_COUNT_MISMATCH,
    ENTRY_LIMIT,
    SIZE_LIMIT,
    MANIFEST_PARSE_FAILED,
}

/** 与文件绑定的原因，供预览/报告列表渲染 */
data class ImportItemMessage(
    val name: String,
    val code: ImportReasonCode,
    val arg: String? = null,
)

/** 全局或按文件的提示 */
data class ImportWarning(
    val code: ImportWarningCode,
    val fileName: String? = null,
    val args: List<String> = emptyList(),
)
