package com.hypernova.navigation.domain.model

data class VehiclePosition(
    val point: GeoPoint,
    val bearingDegrees: Double,
    val speedKph: Double,
    val traveledMeters: Double,
    val remainingDistanceMeters: Double,
    val progressFraction: Double,
    val routeSegmentIndex: Int,
    val arrived: Boolean
)
