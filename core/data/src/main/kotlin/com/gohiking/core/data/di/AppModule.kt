package com.gohiking.core.data.di

import com.gohiking.core.common.coroutine.ApplicationScope
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.location.source.AmapLocationSource
import com.gohiking.core.location.source.FusedLocationSource
import com.gohiking.core.location.source.SwitchingLocationProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.Context
import dagger.Binds
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 应用级协程作用域（会话/服务内长生命周期任务用） */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 主备切换定位（DEV D-08）：高德主 + Fused 备（GMS 不可用时备源为 null，运行时降级） */
    @Provides
    @Singleton
    fun provideSwitchingLocationProvider(
        @ApplicationContext context: Context,
        primary: AmapLocationSource,
        @ApplicationScope scope: CoroutineScope,
    ): SwitchingLocationProvider =
        SwitchingLocationProvider(
            primary = primary,
            backup = if (FusedLocationSource.isAvailable(context)) FusedLocationSource(context) else null,
            scope = scope,
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindModule {

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: SwitchingLocationProvider): LocationProvider
}
