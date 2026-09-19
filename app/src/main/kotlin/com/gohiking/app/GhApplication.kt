package com.gohiking.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * 应用入口。
 *
 * 隐私合规红线（DEV §1.5）：onCreate 里【只】初始化 Timber 与 Hilt，
 * 严禁在此初始化高德 SDK / AMapLocationClient —— 必须等用户同意隐私政策后，
 * 由 core:map / core:location 的 Gate 类收口调用。
 */
@HiltAndroidApp
class GhApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
