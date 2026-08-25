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
    const val OP_PLAY_RADIO = "play_radio"

    const val ACTION_PLAY_RADIO = "com.hypernova.media.command.PLAY_RADIO"
    const val EXTRA_QUERY = "query"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_STATION_NAME = "station_name"
}
