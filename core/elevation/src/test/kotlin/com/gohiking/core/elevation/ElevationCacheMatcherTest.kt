package com.gohiking.core.elevation

import com.gohiking.core.database.entity.ElevationCacheRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 高程缓存精确回配（DEV §3.2 硬规则；文档审阅 D3）。
 *
 * 覆盖的是那个「查得到但会配错」的坑：`IN(latKeys) AND IN(lngKeys)` 会返回跨点错配行。
 */
class ElevationCacheMatcherTest {

    private fun row(lat: Double, lng: Double, alt: Double) =
        ElevationCacheRow(latKey = lat, lngKey = lng, altitudeM = alt)

    @Test
    fun `cross paired row is dropped not matched`() {
        // 请求 A(30.1,120.1) 与 B(30.2,120.2)；库里只有错配的 C(30.1,120.2)
        val wanted = setOf(
            ElevationCacheMatcher.key(30.1, 120.1),
            ElevationCacheMatcher.key(30.2, 120.2),
        )
        val hits = ElevationCacheMatcher.pair(listOf(row(30.1, 120.2, 999.0)), wanted)
        assertEquals(emptyMap<String, Double>(), hits) // C 绝不能被当成 A 或 B 的高程
    }

    @Test
    fun `three point batch keeps each value on its own coordinate`() {
        val a = ElevationCacheMatcher.key(30.00001, 120.00001)
        val b = ElevationCacheMatcher.key(30.00002, 120.00002)
        val c = ElevationCacheMatcher.key(30.00003, 120.00003)
        val rows = listOf(
            row(30.00001, 120.00001, 100.0),
            row(30.00002, 120.00002, 200.0),
            row(30.00003, 120.00003, 300.0),
            row(30.00001, 120.00003, -1.0), // 跨点错配行（笛卡尔积副产物）
            row(30.00003, 120.00001, -2.0),
        )
        val hits = ElevationCacheMatcher.pair(rows, setOf(a, b, c))
        assertEquals(3, hits.size)
        assertEquals(100.0, hits[a]!!, 0.0)
        assertEquals(200.0, hits[b]!!, 0.0)
        assertEquals(300.0, hits[c]!!, 0.0)
    }

    @Test
    fun `missing points are absent so caller shows unknown`() {
        val wanted = setOf(
            ElevationCacheMatcher.key(30.1, 120.1),
            ElevationCacheMatcher.key(30.2, 120.2),
        )
        val hits = ElevationCacheMatcher.pair(listOf(row(30.1, 120.1, 512.0)), wanted)
        assertEquals(1, hits.size)
        assertNull(hits[ElevationCacheMatcher.key(30.2, 120.2)]) // 缺 → null，绝不沿用邻点
        assertFalse(hits.containsKey(ElevationCacheMatcher.key(30.2, 120.2)))
    }

    @Test
    fun `key of LatLngValue matches key of raw coordinates`() {
        val p = com.gohiking.core.model.LatLngValue(30.00001, 120.00002)
        assertEquals(ElevationCacheMatcher.key(30.00001, 120.00002), ElevationCacheMatcher.key(p))
    }
}
