package com.hypernova.phone.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve only real caller identity data.
 *
 * Order:
 * 1) Telecom contact/caller display name.
 * 2) PhoneLookup.
 * 3) Full Android phone-contact scan.
 * 4) CallLog cached contact name.
 */
object CallerIdentityResolver {

    private val positiveCache =
        ConcurrentHashMap<String, String>()

    fun resolve(
        context: Context?,
        telecomDisplayName: String?,
        number: String?
    ): String? {

        val cleanNumber =
            number
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        usefulName(
            telecomDisplayName,
            cleanNumber
        )?.let { name ->
            if (
                cleanNumber !=
                null
            ) {
                positiveCache[
                    cleanNumber
                ] =
                    name
            }

            return name
        }

        if (
            context ==
            null ||
            cleanNumber ==
            null
        ) {
            return null
        }

        positiveCache[
            cleanNumber
        ]?.let {
            return it
        }

        val result =
            lookupPhoneLookup(
                context,
                cleanNumber
            )
                ?: scanAllAndroidPhoneContacts(
                    context,
                    cleanNumber
                )
                ?: lookupCallLog(
                    context,
                    cleanNumber
                )

        if (
            result !=
            null
        ) {
            positiveCache[
                cleanNumber
            ] =
                result
        }

        return result
    }

    private fun lookupPhoneLookup(
        context: Context,
        number: String
    ): String? {

        if (
            !hasPermission(
                context,
                Manifest.permission.READ_CONTACTS
            )
        ) {
            return null
        }

        return try {
            val uri =
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
                )

            context.contentResolver
                .query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.NUMBER
                    ),
                    null,
                    null,
                    null
                )
                ?.use { cursor ->
                    while (
                        cursor.moveToNext()
                    ) {
                        val name =
                            usefulName(
                                cursor.getString(0),
                                number
                            )

                        val candidateNumber =
                            cursor.getString(1)

                        if (
                            name !=
                            null &&
                            (
                                candidateNumber ==
                                    null ||
                                    sameNumber(
                                        candidateNumber,
                                        number
                                    )
                                )
                        ) {
                            Log.i(
                                TAG,
                                "Caller resolved by PhoneLookup"
                            )

                            return name
                        }
                    }
                }

            null
        } catch (
            exception:
                Exception
        ) {
            Log.w(
                TAG,
                "PhoneLookup failed",
                exception
            )

            null
        }
    }

    private fun scanAllAndroidPhoneContacts(
        context: Context,
        incomingNumber: String
    ): String? {

        if (
            !hasPermission(
                context,
                Manifest.permission.READ_CONTACTS
            )
        ) {
            return null
        }

        return try {
            context.contentResolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    null
                )
                ?.use { cursor ->
                    var inspected =
                        0

                    while (
                        cursor.moveToNext() &&
                        inspected <
                        MAX_CONTACT_ROWS
                    ) {
                        inspected +=
                            1

                        val candidateName =
                            cursor.getString(0)

                        val candidateNumber =
                            cursor.getString(1)
                                ?.trim()
                                .orEmpty()

                        if (
                            candidateNumber.isBlank() ||
                            !sameNumber(
                                candidateNumber,
                                incomingNumber
                            )
                        ) {
                            continue
                        }

                        val realName =
                            usefulName(
                                candidateName,
                                incomingNumber
                            )

                        if (
                            realName !=
                            null
                        ) {
                            Log.i(
                                TAG,
                                "Caller resolved by Android contacts scan"
                            )

                            return realName
                        }
                    }
                }

            null
        } catch (
            exception:
                Exception
        ) {
            Log.w(
                TAG,
                "Full contacts scan failed",
                exception
            )

            null
        }
    }

    private fun lookupCallLog(
        context: Context,
        incomingNumber: String
    ): String? {

        if (
            !hasPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            )
        ) {
            return null
        }

        return try {
            context.contentResolver
                .query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )
                ?.use { cursor ->
                    var inspected =
                        0

                    while (
                        cursor.moveToNext() &&
                        inspected <
                        MAX_CALL_LOG_ROWS
                    ) {
                        inspected +=
                            1

                        val candidateNumber =
                            cursor.getString(0)
                                ?.trim()
                                .orEmpty()

                        if (
                            candidateNumber.isBlank() ||
                            !sameNumber(
                                candidateNumber,
                                incomingNumber
                            )
                        ) {
                            continue
                        }

                        val realName =
                            usefulName(
                                cursor.getString(1),
                                incomingNumber
                            )

                        if (
                            realName !=
                            null
                        ) {
                            Log.i(
                                TAG,
                                "Caller resolved by CallLog cache"
                            )

                            return realName
                        }
                    }
                }

            null
        } catch (
            exception:
                Exception
        ) {
            Log.w(
                TAG,
                "CallLog caller lookup failed",
                exception
            )

            null
        }
    }

    private fun usefulName(
        candidate: String?,
        number: String?
    ): String? {

        val value =
            candidate
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        if (
            number !=
            null &&
            sameNumber(
                value,
                number
            )
        ) {
            return null
        }

        val numericCharacters =
            value.count {
                it.isDigit()
            }

        val numberLike =
            numericCharacters >=
                5 &&
                value.all { character ->
                    character.isDigit() ||
                        character in
                        "+-() ./"
                }

        if (
            numberLike
        ) {
            return null
        }

        return value
    }

    @Suppress(
        "DEPRECATION"
    )
    private fun sameNumber(
        first: String,
        second: String
    ): Boolean {

        try {
            if (
                PhoneNumberUtils.compare(
                    first,
                    second
                )
            ) {
                return true
            }
        } catch (
            ignored:
                RuntimeException
        ) {
        }

        val a =
            normalizeDigits(first)

        val b =
            normalizeDigits(second)

        if (
            a.length <
            MIN_DIGITS ||
            b.length <
            MIN_DIGITS
        ) {
            return false
        }

        return a.takeLast(
            MATCH_DIGITS
        ) ==
            b.takeLast(
                MATCH_DIGITS
            )
    }

    private fun normalizeDigits(
        value: String
    ): String {
        return value.filter {
            it.isDigit()
        }
    }

    private fun hasPermission(
        context: Context,
        permission: String
    ): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val MAX_CONTACT_ROWS =
        5000

    private const val MAX_CALL_LOG_ROWS =
        250

    private const val MIN_DIGITS =
        7

    private const val MATCH_DIGITS =
        9

    private const val TAG =
        "HN-Caller"
}
