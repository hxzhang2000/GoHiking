package com.gohiking.core.common.format

import java.util.Locale

/**
 * 展示格式化（PRD 6.5 数值格式约定）。
 * 纯 Kotlin、无 Android 依赖；单位符号由本类拼进返回值（"12.35 km" / "832 m"），
 * 语言相关的文案不在这里（DEV 决策 8：字符串集中在 core/resources）。
 *
 * H-02：单位随 [DisplayUnitProvider.units] 切换（设置项 F-SET-04/05/06 此前完全不生效）。
 */
object Formatters {

    /**
     * 距离：公制 `< 1000 m` 显示整米（"832 m"）、`>= 1000 m` 显示公里两位小数（"12.35 km"）；
     * 英制 `< 1 mi` 显示整码（"910 yd"）、否则两位小数英里（"7.67 mi"）。负值按 0 处理。
     */
    fun distanceText(distanceM: Double, locale: Locale = Locale.ROOT): String {
        val d = distanceM.coerceAtLeast(0.0)
        val u = DisplayUnitProvider.units.distance
        if (u == DistanceUnit.IMPERIAL) {
            val miles = d / DisplayUnitProvider.mPerMile
            return if (miles < 1.0) {
                "${(d / 0.9144).toInt()} yd"
            } else {
                String.format(locale, "%.2f mi", miles)
            }
        }
        return if (d < 1000.0) "${d.toInt()} m" else String.format(locale, "%.2f km", d / 1000.0)
    }

    /** 时长：`h:mm:ss`（小时不封顶，9:05:03）；负值按 0 处理 */
    fun durationText(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * 配速（单位距离用时）：`12'34"`；`paceSecPerKm` 为 null（distance < 50m，F-HIS-34）
     * 或非正时返回 null，界面显示「—」。英制下按 1 英里用时输出。
     */
    fun paceText(paceSecPerKm: Long?): String? {
        if (paceSecPerKm == null || paceSecPerKm <= 0) return null
        val perKm = if (DisplayUnitProvider.units.distance == DistanceUnit.IMPERIAL) {
            (paceSecPerKm * DisplayUnitProvider.mPerMile / 1000.0).toLong()
        } else {
            paceSecPerKm
        }
        return String.format(Locale.ROOT, "%d'%02d\"", perKm / 60, perKm % 60)
    }

    /** 海拔/爬升：公制整数米（"1234 m"）；英制整数英尺（"4049 ft"）。null 按 0（调用方应优先用 common_stat_unknown） */
    fun metersText(meters: Double?): String {
        val v = meters ?: 0.0
        return if (DisplayUnitProvider.units.altitude == AltitudeUnit.FEET) {
            "${(v / DisplayUnitProvider.mPerFoot).toInt()} ft"
        } else {
            "${v.toInt()} m"
        }
    }

    /** 速度：米/秒 → "4.2 km/h"（英制 "2.6 mph"）；null 或非正返回 null（界面显示「—」） */
    fun speedText(speedMps: Double?): String? {
        if (speedMps == null || speedMps <= 0.0) return null
        return if (DisplayUnitProvider.units.distance == DistanceUnit.IMPERIAL) {
            String.format(Locale.ROOT, "%.1f mph", speedMps * DisplayUnitProvider.mpsToMph)
        } else {
            String.format(Locale.ROOT, "%.1f km/h", speedMps * DisplayUnitProvider.mpsToKmh)
        }
    }

    /** 卡路里：整数 "512 kcal"；null 或非正返回 null（界面显示「—」） */
    fun kcalText(kcal: Double?): String? {
        if (kcal == null || kcal <= 0.0) return null
        return "${kcal.toInt()} kcal"
    }

    /**
     * 文件体积：`< 1024 MB` 显示整 MB（"512 MB"），否则两位小数 GB（"1.46 GB"）；
     * 负值按 0 处理。单位符号是协议、不随语言变（同 [distanceText] 的口径），
     * 语言相关的提示文案由调用方走 stringResource（DEV 决策 8）。
     *
     * F-IO-64：导入预览页展示 ZIP 体积用。
     */
    fun bytesText(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        val mb = b / (1024.0 * 1024.0)
        return if (mb < 1024.0) {
            "${mb.toLong()} MB"
        } else {
            String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
        }
    }

    /**
     * 预计耗时（粗估，秒）：按 [bytesPerSec] 的吞吐假设向上取整。
     * 只返回**秒数**，不拼「分钟 / 秒」文案——单位词随语言，由 UI 用 stringResource 格式化
     * （DEV 决策 8；调用方可直接用 [durationText] 渲染）。
     */
    fun estimatedSec(bytes: Long, bytesPerSec: Long): Long {
        if (bytes <= 0 || bytesPerSec <= 0) return 0L
        return (bytes + bytesPerSec - 1) / bytesPerSec // 向上取整
    }

    /**
     * H-02 配速/速度二选一：unitPaceDisplay = pace 时返回配速，speed 时返回速度；
     * 两者都取不到时返回 null（界面显示「—」）。
     */
    fun paceOrSpeedText(paceSecPerKm: Long?, speedMps: Double?): String? =
        if (DisplayUnitProvider.units.pace == PaceDisplay.SPEED) {
            speedText(speedMps) ?: paceText(paceSecPerKm)
        } else {
            paceText(paceSecPerKm) ?: speedText(speedMps)
        }
}
