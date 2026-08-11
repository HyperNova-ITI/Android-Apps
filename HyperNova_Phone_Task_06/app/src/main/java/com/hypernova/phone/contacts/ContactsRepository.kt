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
 * One real phone-number row belonging to one Android contact.
 *
 * numberId is ContactsContract.CommonDataKinds.Phone._ID.
 */
data class ContactNumberRecord(
    val numberId: Long,
    val label: String?,
    val displayNumber: String,
    val primary: Boolean
)

/**
 * Detailed real Android contact record used by the Phone command layer.
 */
data class ContactDetailsRecord(
    val contactId: Long,
    val displayName: String,
    val numbers: List<ContactNumberRecord>
)

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
     *
     * This is intentionally one row per contact for UI/search discovery.
     * Full multi-number expansion is handled by loadContact().
     */
    suspend fun load():
        Pair<ContactsStatus, List<ContactEntry>> =
        withContext(Dispatchers.IO) {

            if (!hasContactsPermission()) {
                return@withContext Pair(
                    ContactsStatus.PERMISSION_REQUIRED,
                    emptyList<ContactEntry>()
                )
            }

            try {
                val entries =
                    linkedMapOf<Long, ContactEntry>()

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
                        val id =
                            cursor.getLong(0)

                        if (
                            entries.containsKey(id)
                        ) {
                            continue
                        }

                        val displayName =
                            cursor.getString(1)
                                .orEmpty()

                        val number =
                            cursor.getString(2)
                                .orEmpty()

                        val label =
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(
                                    context.resources,
                                    cursor.getInt(3),
                                    null
                                )
                                ?.toString()

                        entries[id] =
                            ContactEntry(
                                id = id,
                                displayName =
                                    displayName
                                        .ifBlank {
                                            number
                                        },
                                number = number,
                                label = label,
                                isFavorite =
                                    cursor.getInt(4) != 0
                            )
                    }
                }

                Log.i(
                    TAG,
                    "Contacts query completed with ${entries.size} entries"
                )

                val result =
                    entries.values.toList()

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
     * Load one selected contact using its real ContactsProvider CONTACT_ID.
     *
     * Every real phone row is returned with Phone._ID as numberId.
     */
    suspend fun loadContact(
        contactId: Long
    ): ContactDetailsRecord? =
        withContext(Dispatchers.IO) {

            if (
                contactId <= 0L
            ) {
                return@withContext null
            }

            if (!hasContactsPermission()) {
                Log.w(
                    TAG,
                    "Cannot load contact details: READ_CONTACTS unavailable"
                )

                return@withContext null
            }

            try {
                var displayName:
                    String? = null

                val numbers =
                    linkedMapOf<Long, ContactNumberRecord>()

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone._ID,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL,
                        ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(
                        contactId.toString()
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
                )?.use { cursor ->

                    while (cursor.moveToNext()) {
                        val numberId =
                            cursor.getLong(0)

                        val rowContactId =
                            cursor.getLong(1)

                        if (
                            rowContactId !=
                            contactId
                        ) {
                            continue
                        }

                        val rowDisplayName =
                            cursor.getString(2)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }

                        val number =
                            cursor.getString(3)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }
                                ?: continue

                        val type =
                            cursor.getInt(4)

                        val customLabel =
                            cursor.getString(5)

                        val label =
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(
                                    context.resources,
                                    type,
                                    customLabel
                                )
                                ?.toString()
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }

                        val primary =
                            cursor.getInt(6) != 0

                        if (
                            displayName == null
                        ) {
                            displayName =
                                rowDisplayName
                                    ?: number
                        }

                        numbers[numberId] =
                            ContactNumberRecord(
                                numberId = numberId,
                                label = label,
                                displayNumber = number,
                                primary = primary
                            )
                    }
                }

                if (
                    numbers.isEmpty()
                ) {
                    Log.i(
                        TAG,
                        "No real phone rows found for contactId=$contactId"
                    )

                    return@withContext null
                }

                val result =
                    ContactDetailsRecord(
                        contactId = contactId,

                        displayName =
                            displayName
                                ?: numbers.values
                                    .first()
                                    .displayNumber,

                        numbers =
                            numbers.values
                                .toList()
                    )

                Log.i(
                    TAG,
                    "Loaded real contact details " +
                        "contactId=$contactId " +
                        "numbers=${result.numbers.size}"
                )

                result

            } catch (security: SecurityException) {
                Log.w(
                    TAG,
                    "READ_CONTACTS was revoked during contact detail query"
                )

                null

            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Contact detail query failed",
                    exception
                )

                null
            }
        }

    /**
     * Resolve one real phone number to a real ContactsProvider CONTACT_ID.
     *
     * Used only to enrich CallLog results for the cross-APK contract.
     */
    suspend fun findContactIdByNumber(
        number: String
    ): Long? =
        withContext(Dispatchers.IO) {

            val cleanedNumber =
                number.trim()
                    .takeIf {
                        it.isNotEmpty()
                    }
                    ?: return@withContext null

            if (!hasContactsPermission()) {
                return@withContext null
            }

            try {
                val lookupUri =
                    Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(cleanedNumber)
                    )

                context.contentResolver.query(
                    lookupUri,
                    arrayOf(
                        ContactsContract.PhoneLookup.CONTACT_ID,
                        ContactsContract.PhoneLookup.NUMBER
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->

                    while (
                        cursor.moveToNext()
                    ) {
                        val candidateId =
                            cursor.getLong(0)

                        val candidateNumber =
                            cursor.getString(1)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }

                        val matches =
                            candidateNumber == null ||
                                candidateNumber ==
                                    cleanedNumber ||
                                PhoneNumberUtils.compare(
                                    candidateNumber,
                                    cleanedNumber
                                )

                        if (
                            matches
                        ) {
                            return@withContext candidateId
                        }
                    }
                }

                null

            } catch (security: SecurityException) {
                null

            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Contact ID lookup failed",
                    exception
                )

                null
            }
        }

    /**
     * Resolve one real incoming phone number to one real contact name.
     */
    suspend fun findDisplayNameByNumber(
        number: String
    ): String? =
        withContext(Dispatchers.IO) {

            val cleanedNumber =
                number.trim()
                    .takeIf {
                        it.isNotEmpty()
                    }

            if (
                cleanedNumber == null
            ) {
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
                val lookupUri =
                    Uri.withAppendedPath(
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

                    while (
                        cursor.moveToNext()
                    ) {
                        val candidateName =
                            cursor.getString(0)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }
                                ?: continue

                        val candidateNumber =
                            cursor.getString(1)
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }

                        val matches =
                            candidateNumber == null ||
                                candidateNumber ==
                                    cleanedNumber ||
                                PhoneNumberUtils.compare(
                                    candidateNumber,
                                    cleanedNumber
                                )

                        if (
                            matches
                        ) {
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

    private fun hasContactsPermission():
        Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG =
            "HN-Contacts"
    }
}

