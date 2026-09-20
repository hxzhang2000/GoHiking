package com.gohiking.core.elevation.di

import com.gohiking.core.elevation.source.DemTileSource
import com.gohiking.core.elevation.source.ElevationRemoteSource
import com.gohiking.core.elevation.source.EmptyDemTileSource
import com.gohiking.core.elevation.source.OpenMeteoElevationSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ElevationModule {

    @Binds
    @Singleton
    abstract fun bindRemoteSource(impl: OpenMeteoElevationSource): ElevationRemoteSource

    @Binds
    @Singleton
    abstract fun bindDemTileSource(impl: EmptyDemTileSource): DemTileSource
}
