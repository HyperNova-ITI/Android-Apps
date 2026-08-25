package com.hypernova.launcher.core.assistant

import android.net.Uri
import com.hypernova.launcher.core.climate.ClimateAvailability
import com.hypernova.launcher.core.integration.AppAvailability
import com.hypernova.launcher.core.integration.AppDestination
import com.hypernova.launcher.core.media.MediaPlaybackState
import com.hypernova.launcher.core.navigation.NavigationRuntimeState
import com.hypernova.launcher.core.state.*
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaContextCardFactoryTest {
    @Test
    fun `media action produces one real media context card`() {
        val state = fixture().copy(
            assistant = fixture().assistant.copy(actionDomain = "media"),
            media = fixture().media.copy(
                title = "Midnight Drive",
                artist = "HyperNova",
                playbackState = MediaPlaybackState.PLAYING,
            ),
        )

        val card = NovaContextCardFactory.create(state)!!

        assertEquals("NOW PLAYING", card.label)
        assertEquals("Midnight Drive", card.title)
        assertEquals(AppDestination.MEDIA, card.destination)
    }

    @Test
    fun `unknown action domain does not create a fake card`() {
        val state = fixture().copy(
            assistant = fixture().assistant.copy(actionDomain = "unknown"),
        )

        assertEquals(null, NovaContextCardFactory.create(state))
    }

    @Test
    fun `active vehicle fault is presented as an alert rather than a failed action`() {
        val state = fixture().copy(
            assistant = fixture().assistant.copy(
                actionDomain = "vehicle",
                actionName = "P0217",
                primaryMessage = "Engine coolant temperature is too high.",
                blocked = true,
            ),
        )

        val card = NovaContextCardFactory.create(state)!!

        assertEquals("VEHICLE ALERT", card.label)
        assertEquals("Fault P0217 active", card.title)
        assertEquals(
            "Follow the guidance above. This alert stays active until the vehicle reports it cleared.",
            card.detail,
        )
    }

    private fun fixture(): LauncherUiState {
        val integrated = IntegratedAppState(
            availability = AppAvailability.AVAILABLE,
            connectionState = RuntimeConnectionState.CONNECTED,
            active = false,
        )
        return LauncherUiState(
            system = SystemUiState("SYSTEM", "22°C", "LAN"),
            assistant = AssistantUiState(
                connectionState = AppConnectionState.READY,
                runtimeState = AssistantRuntimeState.SUCCESS,
                headline = "COMMAND COMPLETED",
                primaryMessage = "Done",
                artworkVisible = true,
            ),
            navigation = NavigationUiState(
                integrated, NavigationRuntimeState.IDLE, false, "", 0, "No active route",
                "Navigation is ready", "ETA: —", "—", "Arrival —", emptyList(), null,
                null, false, true,
            ),
            media = MediaUiState(
                integrated, MediaPlaybackState.PAUSED, true, true, "No media", "—", null as Uri?,
                "0:00", "0:00", 0f, false, true, true, true, false,
            ),
            phone = PhoneUiState(integrated, "No phone", "Disconnected", true),
            climate = ClimateUiState(
                integrated, ClimateAvailability.AVAILABLE, "22°C", "Fan: 3", "Climate on", false,
            ),
            settings = SettingsUiState(integrated, "Wi-Fi on", "Volume 50%"),
        )
    }
}
