package com.gohiking.feature.history

import timber.log.Timber

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.common.geo.PolylineSimplifier
import com.gohiking.core.data.media.MediaRepository
import com.gohiking.core.data.media.MediaTripMatcher
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.data.stats.GainSplit
import com.gohiking.core.data.stats.KmSplit
import com.gohiking.core.data.stats.Legs
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.MediaIndexEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.model.LatLngValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * 行程详情（P-11 的 M1 最小切片，F-HIS-20~22/26/27/30）。
 * - trip 订阅 Flow：重命名 / 编辑备注后自动刷新（F-HIS-20 名称可编辑）；
 * - 点 / 标记 / 上下山切分一次拉取：渲染层抽稀（DEV 决策 11，数据库永远原始点）；
 * - legs 无登顶点为 null（F-REC-37），界面显示「没有登顶标记」。
 */
data class TripDetailUiState(
    val trip: TripEntity? = null,
    val loading: Boolean = true,
    /** 渲染用轨迹：每 segment 一条折线（quality==0，抽稀后），跨段绝不连线 */
    val segments: List<List<LatLngValue>> = emptyList(),
    val markers: List<MarkerEntity> = emptyList(),
    val legs: Legs? = null,
    /** F-HIS-23 海拔曲线（距离 m, 海拔 m），抽稀降采样后 ≤600 点 */
    val altitudeSeries: List<Pair<Double, Double>> = emptyList(),
    /** F-HIS-25 分段表 */
    val kmSplits: List<KmSplit> = emptyList(),
    val gainSplits: List<GainSplit> = emptyList(),
    /**
     * F-HIS-28 / F-MEDIA-40 照片缩略图条。
     * null = 无媒体权限（整段隐藏，不打扰）；非 null = 权限已授（可能为空列表 = 无关联照片）。
     */
    val photos: List<MediaIndexEntity>? = null,
    val deleted: Boolean = false, // 删除完成后由壳层导航返回列表
)

class TripDetailViewModel(
    private val repo: TripRepository,
    private val mediaRepository: MediaRepository,
    private val tripId: String,
    /** 应用级 context 由壳层传入（media 权限与 MediaStore 访问用）；history 模块不引 Hilt */
    private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailUiState())
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeById(tripId).collect { t ->
                _state.update { it.copy(trip = t, loading = it.loading && t == null) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val pts = repo.pointsOf(tripId).filter { it.quality == 0 }
            val segs = ArrayList<List<LatLngValue>>()
            var i = 0
            while (i < pts.size) {
                var j = i
                while (j < pts.size && pts[j].segmentIndex == pts[i].segmentIndex) j++
                val simplified = PolylineSimplifier.simplify(
                    pts.subList(i, j).map { LatLngValue(it.latitude, it.longitude) },
                    RENDER_EPSILON_M, // 详情页静态渲染固定容差；按缩放联动抽稀属 M4（DEV §4.5）
                )
                if (simplified.size >= 2) segs.add(simplified)
                i = j
            }
            val markers = repo.markersOf(tripId)
            val legs = repo.legs(tripId)
            val altitudeSeries = repo.chartSeries(tripId)
                .mapNotNull { row ->
                    val alt = row.altitude
                    val dist = row.distanceM
                    if (alt != null && dist != null) dist to alt else null
                }
                .downsample(MAX_CHART_POINTS)
            val km = repo.kmSplits(tripId)
            val gain = repo.gainSplits(tripId)
            _state.update {
                it.copy(
                    segments = segs,
                    markers = markers,
                    legs = legs,
                    altitudeSeries = altitudeSeries,
                    kmSplits = km,
                    gainSplits = gain,
                )
            }
            loadPhotos(pts)
        }
    }

    /**
     * F-HIS-28 / F-MEDIA-40 照片条：
     * 1) 无媒体权限 → photos = null，照片区整段隐藏（权限引导在照片地图页 P-12，详情页静默）；
     * 2) 有权限 → 先增量扫描（F-MEDIA-09，幂等），再按 F-MEDIA-41/43 判定加载关联照片。
     */
    private suspend fun loadPhotos(points: List<com.gohiking.core.database.entity.TrackPointEntity>) {
        if (!mediaRepository.hasMediaAccess(appContext)) {
            _state.update { it.copy(photos = null) }
            return
        }
        runCatching { mediaRepository.rescan(appContext) }
            .onFailure { Timber.w(it, "媒体增量扫描失败") }
        // H-13：此前直接读 _state.value.trip，而 trip 由另一个协程的 observeById 写入，
        // 两者无同步 —— IO 太快时 trip 还是 null，照片条就永久不显示且无重试。
        // 改为直接向仓库取一次（repo 已有 byId），拿不到就等 flow 的首个非空值。
        var trip = repo.byId(tripId)
        if (trip == null) {
            withTimeoutOrNull(LOAD_TRIP_TIMEOUT_MS) {
                repo.observeById(tripId).first { it != null }
            }?.let { trip = it }
        }
        val t = trip ?: return
        val end = t.endTime ?: points.maxOfOrNull { it.timestamp } ?: t.startTime
        // M：photosForTrip 裸调用，MediaStore 异常会直接打到全局 handler
        val photos = runCatching {
            mediaRepository.photosForTrip(
                tripId = tripId,
                tripStartMs = t.startTime,
                tripEndMs = end,
                trackPointsGcj = points.map { MediaTripMatcher.TrackPointGcj(it.latitude, it.longitude) },
            )
        }.onFailure { Timber.w(it, "照片匹配失败") }.getOrDefault(emptyList())
        _state.update { it.copy(photos = photos) }
    }

    /** F-HIS-30 重命名 */
    fun rename(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch { repo.rename(tripId, n) }
    }

    /** F-HIS-30 编辑备注 */
    fun updateNote(note: String) {
        viewModelScope.launch { repo.updateNote(tripId, note.trim().ifEmpty { null }) }
    }

    /** F-HIS-30 删除（仓库层级联：点/标记/媒体引用随行程删） */
    fun delete() {
        viewModelScope.launch {
            repo.delete(tripId)
            _state.update { it.copy(deleted = true) }
        }
    }

    private companion object {
        const val RENDER_EPSILON_M = 12.0

        /** 图表点数上限（DEV §3.2：抽稀/降采样交给渲染层，1 万点塞进 Compose 会卡） */
        const val MAX_CHART_POINTS = 600

        /**
         * H-13：等待 trip 落库的超时。`repo.byId()` 取不到时退化为等 flow 首个非空值，
         * 超时即放弃照片匹配（不阻塞详情页）。
         */
        const val LOAD_TRIP_TIMEOUT_MS = 5_000L
    }
}

/** 等距降采样：保留首尾点，中间隔 n 取 1（O(n)，曲线形状基本不变） */
private fun List<Pair<Double, Double>>.downsample(maxPoints: Int): List<Pair<Double, Double>> {
    if (size <= maxPoints) return this
    val step = size.toDouble() / maxPoints
    val out = ArrayList<Pair<Double, Double>>(maxPoints + 1)
    var i = 0.0
    while (i < size - 1) {
        out.add(this[i.toInt()])
        i += step
    }
    out.add(this[size - 1])
    return out
}
