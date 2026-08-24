package com.hypernova.navigation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.INavigationRoutePreviewCallback
import com.hypernova.contracts.navigation.INavigationStatusCallback
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.HyperNovaNavigationApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NavigationCommandService : Service() {
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    private lateinit var controller: NavigationCommandController
    private lateinit var publisher: NavigationStatusPublisher

    private val binder =
        object : INavigationCommandService.Stub() {
            override fun getApiVersion(): Int = HyperNovaContract.API_VERSION

            override fun searchDestinations(
                requestId: String?,
                query: String?,
                callback: INavigationCommandCallback?,
            ) = controller.searchDestinations(requestId, query, callback)

            override fun getSavedDestinations(
                requestId: String?,
                callback: INavigationCommandCallback?,
            ) = controller.getSavedDestinations(requestId, callback)

            override fun setDestination(
                requestId: String?,
                destinationId: String?,
                callback: INavigationCommandCallback?,
            ) = controller.setDestination(requestId, destinationId, callback)

            override fun startNavigation(
                requestId: String?,
                callback: INavigationCommandCallback?,
            ) = controller.startNavigation(requestId, callback)

            override fun cancelNavigation(
                requestId: String?,
                callback: INavigationCommandCallback?,
            ) = controller.cancelNavigation(requestId, callback)

            override fun getCurrentNavigationState(
                requestId: String?,
                callback: INavigationCommandCallback?,
            ) = controller.getCurrentNavigationState(requestId, callback)

            override fun getCurrentNavigationRoutePreview(
                requestId: String?,
                callback: INavigationRoutePreviewCallback?,
            ) = controller.getCurrentNavigationRoutePreview(requestId, callback)

            override fun registerNavigationStatusCallback(callback: INavigationStatusCallback?) =
                publisher.register(callback)

            override fun unregisterNavigationStatusCallback(callback: INavigationStatusCallback?) =
                publisher.unregister(callback)
        }

    override fun onCreate() {
        super.onCreate()
        val runtime =
            (application as HyperNovaNavigationApplication).navigationRuntime
        controller = NavigationCommandController(runtime, serviceScope)
        publisher = NavigationStatusPublisher(runtime, serviceScope)
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == NavigationContract.BIND_COMMAND_ACTION }

    override fun onDestroy() {
        publisher.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }
}
