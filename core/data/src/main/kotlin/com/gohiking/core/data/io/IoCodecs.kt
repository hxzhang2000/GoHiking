package com.gohiking.core.data.io

import com.gohiking.core.common.geo.PolylineJson
import com.gohiking.core.model.LatLngValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * IO 编解码公共件：Json 配置（兼容约定 F-IO-50/51）、ISO 8601 时间、
 * 紧凑轨迹点行列转换、planned_leg.polylineJson 编解码、球面距离。
 */
object IoCodecs {

    /** 解析端：忽略未知字段 + 全字段默认值（PRD 7.3 向后兼容约定）。
     *  encodeDefaults 必须显式 true（kotlinx 默认 false）：schema/schemaVersion 有默认值但必须输出（识别文件类型用）。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val gpxUtcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))

    /** epoch millis → ISO 8601 本地时区（人类可读时间字段，PRD 7.3） */
    fun toIso(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(isoFormatter)

    /** ISO 8601 → epoch millis；格式非法或 null → fallback */
    fun fromIso(text: String?, fallback: Long): Long = try {
        OffsetDateTime.parse(text, isoFormatter).toInstant().toEpochMilli()
    } catch (t: Throwable) {
        fallback
    }

    /** GPX <time>：xsd:dateTime UTC（GPX 1.1 惯例） */
    fun toGpxUtc(ms: Long): String = gpxUtcFormatter.format(Instant.ofEpochMilli(ms))

    // ---- 轨迹点紧凑数组 ----

    fun numberOrNull(v: Double?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    fun longOrNull(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    fun rowDouble(row: List<JsonElement?>, index: Int): Double? =
        (row.getOrNull(index) as? JsonPrimitive)?.doubleOrNull

    fun rowLong(row: List<JsonElement?>, index: Int): Long? =
        (row.getOrNull(index) as? JsonPrimitive)?.longOrNull

    // ---- planned_leg.polylineJson（[[lat,lng],…]）----

    /**
     * N-37：此前这里是与 `PolylineJson` **完全独立**的第二套实现（自己拼字符串 /
     * 自己 `Json.decodeFromString`）。同一份磁盘格式两套编解码、且互不出测试：
     * 导入链路用本类写库（`ImportEngine`），记录页用 `PolylineJson` 读库
     * （`RecordingScreen`），任一侧格式漂移都会静默读到空折线或直接崩。
     * 现在统一委托到 core:common 的 [PolylineJson] —— 单一实现、纯 Kotlin、可单测。
     */
    fun encodePolyline(points: List<Pair<Double, Double>>): String =
        PolylineJson.encode(points.map { LatLngValue(it.first, it.second) })

    fun decodePolyline(json: String?): List<Pair<Double, Double>> =
        PolylineJson.decode(json).map { it.latitude to it.longitude }

    /**
     * 球面两点距离（米，haversine）。导入端重算 track_point.distanceM 用
     * （导出字段不含 distanceM，PRD 7.3 trackPoints.fields；F-HIS-23/25 依赖该列）。
     */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
