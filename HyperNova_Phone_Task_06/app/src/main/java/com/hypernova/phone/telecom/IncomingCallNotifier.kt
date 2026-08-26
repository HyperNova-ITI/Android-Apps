package com.hypernova.phone.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.phone.R
import com.hypernova.phone.contacts.CallerIdentityFallbacks
import com.hypernova.phone.contacts.ContactsRepository
import com.hypernova.phone.contacts.PhoneNumberMatching
import com.hypernova.phone.domain.TelecomCallState

/**
 * HyperNova incoming-call Heads-Up Notification.
 *
 * Data source:
 *
 * Android Telecom
 *      ↓
 * TelecomCallController
 *      ↓
 * TelecomCallState
 *      ↓
 * Telecom caller name if available
 *      ↓ otherwise
 * ContactsProvider PhoneLookup / bounded number scan
 *      ↓ otherwise
 * CallLog cached name
 *      ↓
 * HyperNova HUN
 *
 * No fake caller identity is ever generated.
 */
class IncomingCallNotifier(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val contactsRepository =
        ContactsRepository(appContext)

    private val notificationManager: NotificationManager
        get() =
            appContext.getSystemService(
                NotificationManager::class.java
            )

    /**
     * Show or update the current incoming-call HUN.
     *
     * This is suspend because contact identity fallback may query the
     * Android ContactsProvider on Dispatchers.IO.
     */
    suspend fun showIncomingCall(
        state: TelecomCallState
    ) {
        createIncomingCallChannel()

        if (!canPostNotifications()) {
            Log.w(
                TAG,
                "Cannot display incoming-call HUN: POST_NOTIFICATIONS unavailable"
            )
            return
        }

        val presentation =
            resolveCallerPresentation(state)

        val answerPendingIntent =
            PendingIntent.getBroadcast(
                appContext,
                REQUEST_ANSWER,
                Intent(
                    appContext,
                    CallActionReceiver::class.java
                ).apply {
                    action =
                        CallActionReceiver.ACTION_ANSWER_CALL
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val declinePendingIntent =
            PendingIntent.getBroadcast(
                appContext,
                REQUEST_DECLINE,
                Intent(
                    appContext,
                    CallActionReceiver::class.java
                ).apply {
                    action =
                        CallActionReceiver.ACTION_DECLINE_CALL
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val openPhonePendingIntent =
            PendingIntent.getActivity(
                appContext,
                REQUEST_OPEN_PHONE,
                Intent(
                    appContext,
                    IncomingCallActivity::class.java
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val actionIcon =
            Icon.createWithResource(
                appContext,
                R.drawable.ic_notification_phone
            )

        val declineAction =
            Notification.Action.Builder(
                actionIcon,
                "Decline",
                declinePendingIntent
            ).build()

        val answerAction =
            Notification.Action.Builder(
                actionIcon,
                "Answer",
                answerPendingIntent
            ).build()

        val notification =
            Notification.Builder(
                appContext,
                CHANNEL_INCOMING_CALLS
            )
                .setSmallIcon(
                    R.drawable.ic_notification_phone
                )
                .setContentTitle(
                    presentation.primary
                )
                .setContentText(
                    presentation.secondary
                )
                .setCategory(
                    Notification.CATEGORY_CALL
                )
                .setVisibility(
                    Notification.VISIBILITY_PUBLIC
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setContentIntent(
                    openPhonePendingIntent
                )
                .addAction(
                    declineAction
                )
                .addAction(
                    answerAction
                )
                .build()

        /*
         * NotificationManager is an external system boundary.
         *
         * Vendor Android / AAOS SystemUI policy must never be allowed
         * to crash the Telecom call pipeline.
         */
        try {

            notificationManager.notify(
                NOTIFICATION_ID_INCOMING_CALL,
                notification
            )

            Log.i(
                TAG,
                "HyperNova incoming-call HUN shown"
            )

        } catch (error: RuntimeException) {

            Log.e(
                TAG,
                "Android rejected HyperNova incoming-call HUN",
                error
            )
        }
    }

    /**
     * Remove the HUN when the call is answered, rejected, disconnected,
     * or otherwise leaves INCOMING state.
     */
    fun cancelIncomingCall() {

        notificationManager.cancel(
            NOTIFICATION_ID_INCOMING_CALL
        )

        Log.i(
            TAG,
            "Incoming-call notification cancelled"
        )
    }

    /**
     * Resolve the caller shown to the driver.
     *
     * Priority:
     *
     * 1. Real callerDisplayName supplied by Android Telecom,
     *    provided it is not merely the phone number again.
     *
     * 2. Real Android ContactsProvider result.
     *
     * 3. Real CallLog cached contact name.
     *
     * 4. Real incoming telephone number.
     *
     * 5. Generic "Unknown number".
     */
    private suspend fun resolveCallerPresentation(
        state: TelecomCallState
    ): CallerPresentation {

        val number =
            state.number
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        val telecomDisplayName =
            state.displayName
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        /*
         * Some Telecom implementations expose the incoming number in
         * callerDisplayName as well.
         *
         * That must not prevent us from performing the real Contacts
         * lookup.
         */
        val meaningfulTelecomName =
            CallerIdentityFallbacks
                .meaningfulName(
                    telecomDisplayName,
                    number
                )

        if (meaningfulTelecomName != null) {

            Log.i(
                TAG,
                "Using caller identity supplied by Android Telecom"
            )

            return CallerPresentation(
                primary =
                    meaningfulTelecomName,

                secondary =
                    number
                        ?: INCOMING_CALL_LABEL
            )
        }

        /*
         * Telecom gave us a real number but no useful contact name.
         * Ask the real Android ContactsProvider.
         */
        if (number != null) {

            val providerIdentity =
                contactsRepository
                    .resolveCallerIdentity(
                        number
                    )

            val contactName =
                providerIdentity
                    ?.displayName
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty() &&
                            !PhoneNumberMatching.sameNumber(
                                it,
                                number
                            )
                    }

            if (contactName != null) {

                Log.i(
                    TAG,
                    "Using caller identity resolved from Android Contacts"
                )

                return CallerPresentation(
                    primary =
                        contactName,

                    secondary =
                        number
                )
            }
        }

        /*
         * Never fabricate identity for an unsaved number.
         */
        if (number != null) {

            Log.i(
                TAG,
                "Incoming caller is not resolved to a saved contact"
            )

            return CallerPresentation(
                primary =
                    number,

                secondary =
                    INCOMING_CALL_LABEL
            )
        }

        /*
         * Telecom may occasionally expose a non-number display label
         * while hiding the number. Preserve that real label.
         */
        if (telecomDisplayName != null) {

            return CallerPresentation(
                primary =
                    telecomDisplayName,

                secondary =
                    INCOMING_CALL_LABEL
            )
        }

        return CallerPresentation(
            primary =
                UNKNOWN_CALLER_LABEL,

            secondary =
                INCOMING_CALL_LABEL
        )
    }

    /**
     * High-importance notification channel for Android / AAOS heads-up
     * presentation.
     *
     * Telecom owns ringtone audio, so HyperNova does not create a
     * duplicate notification sound.
     */
    private fun createIncomingCallChannel() {

        if (
            notificationManager
                .getNotificationChannel(
                    CHANNEL_INCOMING_CALLS
                ) != null
        ) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_INCOMING_CALLS,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    CHANNEL_DESCRIPTION

                lockscreenVisibility =
                    Notification.VISIBILITY_PUBLIC

                setSound(
                    null,
                    null
                )

                enableVibration(
                    false
                )
            }

        notificationManager
            .createNotificationChannel(
                channel
            )

        Log.i(
            TAG,
            "HyperNova incoming-call HUN channel created"
        )
    }

    private fun canPostNotifications(): Boolean {

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) ==
            PackageManager.PERMISSION_GRANTED
    }

    private data class CallerPresentation(
        val primary: String,
        val secondary: String
    )

    companion object {

        private const val TAG =
            "HN-IncomingCall"

        const val CHANNEL_INCOMING_CALLS =
            "hypernova_incoming_call_hun_v2"

        private const val CHANNEL_NAME =
            "HyperNova incoming calls"

        private const val CHANNEL_DESCRIPTION =
            "Incoming call heads-up notifications"

        private const val UNKNOWN_CALLER_LABEL =
            "Unknown number"

        private const val INCOMING_CALL_LABEL =
            "Incoming call"

        private const val NOTIFICATION_ID_INCOMING_CALL =
            6001

        private const val REQUEST_ANSWER =
            6002

        private const val REQUEST_DECLINE =
            6003

        private const val REQUEST_OPEN_PHONE =
            6004
    }
}
