package com.gohiking.core.data.repository

import android.util.LruCache
import androidx.room.withTransaction
import com.gohiking.core.data.stats.GainSplit
import com.gohiking.core.data.stats.KmSplit
import com.gohiking.core.data.stats.LegSplitter
import com.gohiking.core.data.stats.Legs
import com.gohiking.core.data.stats.SplitsCalculator
import com.gohiking.core.database.GhDatabase
import com.gohiking.core.database.entity.MarkerEntity
import com.gohiking.core.database.entity.TrackPointEntity
import com.gohiking.core.database.entity.TripEntity
import com.gohiking.core.database.entity.TripSummaryRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 行程数据仓库（DEV §2.3）。
 * - 汇总统计只读 trip 表（保存记录时算一次落库，DEV §4.12「汇总统计要落库」）；
 * - splits / legs **不落库**：按需计算（O(n)）+ 内存 LruCache（key = tripId，DEV §4.12）；
 * - 删除为仓库层级联（点/标记/媒体引用随行程删，trip 无外键，DEV §3.4）。
 */
@Singleton
class TripRepository @Inject constructor(
    private val db: GhDatabase,
) {
    private val kmCache = LruCache<String, List<KmSplit>>(16)
    private val gainCache = LruCache<String, List<GainSplit>>(16)
    private val legsCache = LruCache<String, Legs>(16) // null 结果（无登顶点）不缓存，重算即可

    /** FINISHED 行程流（列表页，DEV 决策 13：只有 FINISHED 进列表与统计） */
    fun observeFinished(): Flow<List<TripEntity>> = db.tripDao().observeFinished()

    /** 列表汇总（记录数/总距离/总时长/总爬升投影） */
    fun observeSummary(): Flow<TripSummaryRow> = db.tripDao().observeSummary()

    suspend fun byId(id: String): TripEntity? = db.tripDao().byId(id)

    /** 单条行程流（详情页 F-HIS-20：重命名/编辑备注后自动刷新） */
    fun observeById(id: String): Flow<TripEntity?> = db.tripDao().observeById(id)

    /** 重命名（F-HIS-30） */
    suspend fun rename(id: String, name: String): Unit = withContext(Dispatchers.IO) {
        db.tripDao().byId(id)?.let { db.tripDao().update(it.copy(name = name)) }
    }

    /** 编辑备注（F-HIS-30）；null 清空备注 */
    suspend fun updateNote(id: String, note: String?): Unit = withContext(Dispatchers.IO) {
        db.tripDao().byId(id)?.let { db.tripDao().update(it.copy(note = note)) }
    }

    /** 级联删除：一个事务内删点/标记/媒体引用/行程，并失效分段缓存 */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.trackPointDao().deleteOf(id)
            db.markerDao().deleteOf(id)
            db.mediaDao().deleteRefsOf(id)
            db.tripDao().deleteById(id)
        }
        invalidateCaches(id)
    }

    suspend fun pointsOf(tripId: String): List<TrackPointEntity> =
        withContext(Dispatchers.IO) { db.trackPointDao().allOf(tripId) }

    suspend fun markersOf(tripId: String): List<MarkerEntity> =
        withContext(Dispatchers.IO) { db.markerDao().allOf(tripId) }

    /** 每公里分段（F-HIS-25） */
    suspend fun kmSplits(tripId: String): List<KmSplit> = withContext(Dispatchers.IO) {
        kmCache.get(tripId) ?: SplitsCalculator.byKilometer(db.trackPointDao().allOf(tripId))
            .also { kmCache.put(tripId, it) }
    }

    /** 每爬升分段；阈值取 trip.hasBarometer（3m / 10m，DEV 决策 4/5） */
    suspend fun gainSplits(tripId: String): List<GainSplit> = withContext(Dispatchers.IO) {
        gainCache.get(tripId) ?: run {
            val threshold = if (db.tripDao().byId(tripId)?.hasBarometer == true) 3.0 else 10.0
            SplitsCalculator.byAltitudeGain(db.trackPointDao().allOf(tripId), threshold)
                .also { gainCache.put(tripId, it) }
        }
    }

    /** 上下山切分（F-REC-37）；无登顶点返回 null */
    suspend fun legs(tripId: String): Legs? = withContext(Dispatchers.IO) {
        legsCache.get(tripId) ?: LegSplitter.split(
            db.trackPointDao().allOf(tripId),
            db.markerDao().allOf(tripId),
        )?.also { legsCache.put(tripId, it) }
    }

    private fun invalidateCaches(tripId: String) {
        kmCache.remove(tripId)
        gainCache.remove(tripId)
        legsCache.remove(tripId)
    }
}
