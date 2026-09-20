package com.gohiking.core.data.io

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出文件命名（F-IO-06/07，PRD 7.6）。
 * 记录名非法字符（/ \ : * ? " < > | 及控制符）替换为「_」，超 50 字符截断。
 */
object FileNamer {

    private const val MAX_NAME_LEN = 50
    private val ILLEGAL = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault())

    fun stamp(atMs: Long): String = STAMP.format(Instant.ofEpochMilli(atMs))

    /** F-IO-06：GoHiking_<记录名称>_<yyyyMMdd_HHmmss>.json */
    fun tripFileName(name: String, atMs: Long): String =
        "GoHiking_${sanitize(name)}_${stamp(atMs)}.json"

    /** GPX 互操作导出（F-IO-04）：同名规则，扩展名 .gpx */
    fun gpxFileName(name: String, atMs: Long): String =
        "GoHiking_${sanitize(name)}_${stamp(atMs)}.gpx"

    /** F-IO-07：GoHiking_Backup_<yyyyMMdd_HHmmss>.zip */
    fun backupFileName(atMs: Long): String =
        "GoHiking_Backup_${stamp(atMs)}.zip"

    /** 计划线路单文件导出（F-PLAN-43） */
    fun routeFileName(name: String, atMs: Long): String =
        "GoHiking_Route_${sanitize(name)}_${stamp(atMs)}.json"

    /** F-IO-13：目标文件名已存在时追加 _1、_2……（不静默覆盖用户文件） */
    fun dedupe(candidate: String, exists: (String) -> Boolean): String {
        if (!exists(candidate)) return candidate
        val dot = candidate.lastIndexOf('.')
        val base = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var i = 1
        while (true) {
            val next = "${base}_$i$ext"
            if (!exists(next)) return next
            i++
        }
    }

    internal fun sanitize(name: String): String {
        val cleaned = ILLEGAL.replace(name, "_").trim().trim('_', '.', ' ')
        val sized = if (cleaned.length > MAX_NAME_LEN) cleaned.take(MAX_NAME_LEN) else cleaned
        return sized.ifEmpty { "record" }
    }
}
