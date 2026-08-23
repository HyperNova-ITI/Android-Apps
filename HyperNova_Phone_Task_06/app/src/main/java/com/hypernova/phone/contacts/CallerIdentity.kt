package com.hypernova.phone.contacts

/**
 * Caller identity resolved from real providers only.
 *
 * photoUri is a real ContactsProvider PHOTO_URI string or null.
 * It is never fabricated or replaced with a placeholder.
 */
data class CallerIdentity(
    val displayName: String?,
    val photoUri: String?
)

/**
 * Deterministic caller identity fallback order:
 *
 * 1) ContactsProvider resolved name (+ real photo URI)
 * 2) CallLog cached name
 * 3) the raw number itself
 */
object CallerIdentityFallbacks {

    fun resolve(
        contactsProviderName: String?,
        contactsProviderPhotoUri: String?,
        callLogCachedName: String?,
        number: String?
    ): CallerIdentity =
        resolveGated(
            contactsLookupAllowed = true,
            callLogLookupAllowed = true,
            contactsProviderName = contactsProviderName,
            contactsProviderPhotoUri = contactsProviderPhotoUri,
            callLogCachedName = callLogCachedName,
            number = number
        )

    /**
     * Availability-gated variant.
     *
     * A stage whose provider permission is missing never contributes
     * identity, so a permitted later fallback (or the raw number) is
     * reached deterministically.
     */
    fun resolveGated(
        contactsLookupAllowed: Boolean,
        callLogLookupAllowed: Boolean,
        contactsProviderName: String?,
        contactsProviderPhotoUri: String?,
        callLogCachedName: String?,
        number: String?
    ): CallerIdentity {

        val cleanedNumber =
            PhoneNumberMatching.normalize(number)
                ?.takeIf {
                    PhoneNumberMatching.isUsableNumber(it)
                }
                ?: return CallerIdentity(
                    displayName = null,
                    photoUri = null
                )

        val effectiveContactName =
            if (contactsLookupAllowed) {
                meaningfulName(
                    contactsProviderName,
                    cleanedNumber
                )
            } else {
                null
            }

        val effectivePhotoUri =
            if (contactsLookupAllowed &&
                effectiveContactName != null
            ) {
                PhoneNumberMatching.normalize(
                    contactsProviderPhotoUri
                )
            } else {
                null
            }

        val effectiveCachedName =
            if (callLogLookupAllowed) {
                meaningfulName(
                    callLogCachedName,
                    cleanedNumber
                )
            } else {
                null
            }

        if (effectiveContactName != null) {
            return CallerIdentity(
                displayName = effectiveContactName,
                photoUri = effectivePhotoUri
            )
        }

        if (effectiveCachedName != null) {
            return CallerIdentity(
                displayName = effectiveCachedName,
                photoUri = null
            )
        }

        return CallerIdentity(
            displayName = cleanedNumber,
            photoUri = null
        )
    }

    internal fun meaningfulName(
        candidate: String?,
        number: String?
    ): String? {

        val value =
            PhoneNumberMatching.normalize(candidate)
                ?: return null

        if (
            number != null &&
            PhoneNumberMatching.sameNumber(value, number)
        ) {
            return null
        }

        val digitCount =
            value.count { it.isDigit() }

        val numberLike =
            digitCount >= 5 &&
                value.all {
                    it.isDigit() || it in "+-() ./"
                }

        return if (numberLike) {
            null
        } else {
            value
        }
    }
}
