package com.gohiking.core.data.io

import com.gohiking.core.common.geo.PolylineJson
import com.gohiking.core.model.LatLngValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N-37：**同一份磁盘格式（`planned_leg.polylineJson`）曾经有两套独立编解码** ——
 * `core:data` 的 [IoCodecs.encodePolyline]/[decodePolyline]（kotlinx + try/catch）与
 * `core:common` 的 [PolylineJson]（org.json，无防御）。
 *
 * 危险在于它们服务于**同一条数据的两端**：导入链路（`ImportEngine`）用 IoCodecs 写库，
 * 记录页（`RecordingScreen`）用 PolylineJson 读库。任一侧格式漂移，用户看到的就是
 * 「计划线路画不出来」或「打开记录页就崩」，而且两套单测都还是绿的。
 *
 * 现在 IoCodecs 已委托到 PolylineJson（单一实现），这组测试守住两件事：
 * ① 两边 API 对同一份字节的解释必须一致；② 导入写进去的东西，记录页那套必须读得出来。
 */
class PolylineInteropTest {

    private val pairs = listOf(30.0 to 120.0, 30.1 to 120.1, 30.25 to 120.75)
    private val latLngs = pairs.map { LatLngValue(it.first, it.second) }

    @Test
    fun `IoCodecs encode is readable by PolylineJson decode`() {
        val json = IoCodecs.encodePolyline(pairs)
        assertEquals(latLngs, PolylineJson.decode(json))
    }

    @Test
    fun `PolylineJson encode is readable by IoCodecs decodePolyline`() {
        val json = PolylineJson.encode(latLngs)
        assertEquals(pairs, IoCodecs.decodePolyline(json))
    }

    @Test
    fun `both encoders produce identical wire format`() {
        assertEquals(PolylineJson.encode(latLngs), IoCodecs.encodePolyline(pairs))
        assertEquals("[]", PolylineJson.encode(emptyList()))
        assertEquals("[]", IoCodecs.encodePolyline(emptyList()))
    }

    /** 真实链路模拟：导入写库 → 记录页读库 */
    @Test
    fun `import-written polyline is renderable by the recording screen path`() {
        // 导入端（ImportEngine 走 IoCodecs）落库的字符串
        val stored = IoCodecs.encodePolyline(pairs)
        // 记录端（RecordingScreen 走 PolylineJson）读出来画折线，要求 ≥2 点才画
        val pts = PolylineJson.decode(stored)
        assertEquals(3, pts.size)
        assertEquals(LatLngValue(30.0, 120.0), pts.first())
        assertEquals(LatLngValue(30.25, 120.75), pts.last())
    }

    /** 损坏数据：整条链路都不能抛（记录页没有 try/catch 保护） */
    @Test
    fun `corrupt stored polyline degrades to empty on both sides`() {
        for (bad in listOf("not json", "[[1.0]]", "[[1.0,2.0]", "null")) {
            assertEquals(emptyList<LatLngValue>(), PolylineJson.decode(bad))
            assertEquals(emptyList<Pair<Double, Double>>(), IoCodecs.decodePolyline(bad))
        }
    }
}
