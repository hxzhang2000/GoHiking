package com.gohiking.core.location

/**
 * 隐私合规闸门（DEV §1.5 红线）。
 *
 * 高德 **地图** SDK 要求 `MapsInitializer.updatePrivacyShow/Agree` 先于首个 MapView 创建；
 * **定位** SDK 同样要求 `AMapLocationClient.updatePrivacyShow/Agree` 先于首个
 * `AMapLocationClient` 实例化（loc 6.4.5 起强制）。C-03：此前全仓库只调了地图那两个，
 * 定位侧的从未被调用——既违反「用户同意前不初始化 SDK」，合规未通过时也会直接拒绝定位。
 *
 * 用法：App 在用户点击「同意」后调用 [markAgreed]；core 层各 SDK 包装类在初始化前 [assertAgreed]。
 */
object PrivacyConsent {

    @Volatile
    var agreed: Boolean = false
        private set

    fun markAgreed() {
        agreed = true
    }

    /** 仅供测试/重启流程复位；正常只在同意时置 true */
    fun resetForTest() {
        agreed = false
    }

    fun assertAgreed(component: String) {
        check(agreed) {
            "AMap $component 在用户同意隐私政策前被初始化（DEV §1.5 红线）"
        }
    }
}
