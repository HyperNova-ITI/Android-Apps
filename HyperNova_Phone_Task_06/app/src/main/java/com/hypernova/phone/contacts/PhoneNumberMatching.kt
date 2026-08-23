package com.hypernova.phone.contacts

import android.telephony.PhoneNumberUtils

/**
 * Pure-Kotlin phone number normalization and matching.
 *
 * Deterministic in JVM unit tests: PhoneNumberUtils.compare is wrapped
 * so framework stubs falling back to pure logic stay consistent.
 */
object PhoneNumberMatching {

    private const val MIN_SUFFIX_DIGITS = 7
    private const val SUFFIX_MATCH_DIGITS = 9

    fun normalize(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }

    fun isUsableNumber(raw: String?): Boolean =
        normalize(raw)?.any { it.isDigit() } == true

    fun cacheKey(raw: String?): String? {
        val value = normalize(raw) ?: return null
        val key = buildString {
            if (value.startsWith("+")) append('+')
            value.filterTo(this) { it.isDigit() }
        }
        return key.takeIf { it.any { c -> c.isDigit() } }
    }

    fun sameNumber(first: String?, second: String?): Boolean {
        val a = normalize(first) ?: return false
        val b = normalize(second) ?: return false

        val keyA = cacheKey(a) ?: return false
        val keyB = cacheKey(b) ?: return false

        if (keyA == keyB) {
            return true
        }

        try {
            if (PhoneNumberUtils.compare(a, b)) {
                return true
            }
        } catch (_: RuntimeException) {
        }

        val digitsA = keyA.trimStart('+')
        val digitsB = keyB.trimStart('+')

        if (digitsA.length < MIN_SUFFIX_DIGITS ||
            digitsB.length < MIN_SUFFIX_DIGITS
        ) {
            return false
        }

        return digitsA.takeLast(SUFFIX_MATCH_DIGITS) ==
            digitsB.takeLast(SUFFIX_MATCH_DIGITS)
    }
}
