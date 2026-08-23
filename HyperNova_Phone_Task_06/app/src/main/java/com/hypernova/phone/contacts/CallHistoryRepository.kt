package com.hypernova.phone.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.phone.domain.CallHistoryType
import com.hypernova.phone.domain.CallNumberPresentation
import com.hypernova.phone.domain.RecentCallEntry
import com.hypernova.phone.domain.RecentFilter
import com.hypernova.phone.domain.RecentsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecentCallsLoadResult(
    val status: RecentsStatus,
    val entries: List<RecentCallEntry>
)

/**
 * Bounded, single-query CallLog reader.
 *
 * The UI grouping and the cross-APK semantic type are intentionally
 * separated:
 *
 * - RecentFilter -> existing Phone UI.
 * - CallHistoryType -> NOVA/Binder contract.
 *
 * No CallLog row is fabricated or dropped because contact identity is
 * missing.
 */
class CallHistoryRepository(
    private val context: Context
) {
    private val identityResolver =
        ContactIdentityResolver(context)

    /**
     * ContactsProvider changes while PBAP downloads batches.
     * Drop old identity decisions so Recents can resolve new names.
     */
    fun invalidateContactIdentityCache() {
        identityResolver.clear()
    }

    suspend fun load():
        RecentCallsLoadResult =
        withContext(Dispatchers.IO) {

            if (!hasCallLogPermission()) {
                return@withContext RecentCallsLoadResult(
                    RecentsStatus.RECENTS_PERMISSION_REQUIRED,
                    emptyList()
                )
            }

            try {
                val raw =
                    mutableListOf<RawRecentCall>()

                val uri =
                    CallLog.Calls.CONTENT_URI
                        .buildUpon()
                        .appendQueryParameter(
                            CallLog.Calls.LIMIT_PARAM_KEY,
                            MAX_RECENT_CALLS.toString()
                        )
                        .build()

                context.contentResolver.query(
                    uri,
                    PROJECTION,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->

                    while (cursor.moveToNext()) {
                        raw +=
                            RawRecentCall(
                                id = cursor.getLong(0),
                                cachedName = cursor.getString(1),
                                number = cursor.getString(2),
                                type = cursor.getInt(3),
                                timestamp = cursor.getLong(4),
                                duration = cursor.getLong(5),
                                presentation = cursor.getInt(6)
                            )
                    }
                }

                val resolvedNames =
                    identityResolver.resolve(
                        raw
                            .mapNotNull { it.number }
                            .filter {
                                PhoneNumberMatching.isUsableNumber(it)
                            }
                            .toSet()
                    )

                val entries =
                    raw.map { row ->

                        val usableNumber =
                            row.number
                                ?.trim()
                                ?.takeIf {
                                    PhoneNumberMatching.isUsableNumber(it)
                                }

                        /*
                         * Deterministic fallback order:
                         * ContactsProvider name, then CallLog cached
                         * name, then the raw number itself.
                         */
                        val displayName =
                            CallerIdentityFallbacks.resolve(
                                contactsProviderName = usableNumber
                                    ?.let { resolvedNames[it] },
                                contactsProviderPhotoUri = null,
                                callLogCachedName = row.cachedName,
                                number = usableNumber
                            ).displayName

                        RecentCallEntry(
                            id = row.id,

                            displayName = displayName,

                            number = usableNumber,

                            /*
                             * Preserve the existing UI behavior:
                             * REJECTED is visually grouped with MISSED.
                             */
                            type =
                                row.type.toRecentFilter(),

                            /*
                             * Preserve the exact provider semantics for
                             * the public Phone contract.
                             */
                            historyType =
                                row.type.toCallHistoryType(),

                            timestamp =
                                row.timestamp,

                            durationSeconds =
                                row.duration,

                            presentation =
                                row.presentation
                                    .toPresentation()
                        )
                    }

                Log.i(
                    TAG,
                    "Bounded CallLog query completed: ${entries.size} newest rows"
                )

                RecentCallsLoadResult(
                    if (entries.isEmpty()) {
                        RecentsStatus.RECENTS_EMPTY
                    } else {
                        RecentsStatus.RECENTS_READY
                    },
                    entries
                )

            } catch (security: SecurityException) {
                Log.w(
                    TAG,
                    "Call history permission was revoked while querying"
                )

                RecentCallsLoadResult(
                    RecentsStatus.RECENTS_PERMISSION_REQUIRED,
                    emptyList()
                )

            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Call history provider query failed",
                    exception
                )

                RecentCallsLoadResult(
                    RecentsStatus.RECENTS_ERROR,
                    emptyList()
                )
            }
        }

    private fun hasCallLogPermission():
        Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) ==
            PackageManager.PERMISSION_GRANTED

    private data class RawRecentCall(
        val id: Long,
        val cachedName: String?,
        val number: String?,
        val type: Int,
        val timestamp: Long,
        val duration: Long,
        val presentation: Int
    )

    /**
     * One bounded ContactsProvider pass for cache misses,
     * never one query per call row.
     */
    private class ContactIdentityResolver(
        private val context: Context
    ) {
        private val cache =
            mutableMapOf<String, String?>()

        fun clear() {
            synchronized(cache) {
                cache.clear()
            }
        }

        fun resolve(
            numbers: Set<String>
        ): Map<String, String?> {

            if (
                numbers.isEmpty() ||
                !hasContactsPermission()
            ) {
                return emptyMap()
            }

            val missing =
                numbers.filter {
                    synchronized(cache) {
                        !cache.containsKey(
                            keyOf(it)
                        )
                    }
                }

            if (missing.isNotEmpty()) {
                val candidates =
                    mutableListOf<Pair<String, String>>()

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->

                    while (cursor.moveToNext()) {
                        val candidateNumber =
                            cursor.getString(0)
                                ?.takeIf {
                                    PhoneNumberMatching.isUsableNumber(it)
                                }
                                ?: continue

                        val candidateName =
                            cursor.getString(1)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }
                                ?: continue

                        candidates +=
                            candidateNumber to
                                candidateName
                    }
                }

                synchronized(cache) {

                    missing.forEach { number ->

                        val resolvedName =
                            candidates
                                .firstOrNull {
                                        (candidate, _) ->

                                    PhoneNumberMatching.sameNumber(
                                        candidate,
                                        number
                                    )
                                }
                                ?.second

                        /*
                         * Never negative-cache a miss.
                         * PBAP may deliver this contact in a later batch.
                         */
                        if (resolvedName != null) {
                            cache[
                                keyOf(number)
                            ] =
                                resolvedName
                        } else {
                            cache.remove(
                                keyOf(number)
                            )
                        }
                    }
                }
            }

            return numbers.associateWith {
                    number ->

                synchronized(cache) {
                    cache[
                        keyOf(number)
                    ]
                }
            }
        }

        private fun keyOf(
            raw: String
        ): String =
            PhoneNumberMatching.cacheKey(raw)
                ?: raw.trim()

        private fun hasContactsPermission():
            Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) ==
                PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG =
            "HN-CallHistory"

        const val MAX_RECENT_CALLS =
            80

        val PROJECTION =
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NUMBER_PRESENTATION
            )
    }
}

/**
 * Existing UI grouping.
 *
 * Keep REJECTED visually under MISSED without losing the exact
 * Binder/NOVA semantic type.
 */
private fun Int.toRecentFilter():
    RecentFilter =
    when (this) {

        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE ->
            RecentFilter.MISSED

        CallLog.Calls.INCOMING_TYPE ->
            RecentFilter.INCOMING

        else ->
            RecentFilter.OUTGOING
    }

/**
 * Exact Android CallLog -> contract semantic mapping.
 *
 * MISSED and REJECTED are intentionally different.
 */
private fun Int.toCallHistoryType():
    CallHistoryType =
    when (this) {

        CallLog.Calls.INCOMING_TYPE ->
            CallHistoryType.INCOMING

        CallLog.Calls.OUTGOING_TYPE ->
            CallHistoryType.OUTGOING

        CallLog.Calls.MISSED_TYPE ->
            CallHistoryType.MISSED

        CallLog.Calls.REJECTED_TYPE ->
            CallHistoryType.REJECTED

        else ->
            CallHistoryType.OTHER
    }

private fun Int.toPresentation():
    CallNumberPresentation =
    when (this) {

        CallLog.Calls.PRESENTATION_RESTRICTED ->
            CallNumberPresentation.RESTRICTED

        CallLog.Calls.PRESENTATION_UNKNOWN ->
            CallNumberPresentation.UNKNOWN

        CallLog.Calls.PRESENTATION_PAYPHONE ->
            CallNumberPresentation.PAYPHONE

        CallLog.Calls.PRESENTATION_UNAVAILABLE ->
            CallNumberPresentation.UNAVAILABLE

        CallLog.Calls.PRESENTATION_ALLOWED ->
            CallNumberPresentation.ALLOWED

        else ->
            CallNumberPresentation.PRIVATE
    }

