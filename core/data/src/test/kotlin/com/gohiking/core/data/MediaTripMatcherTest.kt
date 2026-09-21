package com.gohiking.core.data

import com.gohiking.core.data.media.MediaTripMatcher
import com.gohiking.core.database.entity.MediaIndexEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-MEDIA-41/43 判定逻辑单测：
 * - 时间窗 ±30 min；
 * - 距离 500 m（GCJ-02 同系）；
 * - 无坐标照片永不命中。
 */
class MediaTripMatcherTest {

    private val t0 = 1_700_000_000_000L // 行程开始
    private val tEnd = t0 + 2L * 60 * 60 * 1000 // 2h 行程

    private fun point(lat: Double, lng: Double) = MediaTripMatcher.TrackPointGcj(lat, lng)

    private fun photo(
        id: Long,
        lat: Double? = 30.0000,
        lng: Double? = 120.0000,
        dateTaken: Long? = t0,
    ) = MediaIndexEntity(
        mediaStoreId = id,
        mediaType = "IMAGE",
        dateModifiedMs = dateTaken ?: 0L,
        uri = "content://media/images/$id",
        dateTakenMs = dateTaken,
        latWgs84 = lat,
        lngWgs84 = lng,
        latGcj02 = lat,
        lngGcj02 = lng, // 测试里 GCJ == WGS（纯判定逻辑，不做转换）
        isApproximate = false,
        durationMs = null,
        sizeBytes = null,
        indexedAt = 0L,
    )

    private val track = listOf(
        point(30.0000, 120.0000),
        point(30.0010, 120.0010), // ≈ 100~140m 外
        point(30.0100, 120.0100), // ≈ 1.4km 外
    )

    @Test
    fun `photo on track within time window hits`() {
        val hits = MediaTripMatcher.match(track, listOf(photo(1)), t0, tEnd)
        assertEquals(listOf(0), hits)
    }

    @Test
    fun `photo outside time window is rejected`() {
        val before = photo(1, dateTaken = t0 - MediaTripMatcher.WINDOW_MS - 1)
        val after = photo(2, dateTaken = tEnd + MediaTripMatcher.WINDOW_MS + 1)
        val hits = MediaTripMatcher.match(track, listOf(before, after), t0, tEnd)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `photo exactly at window boundary hits`() {
        val atStart = photo(1, dateTaken = t0 - MediaTripMatcher.WINDOW_MS)
        val atEnd = photo(2, dateTaken = tEnd + MediaTripMatcher.WINDOW_MS)
        val hits = MediaTripMatcher.match(track, listOf(atStart, atEnd), t0, tEnd)
        assertEquals(listOf(0, 1), hits)
    }

    @Test
    fun `photo 1_4km away from all points is rejected`() {
        // 30.0100,120.0100 附近 500m 内无照片点；把照片放在离全部点 >500m 处
        val far = photo(1, lat = 30.0100, lng = 120.0100) // 这个点在轨迹点 3 上，应命中
        val farther = photo(2, lat = 30.0100 + 0.006, lng = 120.0100) // ≈ 670m 外
        val hits = MediaTripMatcher.match(track, listOf(far, farther), t0, tEnd)
        assertEquals(listOf(0), hits)
    }

    @Test
    fun `photo without coordinates never hits (F-MEDIA-02)`() {
        val noLoc = photo(1, lat = null, lng = null)
        val hits = MediaTripMatcher.match(track, listOf(noLoc), t0, tEnd)
        assertTrue(hits.isEmpty())
    }

    /**
     * N-42：这条测试**一直是绿的**，但它只证明了 [MediaTripMatcher] 的兜底分支正确 ——
     * 真机上这类照片根本到不了这里。真正的坑在 `MediaDao.inTimeRange`：
     * SQL 是 `dateTakenMs BETWEEN ? AND ?`，而 `NULL BETWEEN a AND b` 求值为 NULL，
     * 行直接被过滤，于是本分支成了永远走不到的死代码（截图 / 下载图 / 不写 EXIF 的相机
     * 拍的照片永远关联不上）。DAO 已改为 `COALESCE(dateTakenMs, dateModifiedMs)`。
     *
     * ⚠ 本测试**无法**守住那个回归：它是纯函数测试，碰不到 SQL。若有人把 DAO 改回去，
     * 这里依然全绿 —— 需要在真机/插桩测试里验证「无 DATE_TAKEN 的照片能被关联」。
     */
    @Test
    fun `photo without dateTaken falls back to dateModified`() {
        val fallback = photo(1, dateTaken = null).copy(dateModifiedMs = t0)
        val hits = MediaTripMatcher.match(track, listOf(fallback), t0, tEnd)
        assertEquals(listOf(0), hits)
    }

    /** 与上条配套：兜底时间在窗口外时同样要被拒绝（兜底不是「无条件命中」） */
    @Test
    fun `photo without dateTaken outside window is still rejected`() {
        val tooEarly = photo(1, dateTaken = null).copy(dateModifiedMs = t0 - MediaTripMatcher.WINDOW_MS - 1)
        val tooLate = photo(2, dateTaken = null).copy(dateModifiedMs = tEnd + MediaTripMatcher.WINDOW_MS + 1)
        val hits = MediaTripMatcher.match(track, listOf(tooEarly, tooLate), t0, tEnd)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `empty track or empty photos yields nothing`() {
        assertTrue(MediaTripMatcher.match(emptyList(), listOf(photo(1)), t0, tEnd).isEmpty())
        assertTrue(MediaTripMatcher.match(track, emptyList(), t0, tEnd).isEmpty())
    }

    @Test
    fun `bbox prefilter does not drop hits near track start`() {
        // 照片在轨迹点 2 附近（bbox 内），应命中；构造 bbox 外的远处照片应被淘汰
        val nearP2 = photo(1, lat = 30.0010, lng = 120.0010, dateTaken = t0)
        val farAway = photo(2, lat = 31.5000, lng = 122.0000, dateTaken = t0)
        val hits = MediaTripMatcher.match(track, listOf(nearP2, farAway), t0, tEnd)
        assertEquals(listOf(0), hits)
    }
}
