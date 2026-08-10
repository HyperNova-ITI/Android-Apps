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

class NavigationCommandService : Service() {
    private lateinit var controller: NavigationCommandController
    private lateinit var statusPublisher: NavigationStatusPublisher

    private val binder =
        object : INavigationCommandService.Stub() {
            override fun getApiVersion(): Int =
                HyperNovaContract.API_VERSION

            override fun searchDestinations(
                requestId: String?,
                query: String?,
                callback: INavigationCommandCallback?
            ) {
                controller.searchDestinations(
                    requestId,
                    query,
                    callback
                )
            }

            override fun getSavedDestinations(
                requestId: String?,
                callback: INavigationCommandCallback?
            ) {
                controller.getSavedDestinations(
                    requestId,
                    callback
                )
            }

            override fun setDestination(
                requestId: String?,
                destinationId: String?,
                callback: INavigationCommandCallback?
            ) {
                controller.setDestination(
                    requestId,
                    destinationId,
                    callback
                )
            }

            override fun cancelNavigation(
                requestId: String?,
                callback: INavigationCommandCallback?
            ) {
                controller.cancelNavigation(
                    requestId,
                    callback
                )
            }

            override fun getCurrentNavigationState(
                requestId: String?,
                callback: INavigationCommandCallback?
            ) {
                controller.getCurrentNavigationState(
                    requestId,
                    callback
                )
            }

            override fun getCurrentNavigationRoutePreview(
                requestId: String?,
                callback: INavigationRoutePreviewCallback?
            ) {
                controller.getCurrentNavigationRoutePreview(
                    requestId,
                    callback
                )
            }

            override fun registerNavigationStatusCallback(
                callback: INavigationStatusCallback?
            ) {
                statusPublisher.register(callback)
            }

            override fun unregisterNavigationStatusCallback(
                callback: INavigationStatusCallback?
            ) {
                statusPublisher.unregister(callback)
            }
        }

    override fun onCreate() {
        super.onCreate()
        val navigationApplication =
            application as HyperNovaNavigationApplication
        controller =
            NavigationCommandController(
                navigationApplication.navigationRepository
            )
        statusPublisher =
            NavigationStatusPublisher(
                navigationApplication.navigationRepository
            )
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (
            intent?.action ==
            NavigationContract.BIND_COMMAND_ACTION
        ) {
            binder
        } else {
            null
        }

    override fun onDestroy() {
        statusPublisher.shutdown()
        controller.shutdown()
        super.onDestroy()
    }
}
