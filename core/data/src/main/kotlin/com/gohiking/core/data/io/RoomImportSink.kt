package com.gohiking.core.data.io

import androidx.room.withTransaction
import com.gohiking.core.database.GhDatabase
import javax.inject.Inject

/**
 * Room 落库实现（F-IO-65）：每条记录一个事务——行程 + 点 + 标记 + 媒体引用同事务，
 * 中途异常回滚当前条目不留半条；OVERWRITE = 同事务先删后插。
 */
class RoomImportSink @Inject constructor(private val db: GhDatabase) : ImportSink {

    override suspend fun existingTripIds(ids: Collection<String>): Set<String> =
        if (ids.isEmpty()) emptySet() else db.tripDao().existingIds(ids.toList()).toSet()

    override suspend fun suspectedDuplicateTripIds(name: String, startTime: Long): Set<String> =
        if (startTime <= 0) {
            emptySet()
        } else {
            db.tripDao().findSuspectedDuplicate(name, startTime).toSet()
        }

    override suspend fun existingRouteIds(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return db.plannedRouteDao().allIds().intersect(ids.toSet())
    }

    override suspend fun putTrip(bundle: TripBundle, overwrite: Boolean) {
        db.withTransaction {
            if (overwrite) {
                db.trackPointDao().deleteOf(bundle.trip.id)
                db.markerDao().deleteOf(bundle.trip.id)
                db.mediaDao().deleteRefsOf(bundle.trip.id)
                db.tripDao().deleteById(bundle.trip.id)
            }
            db.tripDao().insert(bundle.trip)
            if (bundle.points.isNotEmpty()) db.trackPointDao().insertAll(bundle.points)
            if (bundle.markers.isNotEmpty()) db.markerDao().insertAll(bundle.markers)
            bundle.media.forEach { db.mediaDao().insertRef(it) }
        }
    }

    override suspend fun putPlannedRoute(bundle: PlannedRouteBundle, overwrite: Boolean) {
        db.withTransaction {
            if (overwrite) db.plannedRouteDao().deleteById(bundle.route.id)
            db.plannedRouteDao().saveWithLegs(bundle.route, bundle.legs)
            if (bundle.waypoints.isNotEmpty()) db.plannedRouteDao().insertWaypoints(bundle.waypoints)
        }
    }
}
