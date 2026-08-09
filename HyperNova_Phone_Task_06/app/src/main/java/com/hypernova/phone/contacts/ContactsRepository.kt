package com.hypernova.phone.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.phone.domain.ContactEntry
import com.hypernova.phone.domain.ContactsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Android Contacts provider integration.
 *
 * No fake contact data is generated.
 *
 * All ContentResolver operations run on Dispatchers.IO.
 */
class ContactsRepository(
    private val context: Context
) {

    /**
     * Load the real Android contacts list.
     */
    suspend fun load(): Pair<ContactsStatus, List<ContactEntry>> =
        withContext(Dispatchers.IO) {

            if (!hasContactsPermission()) {
                return@withContext Pair(
                    ContactsStatus.PERMISSION_REQUIRED,
                    emptyList<ContactEntry>()
                )
            }

            try {
                val entries = linkedMapOf<Long, ContactEntry>()

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.Contacts.STARRED
                    ),
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE"
                )?.use { cursor ->

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)

                        if (entries.containsKey(id)) {
                            continue
                        }

                        val displayName = cursor
                            .getString(1)
                            .orEmpty()

                        val number = cursor
                            .getString(2)
                            .orEmpty()

                        val label =
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(
                                    context.resources,
                                    cursor.getInt(3),
                                    null
                                )
                                ?.toString()

                        entries[id] = ContactEntry(
                            id = id,
                            displayName = displayName.ifBlank { number },
                            number = number,
                            label = label,
                            isFavorite = cursor.getInt(4) != 0
                        )
                    }
                }

                Log.i(
                    TAG,
                    "Contacts query completed with ${entries.size} entries"
                )

                val result = entries.values.toList()

                Pair(
                    if (result.isEmpty()) {
                        ContactsStatus.CONTACTS_EMPTY
                    } else {
                        ContactsStatus.CONTACTS_READY
                    },
                    result
                )

            } catch (security: SecurityException) {
                Log.w(
                    TAG,
                    "Contacts permission was revoked while querying"
                )

                Pair(
                    ContactsStatus.PERMISSION_REQUIRED,
                    emptyList<ContactEntry>()
                )

            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Contacts provider query failed",
                    exception
                )

                Pair(
                    ContactsStatus.CONTACTS_SYNC_FAILED,
                    emptyList<ContactEntry>()
                )
            }
        }

    /**
     * Resolve one real incoming phone number to one real contact name.
     *
     * This is deliberately a targeted PhoneLookup query rather than
     * reloading the complete contacts database for every incoming call.
     *
     * Returns:
     *
     * saved contact   -> real contact display name
     * unsaved number  -> null
     * no permission   -> null
     */
    suspend fun findDisplayNameByNumber(
        number: String
    ): String? = withContext(Dispatchers.IO) {

        val cleanedNumber = number
            .trim()
            .takeIf { it.isNotEmpty() }

        if (cleanedNumber == null) {
            return@withContext null
        }

        if (!hasContactsPermission()) {
            Log.w(
                TAG,
                "Cannot resolve caller identity: READ_CONTACTS unavailable"
            )

            return@withContext null
        }

        try {
            /*
             * Android PhoneLookup handles contact-number matching and
             * common formatting variations more appropriately than a
             * simple SQL equality comparison.
             */
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanedNumber)
            )

            context.contentResolver.query(
                lookupUri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->

                while (cursor.moveToNext()) {
                    val candidateName = cursor
                        .getString(0)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: continue

                    val candidateNumber = cursor
                        .getString(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }

                    /*
                     * PhoneLookup has already filtered the provider.
                     * Keep an additional sanity check when Android
                     * supplies the candidate number.
                     */
                    val matches = candidateNumber == null ||
                        candidateNumber == cleanedNumber ||
                        PhoneNumberUtils.compare(
                            candidateNumber,
                            cleanedNumber
                        )

                    if (matches) {
                        Log.i(
                            TAG,
                            "Real contact identity resolved for incoming call"
                        )

                        return@withContext candidateName
                    }
                }
            }

            Log.i(
                TAG,
                "Incoming number is not saved in Android Contacts"
            )

            null

        } catch (security: SecurityException) {
            Log.w(
                TAG,
                "READ_CONTACTS was revoked during caller lookup"
            )

            null

        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Incoming caller contact lookup failed",
                exception
            )

            null
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "HN-Contacts"
    }
}
