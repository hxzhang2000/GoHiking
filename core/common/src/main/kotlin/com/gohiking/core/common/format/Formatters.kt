package com.gohiking.core.common.format

import java.util.Locale

/**
 * 展示格式化（PRD 6.5 数值格式约定）。
 * 纯 Kotlin、无 Android 依赖；单位符号由本类拼进返回值（"12.35 km" / "832 m"），
 * 语言相关的文案不在这里（DEV 决策 8：字符串集中在 core/resources）。
 */
object Formatters {

    /**
     * 距离：`< 1000 m` 显示整米（"832 m"）；`>= 1000 m` 显示公里两位小数（"12.35 km"）。
     * 负值按 0 处理（防御，口径上距离非负）。
     */
    fun distanceText(distanceM: Double, locale: Locale = Locale.ROOT): String {
        val d = distanceM.coerceAtLeast(0.0)
        return if (d < 1000.0) "${d.toInt()} m" else String.format(locale, "%.2f km", d / 1000.0)
    }

    /** 时长：`h:mm:ss`（小时不封顶，9:05:03）；负值按 0 处理 */
    fun durationText(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * 配速（每公里用时）：`12'34"`；`paceSecPerKm` 为 null（distance < 50m，F-HIS-34）
     * 或非正时返回 null，界面显示「—」。
     */
    fun paceText(paceSecPerKm: Long?): String? {
        if (paceSecPerKm == null || paceSecPerKm <= 0) return null
        return String.format(Locale.ROOT, "%d'%02d\"", paceSecPerKm / 60, paceSecPerKm % 60)
    }

    /** 海拔/爬升：整数米（"1234 m"）；null 按 0（调用方应优先用 common_stat_unknown 处理「—」） */
    fun metersText(meters: Double?): String = "${meters?.toInt() ?: 0} m"
}
