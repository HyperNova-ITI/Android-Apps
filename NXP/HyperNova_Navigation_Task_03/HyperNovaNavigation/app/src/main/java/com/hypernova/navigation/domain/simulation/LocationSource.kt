package com.hypernova.navigation.domain.simulation

import com.hypernova.navigation.domain.model.RouteAlternative
import com.hypernova.navigation.domain.model.VehiclePosition

interface LocationSource : AutoCloseable {
    fun currentPosition(): VehiclePosition?

    fun addListener(listener: (VehiclePosition?) -> Unit)

    fun removeListener(listener: (VehiclePosition?) -> Unit)

    fun setRoute(route: RouteAlternative?)

    override fun close()
}
