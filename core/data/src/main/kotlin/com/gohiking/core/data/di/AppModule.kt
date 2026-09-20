package com.gohiking.core.data.di

import com.gohiking.core.common.coroutine.ApplicationScope
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.location.source.AmapLocationSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 应用级协程作用域（会话/服务内长生命周期任务用） */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindModule {

    /** M1 当前：高德单源；Fused 备源与 30s watchdog 切换（DEV D-08）下一批接入 */
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: AmapLocationSource): LocationProvider
}
