package com.gohiking.core.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒触发引擎单测（DEV §4.9 / PRD F-ALERT-05/06/14~17/20~24/28）。
 */
class AlertEngineTest {

    private fun sample(
        distanceM: Double,
        altitudeM: Double? = null,
        lat: Double = 30.0,
        lng: Double = 120.0,
    ) = AlertSample(distanceM = distanceM, altitudeM = altitudeM, latitude = lat, longitude = lng, timestampMs = 0L)

    // ---- F-ALERT-05：总开关关闭 → 不产生任何标记 ----
    @Test
    fun masterDisabledProducesNothing() {
        val engine = AlertEngine(AlertSettings(masterEnabled = false))
        assertTrue(engine.onSample(sample(1000.0, 900.0)).isEmpty())
    }

    // ---- F-ALERT-14/17：距离达到阈值整数倍触发、同一阈值不重复 ----
    @Test
    fun distanceTriggersOncePerThreshold() {
        val engine = AlertEngine(AlertSettings(distanceIntervalM = 100))
        assertTrue(engine.onSample(sample(99.9)).isEmpty())
        val first = engine.onSample(sample(100.0))
        assertEquals(1, first.size)
        assertEquals("ALERT_DISTANCE", first[0].marker.type)
        assertEquals(1, first[0].marker.sequence)
        assertEquals("{\"distanceM\":100}", first[0].marker.extraJson)
        // 100.0 ~ 199.9 不再触发（同一阈值不重复）
        assertTrue(engine.onSample(sample(150.0)).isEmpty())
        assertTrue(engine.onSample(sample(199.9)).isEmpty())
        // 200.0 触发第二次（n 单调递增）
        val second = engine.onSample(sample(250.0))
        assertEquals(1, second.size)
        assertEquals(2, second[0].marker.sequence)
    }

    // ---- F-ALERT-16：暂停期间距离不增长 → 恢复后从暂停前继续，不重复触发 ----
    @Test
    fun pauseDoesNotRetriggerDistance() {
        val engine = AlertEngine(AlertSettings(distanceIntervalM = 100))
        engine.onSample(sample(100.0)) // 触发一次
        // 暂停期间重复喂相同距离（暂停不累计）→ 不触发
        repeat(5) { assertTrue(engine.onSample(sample(100.0)).isEmpty()) }
        // 恢复后继续累计到 200 触发
        assertEquals(1, engine.onSample(sample(200.0)).size)
    }

    // ---- F-ALERT-24：爬升/下降独立累计，任一方向达阈值触发 ----
    @Test
    fun elevationAscentAndDescentIndependent() {
        val engine = AlertEngine(AlertSettings(elevationIntervalM = 100))
        // 阈值法语义：相对锚点变化 ≥ 100 才计入，计入值 = 锚点差值
        engine.onSample(sample(0.0, 10.0)) // 锚点 = 10
        assertTrue(engine.onSample(sample(50.0, 109.0)).isEmpty()) // +99 未达阈值，不计入
        // 单跳 +199 ≥ 100 → 计入 199，累计 199 ≥ 首阈值 100 → 触发
        val up = engine.onSample(sample(60.0, 209.0))
        assertEquals(1, up.size)
        assertEquals("ALERT_ASCENT", up[0].marker.type)
        assertEquals(199, up[0].voice.let { (it as AlertVoice.Ascent).meters })
        assertEquals("{\"accumM\":199}", up[0].marker.extraJson)
        // 下降：从 209 降到 100（-109 ≥ 100）→ 触发 DESCENT，与 ASCENT 序号独立
        val down = engine.onSample(sample(70.0, 100.0))
        assertEquals(1, down.size)
        assertEquals("ALERT_DESCENT", down[0].marker.type)
        assertEquals(1, down[0].marker.sequence) // DESCENT 自己的序号
        assertEquals(109, down[0].voice.let { (it as AlertVoice.Descent).meters })
        assertEquals(100, down[0].voice.let { (it as AlertVoice.Descent).altitudeM })
    }

    // ---- F-ALERT-06：打点与播报事件独立产生（voiceEnabled 裁剪在调用方） ----
    @Test
    fun eventsCarryBothMarkerAndVoice() {
        val engine = AlertEngine(AlertSettings(voiceEnabled = false, distanceIntervalM = 100))
        val events = engine.onSample(sample(100.0, 500.0))
        // 距离触发；海拔 500 属首个锚点不触发
        assertEquals(1, events.size)
        assertEquals(AlertVoice.Distance(100), events[0].voice)
    }

    // ---- F-ALERT-03：设置即时生效（间隔变更保留累加状态） ----
    @Test
    fun settingsChangeKeepsAccumulation() {
        val engine = AlertEngine(AlertSettings(elevationIntervalM = 100))
        engine.onSample(sample(0.0, 0.0))
        engine.onSample(sample(0.0, 100.0)) // 累计爬升 100 → lastAscentMark = 100
        engine.onSettingsChanged(AlertSettings(elevationIntervalM = 50))
        // 累计保留（100），新阈值 50 → next = 150，+50 触发
        val next = engine.onSample(sample(0.0, 150.0))
        assertEquals(1, next.size)
        assertEquals(150, next[0].voice.let { (it as AlertVoice.Ascent).meters })
    }

    // ---- DEV §4.9：崩溃恢复不重复触发上一阈值 ----
    @Test
    fun restoreDoesNotRetrigger() {
        val engine = AlertEngine(AlertSettings(distanceIntervalM = 100, elevationIntervalM = 100))
        engine.onSample(sample(100.0, 0.0))
        engine.onSample(sample(0.0, 100.0)) // asc 触发（100）
        val (lastDist, lastAsc, lastDesc) = engine.markBaselines()
        assertEquals(100.0, lastDist, 0.0)
        assertEquals(100.0, lastAsc, 0.0)
        assertEquals(0.0, lastDesc, 0.0)
        val anchor = engine.currentElevationAnchor()

        val restored = AlertEngine(AlertSettings(distanceIntervalM = 100, elevationIntervalM = 100))
        restored.restore(lastDist, lastAsc, lastDesc, anchor)
        // 恢复后同距离/同海拔不触发
        assertTrue(restored.onSample(sample(100.0, 100.0)).isEmpty())
        // 继续累计：距离 200 触发 DISTANCE；海拔锚点 100→200 计入 100，累计 200 触发 ASCENT
        val next = restored.onSample(sample(200.0, 200.0))
        assertEquals(2, next.size)
        assertEquals("ALERT_DISTANCE", next[0].marker.type)
        assertEquals("ALERT_ASCENT", next[1].marker.type)
        // 海拔恢复后累计 200，锚点 200：+50 不计入（< 阈值 100）→ 不触发
        assertTrue(restored.onSample(sample(200.0, 250.0)).isEmpty())
        // 锚点 250 → 350：计入 100，累计 300 ≥ 300（nextMark）触发
        assertEquals(1, restored.onSample(sample(200.0, 350.0)).size)
    }

    // ---- 距离开关独立（F-ALERT-07） ----
    @Test
    fun distanceToggleRespected() {
        val engine = AlertEngine(AlertSettings(distanceEnabled = false))
        assertTrue(engine.onSample(sample(5000.0, 0.0)).isEmpty())
    }
}
