package com.gohiking.core.elevation.source

import com.gohiking.core.model.LatLngValue

/**
 * 高程数据源接口（DEV §4.10）。
 * 坐标一律 **WGS-84**（DEM 是 WGS-84 网格）；GCJ-02 → WGS-84 的转换由 ElevationRepository 统一完成，
 * 数据源实现不做坐标系假设。
 */
interface ElevationRemoteSource {
    /**
     * 单批查询（**≤100 点**，DEV §4.7 批量上限；分批由 Repository 负责）。
     * @return 与输入等长；查不到的点为 null；**整批失败返回 null**（与「部分点无值」区分）。
     */
    suspend fun queryBatchWgs84(points: List<LatLngValue>): List<Double?>?
}

/**
 * 本地 DEM 瓦片源（DEV §4.10 路径 C，降级顺序在远程服务之前）。
 * M2 切片：只落接口与「始终未覆盖」实现（D-18）；按省预下载 30m 瓦片属后续里程碑。
 */
interface DemTileSource {
    /** @return 与输入等长；本源不覆盖的点为 null；**完全未覆盖返回 null** → 降级到远程 */
    suspend fun lookupWgs84(points: List<LatLngValue>): List<Double?>?
}
