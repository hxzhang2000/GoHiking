package com.gohiking.core.elevation

import android.util.LruCache
import com.gohiking.core.common.result.GhError
import com.gohiking.core.common.result.GhResult
import com.gohiking.core.database.dao.ElevationCacheDao
import com.gohiking.core.database.entity.ElevationCacheEntity
import com.gohiking.core.elevation.source.DemTileSource
import com.gohiking.core.elevation.source.ElevationRemoteSource
import com.gohiking.core.location.crs.CoordinateConverter
import com.gohiking.core.model.LatLngValue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 高程查询（DEV §4.10 三级降级）：
 * ① 内存 LruCache / DB 缓存（key = WGS-84 5 位小数，约 1m 格网，DEM 不变缓存长期有效）
 * ② 本地 DEM 瓦片（M2 为始终未覆盖的占位实现，D-18）
 * ③ 远程公开高程服务（Open-Meteo，批量 ≤100 点/批，6s 超时）
 *
 * 坐标协议：**输入是 GCJ-02**（项目内部统一坐标系，DEV 决策 2），查询前统一转 WGS-84。
 * 降级硬规则（PRD 6.1）：不可用的点为 null，**绝不编造数值、绝不沿用上一点**；
 * 全部不可用 → GhError.ElevationUnavailable。
 */
@Singleton
class ElevationRepository @Inject constructor(
    private val remote: ElevationRemoteSource,
    private val localTiles: DemTileSource,
    private val cacheDao: ElevationCacheDao,
) {

    private val mem = LruCache<String, Double>(MEMORY_CACHE_SIZE)

    suspend fun query(points: List<LatLngValue>): GhResult<List<Double?>> =
        withContext(Dispatchers.IO) {
            if (points.isEmpty()) return@withContext GhResult.Ok(emptyList())

            // ── WGS-84 化 + 5 位小数格网 key ──
            val wgs = points.map { p ->
                val (la, ln) = CoordinateConverter.gcj02ToWgs84(p.latitude, p.longitude)
                LatLngValue(key5(la), key5(ln))
            }
            val keys = wgs.map { cacheKey(it) }

            // ── ① 内存 + DB 缓存 ──
            val result = arrayOfNulls<Double>(points.size)
            val missIdx = ArrayList<Int>(points.size)
            for (i in keys.indices) {
                val memHit = mem.get(keys[i])
                if (memHit != null) {
                    result[i] = memHit
                } else {
                    missIdx.add(i)
                }
            }
            if (missIdx.isNotEmpty()) {
                val dbHits = lookupDb(wgs, missIdx)
                for (i in missIdx) {
                    val v = dbHits[cacheKey(wgs[i])]
                    if (v != null) {
                        result[i] = v
                        mem.put(keys[i], v)
                    }
                }
            }
            val stillMiss = (0 until points.size).filter { result[it] == null }
            if (stillMiss.isEmpty()) return@withContext GhResult.Ok(result.toList())

            // ── ② 本地 DEM 瓦片（M2 占位实现恒未覆盖） ──
            val tileResult = localTiles.lookupWgs84(stillMiss.map { wgs[it] })
            if (tileResult != null) {
                val stillMiss2 = ArrayList<Int>(stillMiss.size)
                for ((j, i) in stillMiss.withIndex()) {
                    val v = tileResult[j]
                    if (v != null) {
                        result[i] = v
                        mem.put(keys[i], v)
                        persist(keys[i], wgs[i], v, SOURCE_DEM_TILE)
                    } else {
                        stillMiss2.add(i)
                    }
                }
                stillMiss2
            } else {
                stillMiss
            }.let { missAfterTile ->
                if (missAfterTile.isEmpty()) return@withContext GhResult.Ok(result.toList())

                // ── ③ 远程批量（≤100/批，串行；T-07：必须自带超时与缓存） ──
                val fetched = HashMap<Int, Double>()
                var anyBatchSucceeded = false
                missAfterTile.chunked(REMOTE_BATCH_SIZE).forEach { batch ->
                    val batchResult = remote.queryBatchWgs84(batch.map { wgs[it] })
                    if (batchResult != null) {
                        anyBatchSucceeded = true
                        for ((j, i) in batch.withIndex()) {
                            val v = batchResult[j]
                            if (v != null) {
                                fetched[i] = v
                                mem.put(keys[i], v)
                                persist(keys[i], wgs[i], v, SOURCE_REMOTE)
                            }
                        }
                    }
                }
                if (fetched.isNotEmpty()) {
                    // 批量落库（ElevationCacheEntity.upsert）
                    cacheDao.upsert(
                        fetched.map { (i, v) ->
                            ElevationCacheEntity(
                                latKey = wgs[i].latitude,
                                lngKey = wgs[i].longitude,
                                altitudeM = v,
                                source = SOURCE_REMOTE,
                                fetchedAt = System.currentTimeMillis(),
                            )
                        },
                    )
                }
                for ((i, v) in fetched) result[i] = v
                if (!anyBatchSucceeded && fetched.isEmpty()) {
                    return@withContext GhResult.Err(GhError.ElevationUnavailable("all remote batches failed"))
                }
            }

            // 部分点可用：Ok + null（界面按「—」显示，绝不编造）
            GhResult.Ok(result.toList())
        }

    /**
     * DB 精确回配（SupportDaos 硬规则）：IN(latKeys) AND IN(lngKeys) 会返回跨点错配行，
     * 必须按 (latKey, lngKey) 精确配对。
     */
    private suspend fun lookupDb(
        wgs: List<LatLngValue>,
        missIdx: List<Int>,
    ): Map<String, Double> {
        // N-39：Android ≤ 11（SQLite < 3.32）的变量数上限是 999，而这里一次查询要传
        // **两个** IN 列表（latKey IN (...) AND lngKey IN (...)）。长线路一次请求几千点
        // 就会撞上 "too many SQL variables"(SQLiteException)，高程整条降级为 null。
        // 按块拆分：每块自身经纬度去重后总和远低于 999，且块的笛卡尔积一定覆盖
        // 该块内所有请求点（经纬度都取自同一块），语义不变。
        val out = HashMap<String, Double>()
        for (chunk in missIdx.chunked(DB_LOOKUP_CHUNK)) {
            val latKeys = chunk.map { wgs[it].latitude }.distinct()
            val lngKeys = chunk.map { wgs[it].longitude }.distinct()
            val rows = cacheDao.query(latKeys, lngKeys)
            // D3：DAO 是笛卡尔积语义，配对一律走 ElevationCacheMatcher（唯一实现，可单测）
            val wanted = chunk.mapTo(HashSet()) { ElevationCacheMatcher.key(wgs[it]) }
            out += ElevationCacheMatcher.pair(rows, wanted)
        }
        return out
    }

    private suspend fun persist(key: String, wgs: LatLngValue, v: Double, source: String) {
        // 单点路径（DEM 瓦片命中）即时落库；远程批在批处理处统一落库
        if (source == SOURCE_DEM_TILE) {
            cacheDao.upsert(
                listOf(
                    ElevationCacheEntity(
                        latKey = wgs.latitude,
                        lngKey = wgs.longitude,
                        altitudeM = v,
                        source = source,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    private fun key5(v: Double): Double = Math.round(v * 1e5) / 1e5

    // 组合键统一走 ElevationCacheMatcher（D3：配对实现的唯一来源，避免两处口径漂移）
    private fun cacheKey(lat5: Double, lng5: Double): String = ElevationCacheMatcher.key(lat5, lng5)

    private fun cacheKey(p: LatLngValue): String = ElevationCacheMatcher.key(p)

    companion object {
        const val SOURCE_REMOTE = "REMOTE"
        const val SOURCE_DEM_TILE = "DEM_TILE"

        /** DEV §4.7：同一批最多 100 点 */
        const val REMOTE_BATCH_SIZE = 100
        const val MEMORY_CACHE_SIZE = 8 * 1024

        /** N-39：单次缓存查询的点的上限（两个 IN 列表相加必须 < 999） */
        const val DB_LOOKUP_CHUNK = 450
    }
}
