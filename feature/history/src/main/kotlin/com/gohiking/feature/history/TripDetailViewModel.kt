package com.gohiking.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.common.geo.PolylineSimplifier
import com.gohiking.core.data.repository.TripRepository
import com.gohiking.core.data.stats.Legs
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.model.LatLngValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val deleted: Boolean = false, // 删除完成后由壳层导航返回列表
)

class TripDetailViewModel(
    private val repo: TripRepository,
    private val tripId: String,
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
            _state.update { it.copy(segments = segs, markers = markers, legs = legs) }
        }
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
    }
}
