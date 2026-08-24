package com.hypernova.navigation.model

data class GoogleDestinationRecord(
    val placeId: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val latitude: Double?,
    val longitude: Double?,
)
