package com.hypernova.navigation.data.persistence

import android.content.Context
import com.hypernova.navigation.domain.model.DemoDestinations
import com.hypernova.navigation.domain.model.NavigationJson
import com.hypernova.navigation.domain.model.NavigationScreen
import com.hypernova.navigation.domain.model.Place
import org.json.JSONArray
import org.json.JSONObject

class NavigationPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    var guidanceMuted: Boolean
        get() = preferences.getBoolean(KEY_GUIDANCE_MUTED, false)
        set(value) {
            preferences.edit()
                .putBoolean(KEY_GUIDANCE_MUTED, value)
                .apply()
        }

    var home: Place?
        get() =
            readPlace(KEY_HOME)
                ?: DemoDestinations.HOME
        set(value) = writePlace(KEY_HOME, value)

    var work: Place?
        get() =
            readPlace(KEY_WORK)
                ?: DemoDestinations.WORK
        set(value) = writePlace(KEY_WORK, value)

    val recents: List<Place>
        get() {
            val stored =
                preferences.getString(KEY_RECENTS, null)
                    ?: return emptyList()

            return runCatching {
                val json = JSONArray(stored)

                buildList {
                    for (index in 0 until json.length()) {
                        json.optJSONObject(index)
                            ?.let(NavigationJson::placeFromJson)
                            ?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        }

    fun retireLocalTheme() {
        preferences.edit()
            .remove(
                NavigationPreferenceContract
                    .LEGACY_LOCAL_THEME
            )
            .apply()
    }

    fun addRecent(place: Place) {
        val updated =
            buildList {
                add(place)

                recents
                    .filterNot { it.id == place.id }
                    .take(MAX_RECENTS - 1)
                    .forEach(::add)
            }

        val json = JSONArray()

        updated.forEach {
            json.put(
                NavigationJson.placeToJson(it)
            )
        }

        preferences.edit()
            .putString(
                KEY_RECENTS,
                json.toString()
            )
            .apply()
    }

    fun saveSafeScreen(screen: NavigationScreen) {
        if (screen in SAFE_STARTUP_SCREENS) {
            preferences.edit()
                .putString(
                    KEY_SAFE_SCREEN,
                    screen.name
                )
                .apply()
        }
    }

    fun lastSafeScreen(): NavigationScreen =
        runCatching {
            NavigationScreen.valueOf(
                preferences.getString(
                    KEY_SAFE_SCREEN,
                    NavigationScreen.HOME.name
                ) ?: NavigationScreen.HOME.name
            )
        }.getOrDefault(
            NavigationScreen.HOME
        )
            .takeIf {
                it in SAFE_STARTUP_SCREENS
            }
            ?: NavigationScreen.HOME

    private fun readPlace(key: String): Place? {
        val stored =
            preferences.getString(
                key,
                null
            ) ?: return null

        return runCatching {
            NavigationJson.placeFromJson(
                JSONObject(stored)
            )
        }.getOrNull()
    }

    private fun writePlace(
        key: String,
        place: Place?
    ) {
        preferences.edit().apply {
            if (place == null) {
                remove(key)
            } else {
                putString(
                    key,
                    NavigationJson
                        .placeToJson(place)
                        .toString()
                )
            }
        }.apply()
    }

    companion object {
        private const val PREFERENCES_NAME =
            "hypernova_navigation_preferences"

        private const val KEY_GUIDANCE_MUTED =
            NavigationPreferenceContract.GUIDANCE_MUTED

        private const val KEY_HOME =
            NavigationPreferenceContract.HOME_DESTINATION

        private const val KEY_WORK =
            NavigationPreferenceContract.WORK_DESTINATION

        private const val KEY_RECENTS =
            NavigationPreferenceContract.RECENT_DESTINATIONS

        private const val KEY_SAFE_SCREEN =
            NavigationPreferenceContract.LAST_SAFE_SCREEN

        private const val MAX_RECENTS = 6

        private val SAFE_STARTUP_SCREENS =
            setOf(
                NavigationScreen.HOME,
                NavigationScreen.SEARCH
            )
    }
}
