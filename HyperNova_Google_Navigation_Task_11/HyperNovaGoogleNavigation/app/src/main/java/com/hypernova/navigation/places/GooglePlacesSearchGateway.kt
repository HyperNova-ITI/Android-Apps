package com.hypernova.navigation.places

import android.content.Context
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.gms.tasks.CancellationTokenSource
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.model.GoogleDestinationRecord
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException

class GooglePlacesSearchGateway(
    context: Context,
    apiKey: String,
) : DestinationSearchGateway {
    private val placesClient =
        run {
            if (!Places.isInitialized()) {
                Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
            }
            Places.createClient(context.applicationContext)
        }

    override suspend fun search(query: String): List<GoogleDestinationRecord> {
        val fields =
            listOf(
                Place.Field.ID,
                Place.Field.DISPLAY_NAME,
                Place.Field.FORMATTED_ADDRESS,
                Place.Field.PRIMARY_TYPE_DISPLAY_NAME,
                Place.Field.LOCATION,
            )
        val cancellation = CancellationTokenSource()
        val request =
            SearchByTextRequest.builder(query, fields)
                .setMaxResultCount(NavigationContract.MAX_DESTINATION_RESULTS)
                .setCancellationToken(cancellation.token)
                .build()
        return try {
            placesClient.searchByText(request).await().places.mapNotNull { place ->
                val placeId = place.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val title = place.displayName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                GoogleDestinationRecord(
                    placeId = placeId,
                    title = title,
                    subtitle = place.formattedAddress.orEmpty(),
                    category = place.primaryTypeDisplayName.orEmpty(),
                    latitude = place.location?.latitude,
                    longitude = place.location?.longitude,
                )
            }.take(NavigationContract.MAX_DESTINATION_RESULTS)
        } catch (failure: CancellationException) {
            cancellation.cancel()
            throw failure
        } catch (failure: Exception) {
            throw GooglePlacesException.RequestFailed(failure)
        }
    }
}
