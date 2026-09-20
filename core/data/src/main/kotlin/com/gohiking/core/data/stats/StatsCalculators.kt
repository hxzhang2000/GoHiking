package com.gohiking.core.data.stats

import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.location.altitude.ThresholdAccumulator
import com.gohiking.core.location.geo.GeoMath
import com.gohiking.core.model.TripStats

/**
 * 统计口径的唯一权威定义在 PRD 6.5.2「统计口径定义」（DEV §4.12 只给实现落点）。
 *
 * 关键口径：
 * - 只有 quality==0 的点参与距离/速度/海拔极值/卡路里（quality!=0 的点仍参与绘制）；
 * - 段内用时 = 相邻有效点时间差之和，且只在同一 segmentIndex 内累加（跨段 = 暂停，不计）；
 * - 暂停时长无法从点反推 → 每段运动秒数必须由 RecordingSession 落库（movingSecBySegmentJson）；
 * - 爬升/下降阈值 3m（有气压计）/ 10m（无气压计），与打点累加器独立（DEV 决策 5）。
 *
 * @param points 已按 (segmentIndex, seq) 排序的原始轨迹点
 * @param distanceOverrideM RecordingSession 已按口径累加的距离（track_point.distanceM 最后有效值）；
 *        null 时本地重算（同一口径，导入退化路径）
 */
object TripStatsCalculator {

    fun compute(
        points: List<TrackPointEntity>,
        markers: List<MarkerEntity>,
        movingSecBySegment: Map<Int, Long>,
        ascentThresholdM: Double = 10.0,
        bodyWeightKg: Int = 65,
        distanceOverrideM: Double? = null,
    ): TripStats {
        val valid = points.filter { it.quality == 0 }
        val distanceM = distanceOverrideM ?: sumDistance(valid)
        val movingSec = movingSecBySegment.values.sum()
        val maxSpeed = valid.mapNotNull { it.speedMps }.maxOrNull()
        val alts = valid.mapNotNull { it.altitude }

        // 爬升/下降：阈值法重算（与打点累加器独立，DEV 决策 5）
        val acc = ThresholdAccumulator(ascentThresholdM)
        alts.forEach { acc.accept(it) }

        val totalSec = if (points.isEmpty()) 0L else (points.last().timestamp - points.first().timestamp) / 1000

        return TripStats(
            distanceM = distanceM,
            durationSec = totalSec,
            movingDurationSec = movingSec,
            pausedDurationSec = (totalSec - movingSec).coerceAtLeast(0),
            totalAscentM = acc.ascent,
            totalDescentM = acc.descent,
            maxAltitudeM = alts.maxOrNull(),
            minAltitudeM = alts.minOrNull(),
            avgSpeedMps = if (movingSec >= 1) distanceM / movingSec else null,
            avgPaceSecPerKm = if (distanceM >= 50 && movingSec > 0) (movingSec / (distanceM / 1000.0)).toLong() else null,
            maxSpeedMps = maxSpeed,
            steps = -1, // 由调用方从 trip 表回填
            caloriesKcal = CalorieCalculator.estimate(points, bodyWeightKg),
        )
    }

    /** 相邻有效点距离之和（跨 segment 不累加；与 RecordingSession 实时累加同口径） */
    fun sumDistance(valid: List<TrackPointEntity>): Double {
        var sum = 0.0
        for (i in 1 until valid.size) {
            val a = valid[i - 1]
            val b = valid[i]
            if (a.segmentIndex != b.segmentIndex) continue
            sum += GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return sum
    }
}

/** 卡路里估算公式 v1（PRD 6.5.2）：kcal = Σ MET_seg × 体重 × 段运动秒 / 3600 */
object CalorieCalculator {
    private const val MET_UPHILL = 7.0
    private const val MET_DOWNHILL = 4.0
    private const val MET_FLAT = 4.5
    private const val SLOPE_THRESHOLD = 0.03 // 净海拔斜率 ±3%
    private const val WINDOW_M = 100.0 // 每 100m 运动距离一个窗口；尾段不足并入前一窗口
    private const val MIN_MOVING_SEC = 60L

    /**
     * @return kcal；运动时长（段内时间差推出）< 60s 时返回 null（界面「—」，导出写 null）。
     * 海拔缺失的增量按 0 计（窗口斜率无法判上/下坡 → 平地 MET，绝不编造海拔）
     */
    fun estimate(points: List<TrackPointEntity>, bodyWeightKg: Int): Double? {
        val valid = points.filter { it.quality == 0 }
        if (valid.isEmpty()) return null

        var kcal = 0.0
        var winDist = 0.0
        var winSec = 0L
        var winDAlt = 0.0

        fun closeWindow() {
            if (winDist <= 0.0 || winSec <= 0L) return
            val slope = winDAlt / winDist
            val met = when {
                slope > SLOPE_THRESHOLD -> MET_UPHILL
                slope < -SLOPE_THRESHOLD -> MET_DOWNHILL
                else -> MET_FLAT
            }
            kcal += met * bodyWeightKg * winSec / 3600.0
        }

        var movingSec = 0L
        for (i in 1 until valid.size) {
            val a = valid[i - 1]
            val b = valid[i]
            if (a.segmentIndex != b.segmentIndex) continue // 跨段时间差 = 暂停，不计
            val dist = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val sec = ((b.timestamp - a.timestamp) / 1000).coerceAtLeast(0)
            movingSec += sec
            winDist += dist
            winSec += sec
            // 跨模块属性不能 smart cast，先取局部变量（Room 实体在另一模块）
            val aAlt = a.altitude
            val bAlt = b.altitude
            winDAlt += if (aAlt != null && bAlt != null) bAlt - aAlt else 0.0
            if (winDist >= WINDOW_M) {
                closeWindow()
                winDist = 0.0; winSec = 0L; winDAlt = 0.0
            }
        }
        closeWindow() // 尾段并入（直接结算剩余窗口）
        if (movingSec < MIN_MOVING_SEC) return null
        return kcal
    }
}

/** 分段表（F-HIS-25）：按累计运动距离每 1km / 累计爬升每 N m 切段；尾段照常列出 */
object SplitsCalculator {

    fun byKilometer(points: List<TrackPointEntity>): List<KmSplit> {
        val valid = points.filter { it.quality == 0 }
        if (valid.isEmpty()) return emptyList()
        val out = ArrayList<KmSplit>()
        out.add(KmSplit(0, 0.0, 0, null))
        var index = 0
        for (i in 1 until valid.size) {
            val a = valid[i - 1]
            val b = valid[i]
            if (a.segmentIndex != b.segmentIndex) continue // 暂停不计入任何段
            var remaining = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            var remainingSec = ((b.timestamp - a.timestamp) / 1000).coerceAtLeast(0)
            // 距离按比例切分到跨界的段
            while (out[index].distanceM + remaining >= 1000.0) {
                val need = 1000.0 - out[index].distanceM
                val frac = if (remaining > 0) need / remaining else 1.0
                out[index] = out[index].copy(
                    distanceM = 1000.0,
                    movingSec = out[index].movingSec + (remainingSec * frac).toLong(),
                )
                index++
                out.add(KmSplit(index, 0.0, 0, null))
                remaining -= need
                remainingSec = (remainingSec * (1 - frac)).toLong()
            }
            out[index] = out[index].copy(
                distanceM = out[index].distanceM + remaining,
                movingSec = out[index].movingSec + remainingSec,
            )
        }
        return out.map {
            it.copy(paceSecPerKm = pace(it.distanceM, it.movingSec))
        }
    }

    fun byAltitudeGain(points: List<TrackPointEntity>, ascentThresholdM: Double): List<GainSplit> {
        val valid = points.filter { it.quality == 0 }
        val alts = valid.filter { it.altitude != null }
        if (alts.isEmpty()) return emptyList()
        val out = ArrayList<GainSplit>()
        out.add(GainSplit(0, 0.0, 0.0, 0, null))
        var index = 0
        val acc = ThresholdAccumulator(ascentThresholdM)
        var confirmedGain = 0.0
        for (i in 1 until alts.size) {
            val a = alts[i - 1]
            val b = alts[i]
            if (a.segmentIndex != b.segmentIndex) continue
            val dist = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val sec = ((b.timestamp - a.timestamp) / 1000).coerceAtLeast(0)
            val before = acc.ascent
            val counted = acc.accept(b.altitude!!)
            confirmedGain = acc.ascent
            out[index] = out[index].copy(
                distanceM = out[index].distanceM + dist,
                movingSec = out[index].movingSec + sec,
                gainM = out[index].gainM + (acc.ascent - before).let { if (counted) it else 0.0 },
            )
            // 确认爬升满阈值 → 切新段（尾段不足照常输出）
            if (confirmedGain >= (index + 1) * ascentThresholdM) {
                index++
                out.add(GainSplit(index, 0.0, 0.0, 0, null))
            }
        }
        return out.map {
            it.copy(paceSecPerKm = pace(it.distanceM, it.movingSec))
        }
    }

    private fun pace(distanceM: Double, movingSec: Long): Long? =
        if (distanceM >= 50 && movingSec > 0) (movingSec / (distanceM / 1000.0)).toLong() else null
}

/** 上下山切分（F-REC-37）：以时间上最后一个 SUMMIT 为分界；无登顶点返回 null */
object LegSplitter {

    fun split(points: List<TrackPointEntity>, markers: List<MarkerEntity>): Legs? {
        val summit = markers
            .filter { it.type == "SUMMIT" }
            .maxByOrNull { it.timestamp } ?: return null
        val valid = points.filter { it.quality == 0 }
        if (valid.isEmpty()) return null
        // 分界点：轨迹上第一个时间 >= 登顶时间戳的有效点（含该点在两侧重复计一次，保证两段衔接）
        val idx = valid.indexOfFirst { it.timestamp >= summit.timestamp }
        if (idx <= 0 || idx >= valid.size) return null

        fun summarize(range: List<TrackPointEntity>, thresholdM: Double = 10.0): LegSummary {
            val acc = ThresholdAccumulator(thresholdM)
            var dist = 0.0
            var sec = 0L
            for (i in 1 until range.size) {
                val a = range[i - 1]
                val b = range[i]
                if (a.segmentIndex != b.segmentIndex) continue
                dist += GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                sec += ((b.timestamp - a.timestamp) / 1000).coerceAtLeast(0)
                b.altitude?.let { acc.accept(it) }
            }
            return LegSummary(dist, sec, acc.ascent, acc.descent)
        }

        return Legs(
            uphill = summarize(valid.subList(0, idx + 1)),
            downhill = summarize(valid.subList(idx, valid.size)),
        )
    }
}
