package com.gohiking.core.elevation.source

import com.gohiking.core.model.LatLngValue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M2 切片的本地瓦片源：始终返回「未覆盖」（D-18）。
 * 降级链为 缓存 → 本地瓦片 → 远程；本实现让链路直达远程，行为与 DEV §4.10 表一致。
 * 后续落地「按省预下载 30m 瓦片 + 双线性插值」时替换此实现即可，接口不变。
 */
@Singleton
class EmptyDemTileSource @Inject constructor() : DemTileSource {
    override suspend fun lookupWgs84(points: List<LatLngValue>): List<Double?>? = null
}
