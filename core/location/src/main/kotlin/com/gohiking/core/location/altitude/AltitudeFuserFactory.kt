package com.gohiking.core.location.altitude

/**
 * 海拔融合器工厂（文档审阅 H-3 / D10）。
 *
 * H-3：`AltitudeFuser` 有**逐场可变状态**（`altRef`、气压基准、中值窗口），
 * 不能被 `@Singleton` 持有、更不能跨场继承，所以消费方拿的是**工厂**而不是实例——
 * 每场记录（含崩溃恢复）都 `create()` 一个全新实例。
 *
 * D10：接口化的另一个目的是可替身，单测注入 [FakeAltitudeFuserFactory] 即可。
 */
interface AltitudeFuserFactory {
    fun create(hasBarometer: Boolean): AltitudeFusion
}

/** 生产实现：真气压融合器（气压→海拔用国际标准大气公式） */
object RealAltitudeFuserFactory : AltitudeFuserFactory {
    override fun create(hasBarometer: Boolean): AltitudeFusion =
        AltitudeFuser(hasBarometer, nowMs = System::currentTimeMillis)
}
