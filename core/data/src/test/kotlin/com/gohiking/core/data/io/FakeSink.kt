package com.gohiking.core.data.io

import com.gohiking.core.database.entity.TripEntity

/** 单测用内存 ImportSink */
class FakeSink : ImportSink {
    val trips = mutableMapOf<String, TripBundle>()
    val overwrites = mutableListOf<String>()
    val routes = mutableMapOf<String, PlannedRouteBundle>()

    fun seedTrip(bundle: TripBundle) {
        trips[bundle.trip.id] = bundle
    }

    fun seedRouteId(id: String) {
        // 仅登记 id（构造最小 bundle）
        routes[id] = PlannedRouteBundle(
            route = com.gohiking.core.database.entity.PlannedRouteEntity(
                id = id, name = "seed", note = null, source = "MANUAL",
                createdAt = 0, totalDistanceM = 0.0, totalAscentM = 0.0, totalDescentM = 0.0,
            ),
            legs = emptyList(),
            waypoints = emptyList(),
        )
    }

    override suspend fun existingTripIds(ids: Collection<String>): Set<String> = ids.toSet().intersect(trips.keys)

    override suspend fun suspectedDuplicateTripIds(name: String, startTime: Long): Set<String> =
        trips.values
            .filter { it.trip.name == name && it.trip.startTime == startTime }
            .map { it.trip.id }
            .toSet()

    override suspend fun existingRouteIds(ids: Collection<String>): Set<String> = ids.toSet().intersect(routes.keys)

    override suspend fun putTrip(bundle: TripBundle, overwrite: Boolean) {
        if (trips.containsKey(bundle.trip.id) && !overwrite) {
            throw IllegalStateException("trip exists without overwrite")
        }
        if (overwrite) overwrites.add(bundle.trip.id)
        trips[bundle.trip.id] = bundle
    }

    override suspend fun putPlannedRoute(bundle: PlannedRouteBundle, overwrite: Boolean) {
        if (routes.containsKey(bundle.route.id) && !overwrite) {
            throw IllegalStateException("route exists without overwrite")
        }
        routes[bundle.route.id] = bundle
    }

    companion object {
        fun makeTrip(id: String = "t1", name: String = "测试山", startMs: Long = 1_758_240_723_000L): TripBundle =
            TripBundle(
                trip = TripEntity(
                    id = id, name = name, note = null, plannedRouteId = null,
                    startTime = startMs, endTime = startMs + 3_600_000,
                    durationSec = 3_600, movingDurationSec = 3_400, pausedDurationSec = 200,
                    distanceM = 4_000.0, totalAscentM = 300.0, totalDescentM = 280.0,
                    maxAltitudeM = 500.0, minAltitudeM = 100.0, avgSpeedMps = 1.1,
                    avgPaceSecPerKm = 850, maxSpeedMps = 2.0, steps = 5_000,
                    stepSource = "SENSOR_COUNTER", caloriesKcal = 300.0,
                    altitudeSource = "BAROMETER_FUSED", hasBarometer = true,
                    status = "FINISHED", createdAt = startMs,
                ),
                points = emptyList(),
                markers = emptyList(),
                media = emptyList(),
            )
    }
}
