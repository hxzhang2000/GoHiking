package com.gohiking.core.data

import com.gohiking.core.data.media.MediaClusterer
import com.gohiking.core.database.entity.MediaIndexEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 照片网格聚类（F-MEDIA-10~13，DEV §4.6）单测 */
class MediaClustererTest {

    private fun photo(id: Long, lat: Double, lng: Double) = MediaIndexEntity(
        mediaStoreId = id,
        mediaType = "IMAGE",
        dateModifiedMs = 0L,
        uri = "content://media/images/$id",
        dateTakenMs = 0L,
        latWgs84 = lat,
        lngWgs84 = lng,
        latGcj02 = lat,
        lngGcj02 = lng,
        isApproximate = false,
        durationMs = null,
        sizeBytes = null,
        indexedAt = 0L,
    )

    @Test
    fun `cellSizeDeg halves when zoom increases by 1`() {
        val z10 = MediaClusterer.cellSizeDeg(10f, 300f)
        val z11 = MediaClusterer.cellSizeDeg(11f, 300f)
        assertEquals(z10, z11 * 2, 1e-12)
    }

    @Test
    fun `two nearby photos merge into one cluster with mean position`() {
        val items = listOf(
            photo(1, 30.0001, 120.0001),
            photo(2, 30.0002, 120.0002),
        )
        val clusters = MediaClusterer.cluster(items, zoom = 14f, cellPx = 300f)
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].items.size)
        assertEquals(30.00015, clusters[0].latGcj02, 1e-9)
        assertEquals(120.00015, clusters[0].lngGcj02, 1e-9)
    }

    @Test
    fun `far apart photos stay in separate clusters`() {
        val items = listOf(
            photo(1, 30.0, 120.0),
            photo(2, 31.0, 121.0),
        )
        val clusters = MediaClusterer.cluster(items, zoom = 10f, cellPx = 300f)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `zooming in splits clusters (F-MEDIA-11)`() {
        // zoom 小 → 格子大 → 全并；zoom 大 → 格子小 → 散开
        val items = listOf(
            photo(1, 30.0000, 120.0000),
            photo(2, 30.0100, 120.0100),
        )
        val zoomedOut = MediaClusterer.cluster(items, zoom = 8f, cellPx = 300f)
        val zoomedIn = MediaClusterer.cluster(items, zoom = 15f, cellPx = 300f)
        assertEquals(1, zoomedOut.size)
        assertTrue(zoomedIn.size >= 2)
    }

    @Test
    fun `photos without coordinates are ignored`() {
        val items = listOf(
            photo(1, 30.0, 120.0),
            photo(2, Double.NaN, Double.NaN).copy(latGcj02 = null, lngGcj02 = null),
        )
        val clusters = MediaClusterer.cluster(items, zoom = 14f, cellPx = 300f)
        assertEquals(1, clusters.size)
        assertEquals(1, clusters[0].items.size)
    }

    @Test
    fun `bubble size tiers follow F-MEDIA-13`() {
        assertEquals(32, MediaClusterer.bubbleSizeDp(1))
        assertEquals(32, MediaClusterer.bubbleSizeDp(9))
        assertEquals(40, MediaClusterer.bubbleSizeDp(10))
        assertEquals(40, MediaClusterer.bubbleSizeDp(49))
        assertEquals(48, MediaClusterer.bubbleSizeDp(50))
        assertEquals(48, MediaClusterer.bubbleSizeDp(99))
        assertEquals(56, MediaClusterer.bubbleSizeDp(100))
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(MediaClusterer.cluster(emptyList(), zoom = 14f, cellPx = 300f).isEmpty())
    }
}
