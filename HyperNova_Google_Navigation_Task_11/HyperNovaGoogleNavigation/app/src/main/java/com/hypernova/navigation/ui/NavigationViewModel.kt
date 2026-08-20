package com.hypernova.navigation.ui

import android.app.Application
import android.view.ViewGroup
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.HyperNovaNavigationApplication
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.simulation.SimulationControllerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime =
        (application as HyperNovaNavigationApplication).navigationRuntime
    private val simulation = SimulationControllerFactory.create(runtime)
    private val mutableResults = MutableStateFlow<List<DestinationTokenEntry>>(emptyList())
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableBusy = MutableStateFlow(false)

    val session: StateFlow<NavigationSessionState> = runtime.state
    val results: StateFlow<List<DestinationTokenEntry>> = mutableResults.asStateFlow()
    val message: StateFlow<String?> = mutableMessage.asStateFlow()
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    val simulationAvailable: Boolean
        get() = simulation.available
    val guidanceAvailable: Boolean
        get() = runtime.supportsGuidance

    fun attach(activity: android.app.Activity) = runtime.attachActivity(activity)

    fun attachMapSurface(container: ViewGroup) = runtime.attachMapSurface(container)

    fun detachMapSurface(container: ViewGroup) = runtime.detachMapSurface(container)

    fun setMapInsets(topPixels: Int, bottomPixels: Int) =
        runtime.setMapSurfaceInsets(topPixels, bottomPixels)

    fun search(query: String) {
        if (query.isBlank() || mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableMessage.value = null
            try {
                mutableResults.value =
                    withTimeout(NavigationContract.SEARCH_TIMEOUT_MILLIS) {
                        runtime.search(query)
                    }
                if (mutableResults.value.isEmpty()) mutableMessage.value = "No destinations found."
            } catch (_: TimeoutCancellationException) {
                mutableMessage.value = "Google Places search timed out."
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableMessage.value = "Google Places search is unavailable."
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun select(entry: DestinationTokenEntry) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableResults.value = emptyList()
            mutableMessage.value = null
            try {
                withTimeout(NavigationContract.ROUTE_TIMEOUT_MILLIS) {
                    runtime.prepareDestination(entry)
                }
            } catch (_: TimeoutCancellationException) {
                runtime.cancelNavigation()
                mutableMessage.value = "Google route calculation timed out."
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun startGuidance(): Boolean = runtime.startGuidance()

    fun cancelNavigation() {
        runtime.cancelNavigation()
        mutableResults.value = emptyList()
        mutableMessage.value = null
    }

    fun startSimulation(): Boolean = simulation.startDeterministicDemo()

    fun reportMessage(value: String) {
        mutableMessage.value = value
    }

    fun clearResults() {
        mutableResults.value = emptyList()
        mutableMessage.value = null
    }
}
