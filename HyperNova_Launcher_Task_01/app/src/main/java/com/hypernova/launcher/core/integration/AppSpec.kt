package com.hypernova.launcher.core.integration

/**
 * Describes one HyperNova application.
 *
 * packageName:
 * The applicationId used by Android.
 *
 * openAction:
 * A stable Intent action used to open the application.
 *
 * displayNameResourceId:
 * The user-visible application name.
 *
 * serviceClassName:
 * Optional full class name of a service exposed by the application.
 */
data class AppSpec(
    val destination: AppDestination,
    val packageName: String,
    val openAction: String,
    val displayNameResourceId: Int,
    val serviceClassName: String? = null
)