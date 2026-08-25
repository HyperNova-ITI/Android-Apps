package com.hypernova.navigation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationPlaceContactCallback
import com.hypernova.contracts.navigation.INavigationPlaceContactService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.contracts.navigation.NavigationPlaceContactResult
import com.hypernova.navigation.HyperNovaNavigationApplication
import com.hypernova.navigation.persistence.DestinationResolution
import com.hypernova.navigation.places.GooglePlacesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Optional read-only place-detail boundary; the frozen command service remains unchanged. */
class NavigationPlaceContactService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    private val binder = object : INavigationPlaceContactService.Stub() {
        override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

        override fun getDestinationContact(
            requestId: String?,
            destinationId: String?,
            callback: INavigationPlaceContactCallback?,
        ) {
            val receiver = callback ?: return
            val id = requestId?.trim().orEmpty()
            val token = destinationId?.trim().orEmpty()
            if (id.isBlank() || token.isBlank()) {
                deliver(receiver, result(
                    id,
                    HyperNovaContract.STATUS_REJECTED,
                    "requestId and destinationId must not be blank.",
                    HyperNovaContract.ERROR_INVALID_ARGUMENT,
                    token,
                ))
                return
            }
            scope.launch {
                val runtime = (application as HyperNovaNavigationApplication).navigationRuntime
                val response = try {
                    when (val resolution = runtime.resolveDestination(token)) {
                        DestinationResolution.Expired -> result(
                            id,
                            HyperNovaContract.STATUS_REJECTED,
                            "The destination ID has expired.",
                            NavigationContract.ERROR_DESTINATION_EXPIRED,
                            token,
                        )
                        DestinationResolution.Unknown -> result(
                            id,
                            HyperNovaContract.STATUS_REJECTED,
                            "The destination ID is unknown.",
                            NavigationContract.ERROR_DESTINATION_NOT_FOUND,
                            token,
                        )
                        is DestinationResolution.Found -> {
                            val contact = withTimeout(NavigationContract.PLACE_CONTACT_TIMEOUT_MILLIS) {
                                runtime.destinationContact(resolution.entry)
                            }
                            if (contact == null) {
                                result(
                                    id,
                                    HyperNovaContract.STATUS_REJECTED,
                                    "No phone number is listed for this place.",
                                    NavigationContract.ERROR_NO_PHONE_NUMBER,
                                    token,
                                    resolution.entry.record.title,
                                )
                            } else {
                                result(
                                    id,
                                    HyperNovaContract.STATUS_CONFIRMED,
                                    "Place phone number found.",
                                    HyperNovaContract.ERROR_NONE,
                                    token,
                                    contact.displayName,
                                    contact.phoneNumber,
                                )
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    result(
                        id,
                        HyperNovaContract.STATUS_TIMEOUT,
                        "Place phone lookup timed out.",
                        HyperNovaContract.ERROR_TIMEOUT,
                        token,
                    )
                } catch (_: GooglePlacesException.ConfigurationRequired) {
                    result(
                        id,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "Google Maps configuration is required.",
                        HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                        token,
                    )
                } catch (_: GooglePlacesException.RequestFailed) {
                    result(
                        id,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "Google Place contact lookup is unavailable.",
                        NavigationContract.ERROR_OFFLINE_DATA_UNAVAILABLE,
                        token,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.e(TAG, "Place contact lookup failed", failure)
                    result(
                        id,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "The navigation contact service is unavailable.",
                        HyperNovaContract.ERROR_INTERNAL,
                        token,
                    )
                }
                safeDeliver(receiver, response)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == NavigationContract.BIND_PLACE_CONTACT_ACTION }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun deliver(
        callback: INavigationPlaceContactCallback,
        value: NavigationPlaceContactResult,
    ) = scope.launch { safeDeliver(callback, value) }

    private fun safeDeliver(
        callback: INavigationPlaceContactCallback,
        value: NavigationPlaceContactResult,
    ) {
        try {
            callback.onResult(value)
        } catch (_: RemoteException) {
            Log.i(TAG, "Place contact client disconnected")
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Place contact callback failed", failure)
        }
    }

    private fun result(
        requestId: String,
        status: Int,
        message: String,
        errorCode: String,
        destinationId: String,
        displayName: String = "",
        phoneNumber: String = "",
    ) = NavigationPlaceContactResult(
        requestId,
        status,
        message,
        errorCode,
        destinationId,
        displayName,
        phoneNumber,
    )

    private companion object {
        const val TAG = "HN-PlaceContact"
    }
}
