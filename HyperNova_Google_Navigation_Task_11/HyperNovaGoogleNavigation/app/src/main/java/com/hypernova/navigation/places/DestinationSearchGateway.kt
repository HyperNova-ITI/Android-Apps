package com.hypernova.navigation.places

import com.hypernova.navigation.model.GoogleDestinationRecord

interface DestinationSearchGateway {
    suspend fun search(query: String): List<GoogleDestinationRecord>
}

class ConfigurationRequiredSearchGateway : DestinationSearchGateway {
    override suspend fun search(query: String): List<GoogleDestinationRecord> {
        throw GooglePlacesException.ConfigurationRequired
    }
}

sealed class GooglePlacesException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object ConfigurationRequired : GooglePlacesException("Google Maps configuration is required.")
    class RequestFailed(cause: Throwable) : GooglePlacesException("Google Places search failed.", cause)
}
