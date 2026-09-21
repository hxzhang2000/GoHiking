package com.gohiking.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.database.entity.PlannedRouteWithLegs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 列表条目（F-PLAN-41）：名称/总距离/总爬升/创建时间 */
data class PlanListItem(
    val id: String,
    val name: String,
    val totalDistanceM: Double,
    // H-06：高程不可用（null）时不得用 0 冒充，列表展示「—」
    val totalAscentM: Double?,
    val createdAt: Long,
    val legCount: Int,
    // P-07 原型列表展示难度（取去程段；高程不可用时为 null）
    val difficulty: String?,
)

class PlanListViewModel(
    private val plannedRouteDao: PlannedRouteDao,
) : ViewModel() {

    /** F-PLAN-41：计划线路列表（创建时间倒序，Room Flow 自动刷新） */
    val items: StateFlow<List<PlanListItem>> = plannedRouteDao.observeAllWithLegs()
        .map { routes -> routes.map { it.toItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** F-PLAN-42：重命名 */
    fun rename(id: String, name: String) {
        viewModelScope.launch {
            val w = plannedRouteDao.withLegs(id) ?: return@launch
            if (name.isNotBlank()) {
                plannedRouteDao.update(w.route.copy(name = name.trim()))
            }
        }
    }

    /** F-PLAN-42：删除（legs/waypoints 级联） */
    fun delete(id: String) {
        viewModelScope.launch { plannedRouteDao.deleteById(id) }
    }
}

private fun PlannedRouteWithLegs.toItem() = PlanListItem(
    id = route.id,
    name = route.name,
    totalDistanceM = route.totalDistanceM,
    totalAscentM = route.totalAscentM,
    createdAt = route.createdAt,
    legCount = legs.size,
    difficulty = legs.firstOrNull { it.legType == "OUTBOUND" }?.difficulty,
)
