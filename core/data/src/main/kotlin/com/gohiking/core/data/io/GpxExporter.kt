package com.gohiking.core.data.io

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.location.crs.CoordinateConverter

/**
 * GPX 1.1 导出（PRD 7.2.1，F-IO-04/14/15）。
 * - 恒用 WGS-84（GCJ-02 库内坐标逐点转换），与「导出坐标系」设置无关（F-IO-14 冻结决策 15）；
 * - 每个 segment 一个 <trkseg>，绝不合并（保住暂停断开语义）；
 * - 每个 marker 一个 <wpt>：<type> 枚举原文不翻译，<name> 本地化展示文本由调用方提供；
 * - 不写自定义 <extensions>（F-IO-15）；步数/卡路里/计划线路/照片等明确丢弃（F-IO-16，丢弃项提示由 UI 层负责）。
 */
object GpxExporter {

    fun export(
        trip: TripEntity,
        points: List<TrackPointEntity>,
        markers: List<MarkerEntity>,
        labelOf: (MarkerEntity) -> String,
    ): String = StringBuilder().apply {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GoHiking\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")

        // metadata：记录名 + 开始时间
        append("  <metadata>\n")
        append("    <name>").append(escape(trip.name)).append("</name>\n")
        append("    <time>").append(IoCodecs.toGpxUtc(trip.startTime)).append("</time>\n")
        append("  </metadata>\n")

        // 标记 → wpt
        for (m in markers.sortedBy { it.timestamp }) {
            val (wLat, wLng) = CoordinateConverter.gcj02ToWgs84(m.latitude, m.longitude)
            append("  <wpt lat=\"").append(wLat).append("\" lon=\"").append(wLng).append("\">\n")
            m.altitude?.let { append("    <ele>").append(it).append("</ele>\n") }
            append("    <time>").append(IoCodecs.toGpxUtc(m.timestamp)).append("</time>\n")
            append("    <name>").append(escape(labelOf(m))).append("</name>\n")
            m.note?.let { append("    <desc>").append(escape(it)).append("</desc>\n") }
            append("    <type>").append(escape(m.type)).append("</type>\n")
            append("  </wpt>\n")
        }

        // 轨迹：每段一个 trkseg
        append("  <trk>\n")
        append("    <name>").append(escape(trip.name)).append("</name>\n")
        points.groupBy { it.segmentIndex }.toSortedMap().forEach { (_, pts) ->
            append("    <trkseg>\n")
            for (p in pts.sortedBy { it.seq }) {
                val (wLat, wLng) = CoordinateConverter.gcj02ToWgs84(p.latitude, p.longitude)
                append("      <trkpt lat=\"").append(wLat).append("\" lon=\"").append(wLng).append("\">\n")
                p.altitude?.let { append("        <ele>").append(it).append("</ele>\n") }
                append("        <time>").append(IoCodecs.toGpxUtc(p.timestamp)).append("</time>\n")
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }.toString()

    /** XML 五个保留字符转义（属性/文本通用） */
    internal fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
