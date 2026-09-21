package com.gohiking.core.common.geo

import com.gohiking.core.model.LatLngValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-36：`PolylineJson` 原先**在这个模块结构下无法编写测试** —— 它用 `org.json`，
 * 那是 Android 自带类，JVM 单测里调用会抛 "Method ... not mocked"。
 * 改用 kotlinx.serialization（纯 Kotlin）后终于能测，这里补齐：
 * 正常往返、历史数据兼容（整数形式）、以及各类损坏输入必须**不抛异常**。
 */
class PolylineJsonTest {

    private val pts = listOf(
        LatLngValue(30.0, 120.0),
        LatLngValue(30.1, 120.1),
        LatLngValue(30.2, 120.2),
    )

    @Test
    fun `encode then decode round trips`() {
        val json = PolylineJson.encode(pts)
        assertEquals(pts, PolylineJson.decode(json))
    }

    @Test
    fun `encode produces the documented array-of-pairs shape`() {
        assertEquals("[[30.0,120.0],[30.1,120.1]]", PolylineJson.encode(pts.take(2)))
    }

    @Test
    fun `empty list round trips`() {
        assertEquals(emptyList<LatLngValue>(), PolylineJson.decode(PolylineJson.encode(emptyList())))
    }

    /** org.json 时代写进库的数据可能是整数形式（30 而非 30.0），必须仍能被读出 */
    @Test
    fun `legacy integer-formatted json is still decoded`() {
        val got = PolylineJson.decode("[[30,120],[30.5,120.5]]")
        assertEquals(listOf(LatLngValue(30.0, 120.0), LatLngValue(30.5, 120.5)), got)
    }

    // ---- 损坏输入：一律不抛异常（调用点 RecordingScreen / TripJsonExporter 都没有保护）----

    @Test
    fun `malformed input never throws`() {
        val bad = listOf(
            "", "   ", "not json at all", "{", "]", "null", "42", "\"str\"",
            "[[30.0]]",                 // 元素不足 2 个
            "[[30.0,120.0],[]]",        // 中间夹一个空数组
            "[[\"a\",120.0]]",          // 非数字
            "[[30.0,120.0],\"x\"]",     // 元素不是数组
            "[[NaN,120.0]]",            // JSON 非法字面量
            "[[30.0,120.0]",            // 截断
        )
        for (s in bad) {
            PolylineJson.decode(s) // 只要求不抛
        }
    }

    /** 逐元素容错：一个坏点不应连累其余好点 */
    @Test
    fun `bad element does not discard the good ones`() {
        val got = PolylineJson.decode("[[30.0,120.0],\"oops\",[31.0,121.0]]")
        assertEquals(listOf(LatLngValue(30.0, 120.0), LatLngValue(31.0, 121.0)), got)
    }

    @Test
    fun `null and blank yield empty list`() {
        assertTrue(PolylineJson.decode(null).isEmpty())
        assertTrue(PolylineJson.decode("").isEmpty())
    }
}
