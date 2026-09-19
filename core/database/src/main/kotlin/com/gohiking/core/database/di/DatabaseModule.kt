package com.gohiking.core.database.di

import android.content.Context
import androidx.room.Room
import com.gohiking.core.database.GhDatabase
import com.gohiking.core.database.MIGRATIONS
import com.gohiking.core.database.dao.ElevationCacheDao
import com.gohiking.core.database.dao.MarkerDao
import com.gohiking.core.database.dao.MediaDao
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.database.dao.RecordingStateDao
import com.gohiking.core.database.dao.TrackPointDao
import com.gohiking.core.database.dao.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 数据库 DI（DEV §2.2）。注意：禁止 fallbackToDestructiveMigration。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GhDatabase =
        Room.databaseBuilder(context, GhDatabase::class.java, GhDatabase.NAME)
            .addMigrations(*MIGRATIONS)
            .build()

    @Provides fun tripDao(db: GhDatabase): TripDao = db.tripDao()

    @Provides fun trackPointDao(db: GhDatabase): TrackPointDao = db.trackPointDao()

    @Provides fun markerDao(db: GhDatabase): MarkerDao = db.markerDao()

    @Provides fun plannedRouteDao(db: GhDatabase): PlannedRouteDao = db.plannedRouteDao()

    @Provides fun mediaDao(db: GhDatabase): MediaDao = db.mediaDao()

    @Provides fun recordingStateDao(db: GhDatabase): RecordingStateDao = db.recordingStateDao()

    @Provides fun elevationCacheDao(db: GhDatabase): ElevationCacheDao = db.elevationCacheDao()
}
