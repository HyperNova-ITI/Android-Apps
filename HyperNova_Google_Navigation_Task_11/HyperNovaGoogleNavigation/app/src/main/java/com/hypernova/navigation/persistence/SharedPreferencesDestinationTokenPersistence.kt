package com.hypernova.navigation.persistence

import android.content.Context
import androidx.core.content.edit
import com.hypernova.navigation.model.GoogleDestinationRecord
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesDestinationTokenPersistence(context: Context) : DestinationTokenPersistence {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): List<DestinationTokenEntry> =
        runCatching {
            val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(
                        DestinationTokenEntry(
                            token = item.getString("token"),
                            source = item.getInt("source"),
                            record =
                                GoogleDestinationRecord(
                                    placeId = item.getString("placeId"),
                                    title = item.getString("title"),
                                    subtitle = item.optString("subtitle"),
                                    category = item.optString("category"),
                                    latitude = item.optNullableDouble("latitude"),
                                    longitude = item.optNullableDouble("longitude"),
                                ),
                            createdAtMillis = item.getLong("createdAt"),
                            expiresAtMillis =
                                if (item.isNull("expiresAt")) null else item.getLong("expiresAt"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }

    override fun save(entries: List<DestinationTokenEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("token", entry.token)
                    put("source", entry.source)
                    put("placeId", entry.record.placeId)
                    put("title", entry.record.title)
                    put("subtitle", entry.record.subtitle)
                    put("category", entry.record.category)
                    put("latitude", entry.record.latitude ?: JSONObject.NULL)
                    put("longitude", entry.record.longitude ?: JSONObject.NULL)
                    put("createdAt", entry.createdAtMillis)
                    put("expiresAt", entry.expiresAtMillis ?: JSONObject.NULL)
                },
            )
        }
        preferences.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf(Double::isFinite)

    private companion object {
        const val PREFERENCES_NAME = "hypernova_google_destinations"
        const val KEY_ENTRIES = "destination_tokens_v1"
    }
}
