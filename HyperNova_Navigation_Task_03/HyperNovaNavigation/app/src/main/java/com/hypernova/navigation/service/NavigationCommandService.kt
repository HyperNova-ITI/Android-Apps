package com.hypernova.navigation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.navigation.INavigationCommandCallback
import com.hypernova.contracts.navigation.INavigationCommandService
import com.hypernova.contracts.navigation.NavigationContract
import com.hypernova.navigation.HyperNovaNavigationApplication

class NavigationCommandService : Service() {
    private lateinit var controller: NavigationCommandController

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
        }

    override fun onCreate() {
        super.onCreate()
        val navigationApplication =
            application as HyperNovaNavigationApplication
        controller =
            NavigationCommandController(
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
        controller.shutdown()
        super.onDestroy()
    }
}
