package com.hypernova.ai.command

/**
 * Pi-to-Android operation names for the existing Media3 MediaSession integration.
 *
 * This is intentionally not an AIDL contract: HyperNova Media already exposes the platform
 * MediaSession contract used by Launcher and NOVA.
 */
object MediaWireContract {
    const val PACKAGE_NAME = "com.hypernova.media"
    const val SESSION_SERVICE = "com.hypernova.media.playback.HyperNovaPlaybackService"

    const val OP_GET_CURRENT_STATE = "get_current_state"
    const val OP_PLAY = "play"
    const val OP_PAUSE = "pause"
    const val OP_NEXT = "next"
    const val OP_PREVIOUS = "previous"
    const val OP_SET_VOLUME = "set_volume"
}
