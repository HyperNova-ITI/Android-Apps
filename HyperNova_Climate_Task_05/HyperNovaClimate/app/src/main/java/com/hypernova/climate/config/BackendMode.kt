package com.hypernova.climate.config

import com.hypernova.climate.BuildConfig

/**
 * Which vehicle backend the app links against.
 *
 * The value is compiled in from the Gradle property `climate.backend`
 * (see gradle.properties) and exposed through [BuildConfig.CLIMATE_BACKEND].
 * Change it once, at build time, to switch the whole app between the typed
 * Vehicle Gateway AIDL and the standard AAOS CarProperty/VHAL
 * path — no source edits required.
 */
enum class BackendMode {
    /** Typed Binder link to the headless Vehicle Gateway APK. */
    ETHERNET,

    /** Standard AAOS CarPropertyManager, real values bridged inside a VHAL. */
    VHAL;

    companion object {
        /** The backend selected for this build. Defaults to [ETHERNET]. */
        val current: BackendMode =
            runCatching { valueOf(BuildConfig.CLIMATE_BACKEND.uppercase()) }
                .getOrDefault(ETHERNET)
    }
}
