package com.hypernova.phone.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.telecom.CallAudioState
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.contracts.HyperNovaContract
import com.hypernova.contracts.phone.IPhoneCommandCallback
import com.hypernova.contracts.phone.IPhoneCommandService
import com.hypernova.contracts.phone.IPhoneStatusCallback
import com.hypernova.contracts.phone.PhoneCallHistoryEntry
import com.hypernova.contracts.phone.PhoneContact
import com.hypernova.contracts.phone.PhoneContactNumber
import com.hypernova.contracts.phone.PhoneContract
import com.hypernova.contracts.phone.PhoneResult
import com.hypernova.contracts.phone.PhoneState
import com.hypernova.phone.data.PhoneRepository
import com.hypernova.phone.domain.BluetoothConnectionState
import com.hypernova.phone.domain.CallHistoryType
import com.hypernova.phone.domain.CallNumberPresentation
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.ContactsStatus
import com.hypernova.phone.domain.PhoneAvailability
import com.hypernova.phone.domain.PhoneDataState
import com.hypernova.phone.domain.RecentCallEntry
import com.hypernova.phone.domain.RecentsStatus
import com.hypernova.phone.telecom.CallAudioController
import com.hypernova.phone.telecom.HyperNovaInCallService
import com.hypernova.phone.telecom.TelecomCallController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * Signature-protected Binder server for:
 *
 * NOVA -> HyperNova Phone
 *
 * Phone is the source of truth for:
 * - HFP readiness
 * - ContactsProvider
 * - CallLog
 * - Android Telecom
 * - confirmed call/audio state
 *
 * NOVA owns:
 * - speech / NLU
 * - candidate conversation
 * - pronouns / ordinals
 * - clarification wording
 */
class PhoneCommandService : Service() {

    private val serviceJob =
        SupervisorJob()

    private val serviceScope =
        CoroutineScope(
            serviceJob +
                Dispatchers.Default
        )

    private lateinit var repository:
        PhoneRepository

    private lateinit var telecom:
        TelecomCallController

    private val audioController =
        CallAudioController()

    private val statusCallbacks =
        RemoteCallbackList<IPhoneStatusCallback>()

    @Volatile
    private var latestState:
        PhoneDataState? = null

    private data class CachedRequest(
        val operation: String,
        var result: PhoneResult?,
        var updatedAtMillis: Long
    )

    private val requestLock =
        Any()

    private val requestCache =
        linkedMapOf<String, CachedRequest>()

    private val binder =
        object :
            IPhoneCommandService.Stub() {

            override fun getApiVersion():
                Int =
                HyperNovaContract.API_VERSION

            override fun getCurrentState(
                requestId: String,
                callback: IPhoneCommandCallback
            ) {
                handleGetCurrentState(
                    requestId,
                    callback
                )
            }

            override fun searchContacts(
                requestId: String,
                query: String,
                limit: Int,
                callback: IPhoneCommandCallback
            ) {
                handleSearchContacts(
                    requestId,
                    query,
                    limit,
                    callback
                )
            }

            override fun getContact(
                requestId: String,
                contactId: String,
                callback: IPhoneCommandCallback
            ) {
                handleGetContact(
                    requestId,
                    contactId,
                    callback
                )
            }

            override fun getCallHistory(
                requestId: String,
                filter: Int,
                limit: Int,
                callback: IPhoneCommandCallback
            ) {
                handleGetCallHistory(
                    requestId,
                    filter,
                    limit,
                    callback
                )
            }

            override fun getCallHistoryForContact(
                requestId: String,
                contactId: String,
                filter: Int,
                limit: Int,
                callback: IPhoneCommandCallback
            ) {
                handleGetCallHistoryForContact(
                    requestId,
                    contactId,
                    filter,
                    limit,
                    callback
                )
            }

            override fun callContact(
                requestId: String,
                contactId: String,
                numberId: String,
                callback: IPhoneCommandCallback
            ) {
                handleCallContact(
                    requestId,
                    contactId,
                    numberId,
                    callback
                )
            }

            override fun callNumber(
                requestId: String,
                phoneNumber: String,
                callback: IPhoneCommandCallback
            ) {
                handleCallNumber(
                    requestId,
                    phoneNumber,
                    callback
                )
            }

            override fun callHistoryEntry(
                requestId: String,
                callId: String,
                callback: IPhoneCommandCallback
            ) {
                handleCallHistoryEntry(
                    requestId,
                    callId,
                    callback
                )
            }

            override fun answerCall(
                requestId: String,
                callback: IPhoneCommandCallback
            ) {
                handleAnswerCall(
                    requestId,
                    callback
                )
            }

            override fun declineCall(
                requestId: String,
                callback: IPhoneCommandCallback
            ) {
                handleDeclineCall(
                    requestId,
                    callback
                )
            }

            override fun endCall(
                requestId: String,
                callback: IPhoneCommandCallback
            ) {
                handleEndCall(
                    requestId,
                    callback
                )
            }

            override fun setMuted(
                requestId: String,
                muted: Boolean,
                callback: IPhoneCommandCallback
            ) {
                handleSetMuted(
                    requestId,
                    muted,
                    callback
                )
            }

            override fun setHeld(
                requestId: String,
                held: Boolean,
                callback: IPhoneCommandCallback
            ) {
                handleSetHeld(
                    requestId,
                    held,
                    callback
                )
            }

            override fun setAudioRoute(
                requestId: String,
                route: Int,
                callback: IPhoneCommandCallback
            ) {
                handleSetAudioRoute(
                    requestId,
                    route,
                    callback
                )
            }

            override fun sendDtmf(
                requestId: String,
                digit: String,
                callback: IPhoneCommandCallback
            ) {
                handleSendDtmf(
                    requestId,
                    digit,
                    callback
                )
            }

            override fun registerPhoneStatusCallback(
                callback: IPhoneStatusCallback
            ) {
                statusCallbacks.register(
                    callback
                )

                try {
                    callback.onStateChanged(
                        currentContractState()
                    )
                } catch (
                    exception: RemoteException
                ) {
                    Log.w(
                        TAG,
                        "Initial status callback failed",
                        exception
                    )
                }
            }

            override fun unregisterPhoneStatusCallback(
                callback: IPhoneStatusCallback
            ) {
                statusCallbacks.unregister(
                    callback
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        repository =
            PhoneRepository(
                context =
                    applicationContext,
                scope =
                    serviceScope
            )

        telecom =
            TelecomCallController(
                applicationContext
            )

        serviceScope.launch {

            repository.state.collect {
                    state ->

                latestState =
                    state

                broadcastState(
                    state.toContractState()
                )
            }
        }

        repository.start()

        Log.i(
            TAG,
            "Phone command service created"
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        if (
            intent?.action !=
            PhoneContract.BIND_COMMAND_ACTION
        ) {
            Log.w(
                TAG,
                "Rejected bind action=${intent?.action}"
            )

            return null
        }

        Log.i(
            TAG,
            "NOVA Phone command client bound"
        )

        return binder
    }

    override fun onDestroy() {

        statusCallbacks.kill()

        repository.stop()

        serviceScope.cancel()

        Log.i(
            TAG,
            "Phone command service destroyed"
        )

        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private fun handleGetCurrentState(
        requestId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_GET_CURRENT_STATE

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        repository.refreshCapabilities()

        finishRequest(
            callback,
            PhoneResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_CONFIRMED,
                "Current Phone state",
                HyperNovaContract.ERROR_NONE,
                -1,
                emptyList(),
                null,
                emptyList(),
                currentContractState()
            )
        )
    }

    // ---------------------------------------------------------------------
    // Contacts
    // ---------------------------------------------------------------------

    private fun handleSearchContacts(
        requestId: String,
        query: String,
        limit: Int,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_SEARCH_CONTACTS

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val cleanQuery =
            query.trim()

        if (
            cleanQuery.isEmpty()
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Contact search query must not be blank"
            )
            return
        }

        if (
            !hasPermission(
                Manifest.permission.READ_CONTACTS
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_UNAVAILABLE,
                    "READ_CONTACTS permission is unavailable",
                    HyperNovaContract.ERROR_PERMISSION_DENIED,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        val effectiveLimit =
            normalizeContactLimit(
                limit
            )

        serviceScope.launch {

            repository.refreshContacts()

            val state =
                awaitContactsState()

            if (
                state == null
            ) {
                finishTimeout(
                    requestId,
                    operation,
                    callback,
                    "Timed out while loading contacts"
                )
                return@launch
            }

            when (
                state.contactsStatus
            ) {
                ContactsStatus.PERMISSION_REQUIRED -> {
                    finishRequest(
                        callback,
                        PhoneResult(
                            requestId,
                            operation,
                            HyperNovaContract.STATUS_UNAVAILABLE,
                            "READ_CONTACTS permission is unavailable",
                            HyperNovaContract.ERROR_PERMISSION_DENIED,
                            -1,
                            emptyList(),
                            null,
                            emptyList(),
                            null
                        )
                    )
                    return@launch
                }

                ContactsStatus.CONTACTS_SYNC_FAILED -> {
                    finishRequest(
                        callback,
                        PhoneResult(
                            requestId,
                            operation,
                            HyperNovaContract.STATUS_UNAVAILABLE,
                            "Android Contacts provider is unavailable",
                            HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                            -1,
                            emptyList(),
                            null,
                            emptyList(),
                            null
                        )
                    )
                    return@launch
                }

                ContactsStatus.CONTACTS_SYNCING -> {
                    finishTimeout(
                        requestId,
                        operation,
                        callback,
                        "Contacts are still loading"
                    )
                    return@launch
                }

                ContactsStatus.CONTACTS_EMPTY,
                ContactsStatus.CONTACTS_READY ->
                    Unit
            }

            var allMatches =
                state.contacts
                    .filter {
                            entry ->

                        entry.displayName
                            .contains(
                                cleanQuery,
                                ignoreCase = true
                            ) ||
                            entry.number
                                .contains(
                                    cleanQuery,
                                    ignoreCase = true
                                )
                    }

            /*
             * PBAP may still be adding the requested contact.
             * Retry once against a fresh real-provider snapshot.
             */
            if (
                allMatches.isEmpty() &&
                repository
                    .contactsProviderChangingRecently()
            ) {
                delay(
                    750L
                )

                repository
                    .refreshContacts()

                val retryState =
                    awaitContactsState()

                if (retryState != null) {
                    allMatches =
                        retryState.contacts
                            .filter {
                                    entry ->

                                entry.displayName
                                    .contains(
                                        cleanQuery,
                                        ignoreCase = true
                                    ) ||
                                    entry.number
                                        .contains(
                                            cleanQuery,
                                            ignoreCase = true
                                        )
                            }
                }
            }

            /*
             * Do not report CONTACT_NOT_FOUND while PBAP is still changing
             * ContactsProvider. NOVA can ask the user to wait briefly.
             */
            if (
                allMatches.isEmpty() &&
                repository
                    .contactsProviderChangingRecently()
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "Contacts are still syncing",
                        HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                        0,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            if (
                allMatches.isEmpty()
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "No matching contact",
                        PhoneContract.ERROR_CONTACT_NOT_FOUND,
                        0,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            val result =
                allMatches
                    .take(
                        effectiveLimit
                    )
                    .map {
                            entry ->

                        /*
                         * Search returns candidate identity only.
                         * Number IDs are expanded by getContact(contactId).
                         */
                        PhoneContact(
                            entry.id.toString(),
                            entry.displayName,
                            emptyList()
                        )
                    }

            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Matching contacts",
                    HyperNovaContract.ERROR_NONE,
                    allMatches.size,
                    result,
                    null,
                    emptyList(),
                    null
                )
            )
        }
    }

    private fun handleGetContact(
        requestId: String,
        contactId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_GET_CONTACT

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val parsedContactId =
            parsePositiveId(
                contactId
            )

        if (
            parsedContactId == null
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Invalid contactId"
            )
            return
        }

        if (
            !hasPermission(
                Manifest.permission.READ_CONTACTS
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_UNAVAILABLE,
                    "READ_CONTACTS permission is unavailable",
                    HyperNovaContract.ERROR_PERMISSION_DENIED,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        serviceScope.launch {

            val details =
                repository.getContactDetails(
                    parsedContactId
                )

            if (
                details == null
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "Contact was not found",
                        PhoneContract.ERROR_CONTACT_NOT_FOUND,
                        0,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Contact details",
                    HyperNovaContract.ERROR_NONE,
                    1,
                    emptyList(),
                    PhoneContact(
                        details.contactId
                            .toString(),

                        details.displayName,

                        details.numbers
                            .map {
                                    number ->

                                PhoneContactNumber(
                                    number.numberId
                                        .toString(),

                                    number.label,

                                    number.displayNumber,

                                    number.primary
                                )
                            }
                    ),
                    emptyList(),
                    null
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // Call history
    // ---------------------------------------------------------------------

    private fun handleGetCallHistory(
        requestId: String,
        filter: Int,
        limit: Int,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_GET_CALL_HISTORY

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        if (
            !isValidHistoryFilter(
                filter
            )
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Invalid call-history filter"
            )
            return
        }

        if (
            !hasPermission(
                Manifest.permission.READ_CALL_LOG
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_UNAVAILABLE,
                    "READ_CALL_LOG permission is unavailable",
                    HyperNovaContract.ERROR_PERMISSION_DENIED,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        val effectiveLimit =
            normalizeHistoryLimit(
                limit
            )

        serviceScope.launch {

            val snapshot =
                repository.getCallHistorySnapshot()

            if (
                !handleHistoryLoadFailure(
                    requestId,
                    operation,
                    snapshot.status,
                    callback
                )
            ) {
                return@launch
            }

            val matches =
                snapshot.entries
                    .filter {
                            entry ->

                        entry.historyType !=
                            CallHistoryType.OTHER &&
                            historyMatchesFilter(
                                entry.historyType,
                                filter
                            )
                    }

            val mapped =
                matches
                    .take(
                        effectiveLimit
                    )
                    .mapNotNull {
                            entry ->

                        mapHistoryEntry(
                            entry = entry,
                            explicitContactId = null,
                            fallbackDisplayName = null
                        )
                    }

            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Call history",
                    HyperNovaContract.ERROR_NONE,
                    matches.size,
                    emptyList(),
                    null,
                    mapped,
                    null
                )
            )
        }
    }

    private fun handleGetCallHistoryForContact(
        requestId: String,
        contactId: String,
        filter: Int,
        limit: Int,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_GET_CONTACT_CALL_HISTORY

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val parsedContactId =
            parsePositiveId(
                contactId
            )

        if (
            parsedContactId == null ||
            !isValidHistoryFilter(
                filter
            )
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Invalid contactId or history filter"
            )
            return
        }

        if (
            !hasPermission(
                Manifest.permission.READ_CONTACTS
            ) ||
            !hasPermission(
                Manifest.permission.READ_CALL_LOG
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_UNAVAILABLE,
                    "Contacts or call-history permission is unavailable",
                    HyperNovaContract.ERROR_PERMISSION_DENIED,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        val effectiveLimit =
            normalizeHistoryLimit(
                limit
            )

        serviceScope.launch {

            val contact =
                repository.getContactDetails(
                    parsedContactId
                )

            if (
                contact == null
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "Contact reference is stale",
                        PhoneContract.ERROR_STALE_CONTACT_REFERENCE,
                        0,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            val snapshot =
                repository.getCallHistorySnapshot()

            if (
                !handleHistoryLoadFailure(
                    requestId,
                    operation,
                    snapshot.status,
                    callback
                )
            ) {
                return@launch
            }

            val contactNumbers =
                contact.numbers
                    .map {
                        it.displayNumber
                    }

            val matches =
                snapshot.entries
                    .filter {
                            entry ->

                        entry.historyType !=
                            CallHistoryType.OTHER &&
                            historyMatchesFilter(
                                entry.historyType,
                                filter
                            ) &&
                            entry.number
                                ?.let {
                                        historyNumber ->

                                    contactNumbers
                                        .any {
                                                contactNumber ->

                                            samePhoneNumber(
                                                historyNumber,
                                                contactNumber
                                            )
                                        }
                                } ==
                                true
                    }

            val mapped =
                matches
                    .take(
                        effectiveLimit
                    )
                    .mapNotNull {
                            entry ->

                        mapHistoryEntry(
                            entry = entry,
                            explicitContactId =
                                parsedContactId
                                    .toString(),

                            fallbackDisplayName =
                                contact.displayName
                        )
                    }

            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    "Contact call history",
                    HyperNovaContract.ERROR_NONE,
                    matches.size,
                    emptyList(),
                    null,
                    mapped,
                    null
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // Outgoing call commands
    // ---------------------------------------------------------------------

    private fun handleCallContact(
        requestId: String,
        contactId: String,
        numberId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_CALL_CONTACT

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val parsedContactId =
            parsePositiveId(
                contactId
            )

        if (
            parsedContactId == null
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Invalid contactId"
            )
            return
        }

        if (
            !requireConnectedPhone(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        serviceScope.launch {

            val contact =
                repository.getContactDetails(
                    parsedContactId
                )

            if (
                contact == null
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "Contact reference is stale",
                        PhoneContract.ERROR_STALE_CONTACT_REFERENCE,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            val selectedNumber =
                if (
                    numberId.isBlank()
                ) {
                    if (
                        contact.numbers.size ==
                        1
                    ) {
                        contact.numbers.first()
                    } else {
                        finishRequest(
                            callback,
                            PhoneResult(
                                requestId,
                                operation,
                                HyperNovaContract.STATUS_REJECTED,
                                "Contact has multiple phone numbers",
                                PhoneContract.ERROR_MULTIPLE_NUMBERS,
                                contact.numbers.size,
                                emptyList(),
                                null,
                                emptyList(),
                                null
                            )
                        )
                        return@launch
                    }

                } else {
                    val parsedNumberId =
                        parsePositiveId(
                            numberId
                        )

                    if (
                        parsedNumberId == null
                    ) {
                        rejectInvalidArgument(
                            requestId,
                            operation,
                            callback,
                            "Invalid numberId"
                        )
                        return@launch
                    }

                    contact.numbers
                        .firstOrNull {
                            it.numberId ==
                                parsedNumberId
                        }
                        ?: run {
                            finishRequest(
                                callback,
                                PhoneResult(
                                    requestId,
                                    operation,
                                    HyperNovaContract.STATUS_REJECTED,
                                    "Phone number was not found for contact",
                                    PhoneContract.ERROR_NUMBER_NOT_FOUND,
                                    -1,
                                    emptyList(),
                                    null,
                                    emptyList(),
                                    null
                                )
                            )
                            return@launch
                        }
                }

            placeCallAndConfirm(
                requestId =
                    requestId,

                operation =
                    operation,

                phoneNumber =
                    selectedNumber.displayNumber,

                callback =
                    callback
            )
        }
    }

    private fun handleCallNumber(
        requestId: String,
        phoneNumber: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_CALL_NUMBER

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val cleanNumber =
            validatePhoneNumber(
                phoneNumber
            )

        if (
            cleanNumber == null
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Invalid phone number",
                    PhoneContract.ERROR_INVALID_PHONE_NUMBER,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        if (
            !requireConnectedPhone(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        serviceScope.launch {

            placeCallAndConfirm(
                requestId =
                    requestId,

                operation =
                    operation,

                phoneNumber =
                    cleanNumber,

                callback =
                    callback
            )
        }
    }

    private fun handleCallHistoryEntry(
        requestId: String,
        callId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_CALL_HISTORY_ENTRY

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val parsedCallId =
            parsePositiveId(
                callId
            )

        if (
            parsedCallId == null
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Invalid callId"
            )
            return
        }

        if (
            !requireConnectedPhone(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        serviceScope.launch {

            val snapshot =
                repository.getCallHistorySnapshot()

            if (
                !handleHistoryLoadFailure(
                    requestId,
                    operation,
                    snapshot.status,
                    callback
                )
            ) {
                return@launch
            }

            val entry =
                snapshot.entries
                    .firstOrNull {
                        it.id ==
                            parsedCallId
                    }

            if (
                entry == null
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "Call-history reference is stale",
                        PhoneContract.ERROR_STALE_CALL_REFERENCE,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            if (
                entry.presentation !=
                    CallNumberPresentation.ALLOWED ||
                entry.number.isNullOrBlank()
            ) {
                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        "This call-history entry cannot be dialed",
                        PhoneContract.ERROR_CALL_NOT_ALLOWED,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )
                return@launch
            }

            placeCallAndConfirm(
                requestId =
                    requestId,

                operation =
                    operation,

                phoneNumber =
                    entry.number,

                callback =
                    callback
            )
        }
    }

    // ---------------------------------------------------------------------
    // Incoming/active call controls
    // ---------------------------------------------------------------------

    private fun handleAnswerCall(
        requestId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_ANSWER_CALL

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        if (
            repository.state.value
                .call.status !=
            CallStatus.INCOMING
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "There is no incoming call to answer",
                    PhoneContract.ERROR_NO_INCOMING_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        val dispatch =
            telecom.answer()

        if (
            !handleCommandDispatch(
                requestId,
                operation,
                dispatch,
                callback,
                PhoneContract.ERROR_ANSWER_FAILED
            )
        ) {
            return
        }

        serviceScope.launch {

            val confirmed =
                awaitCallState(
                    COMMAND_CONFIRM_TIMEOUT_MILLIS
                ) {
                        call ->

                    call.status ==
                        CallStatus.ACTIVE
                }

            finishConfirmedOrTimeout(
                requestId,
                operation,
                callback,
                confirmed,
                "Call answered"
            )
        }
    }

    private fun handleDeclineCall(
        requestId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_DECLINE_CALL

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        if (
            repository.state.value
                .call.status !=
            CallStatus.INCOMING
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "There is no incoming call to decline",
                    PhoneContract.ERROR_NO_INCOMING_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        val dispatch =
            telecom.decline()

        if (
            !handleCommandDispatch(
                requestId,
                operation,
                dispatch,
                callback,
                PhoneContract.ERROR_DECLINE_FAILED
            )
        ) {
            return
        }

        serviceScope.launch {

            val confirmed =
                awaitCallState(
                    COMMAND_CONFIRM_TIMEOUT_MILLIS
                ) {
                        call ->

                    call.status ==
                        CallStatus.IDLE ||
                        call.status ==
                            CallStatus.CALL_ENDED
                }

            finishConfirmedOrTimeout(
                requestId,
                operation,
                callback,
                confirmed,
                "Incoming call declined"
            )
        }
    }

    private fun handleEndCall(
        requestId: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_END_CALL

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val current =
            repository.state.value
                .call.status

        if (
            current ==
                CallStatus.IDLE ||
            current ==
                CallStatus.CALL_ENDED
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "There is no active call to end",
                    PhoneContract.ERROR_NO_ACTIVE_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        val dispatch =
            telecom.disconnect()

        if (
            !handleCommandDispatch(
                requestId,
                operation,
                dispatch,
                callback,
                PhoneContract.ERROR_END_FAILED
            )
        ) {
            return
        }

        serviceScope.launch {

            val confirmed =
                awaitCallState(
                    COMMAND_CONFIRM_TIMEOUT_MILLIS
                ) {
                        call ->

                    call.status ==
                        CallStatus.IDLE ||
                        call.status ==
                            CallStatus.CALL_ENDED
                }

            finishConfirmedOrTimeout(
                requestId,
                operation,
                callback,
                confirmed,
                "Call ended"
            )
        }
    }

    private fun handleSetMuted(
        requestId: String,
        muted: Boolean,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_SET_MUTED

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val call =
            repository.state.value.call

        if (
            call.status !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD,
                CallStatus.MUTED
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Mute requires an active call",
                    PhoneContract.ERROR_NO_ACTIVE_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        if (
            call.isMuted ==
            muted
        ) {
            finishRequest(
                callback,
                confirmedStateResult(
                    requestId,
                    operation,
                    if (muted) {
                        "Call is muted"
                    } else {
                        "Call is unmuted"
                    }
                )
            )
            return
        }

        if (
            !audioController.setMuted(
                muted
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Mute control is unavailable",
                    PhoneContract.ERROR_MUTE_UNAVAILABLE,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        sendAccepted(
            callback,
            requestId,
            operation,
            "Mute request accepted"
        )

        serviceScope.launch {

            val confirmed =
                awaitCallState(
                    AUDIO_CONFIRM_TIMEOUT_MILLIS
                ) {
                        state ->

                    state.isMuted ==
                        muted
                }

            finishConfirmedOrTimeout(
                requestId,
                operation,
                callback,
                confirmed,
                if (muted) {
                    "Call muted"
                } else {
                    "Call unmuted"
                }
            )
        }
    }

    private fun handleSetHeld(
        requestId: String,
        held: Boolean,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_SET_HELD

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val current =
            repository.state.value
                .call.status

        if (
            held &&
            current ==
                CallStatus.HELD
        ) {
            finishRequest(
                callback,
                confirmedStateResult(
                    requestId,
                    operation,
                    "Call is held"
                )
            )
            return
        }

        if (
            !held &&
            current in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.MUTED
            )
        ) {
            finishRequest(
                callback,
                confirmedStateResult(
                    requestId,
                    operation,
                    "Call is resumed"
                )
            )
            return
        }

        if (
            current !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.MUTED,
                CallStatus.HELD
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Hold requires an active call",
                    PhoneContract.ERROR_NO_ACTIVE_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        val dispatch =
            if (
                held
            ) {
                telecom.hold()
            } else {
                telecom.unhold()
            }

        if (
            !handleCommandDispatch(
                requestId,
                operation,
                dispatch,
                callback,
                PhoneContract.ERROR_HOLD_UNAVAILABLE
            )
        ) {
            return
        }

        serviceScope.launch {

            val confirmed =
                awaitCallState(
                    COMMAND_CONFIRM_TIMEOUT_MILLIS
                ) {
                        state ->

                    if (
                        held
                    ) {
                        state.status ==
                            CallStatus.HELD
                    } else {
                        state.status ==
                            CallStatus.ACTIVE ||
                            state.status ==
                                CallStatus.MUTED
                    }
                }

            finishConfirmedOrTimeout(
                requestId,
                operation,
                callback,
                confirmed,
                if (held) {
                    "Call held"
                } else {
                    "Call resumed"
                }
            )
        }
    }

    private fun handleSetAudioRoute(
        requestId: String,
        route: Int,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_SET_AUDIO_ROUTE

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val telecomRoute =
            contractRouteToTelecom(
                route
            )

        if (
            telecomRoute == null
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "Unsupported audio route"
            )
            return
        }

        val callStatus =
            repository.state.value
                .call.status

        if (
            callStatus !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD,
                CallStatus.MUTED
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Audio route requires an active call",
                    PhoneContract.ERROR_NO_ACTIVE_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        if (
            currentTelecomAudioRoute() ==
            telecomRoute
        ) {
            finishRequest(
                callback,
                confirmedStateResult(
                    requestId,
                    operation,
                    "Audio route already selected"
                )
            )
            return
        }

        val dispatch =
            telecom.selectAudioRoute(
                telecomRoute
            )

        if (
            !handleCommandDispatch(
                requestId,
                operation,
                dispatch,
                callback,
                PhoneContract.ERROR_AUDIO_ROUTE_UNAVAILABLE
            )
        ) {
            return
        }

        serviceScope.launch {

            val confirmed =
                awaitAudioRoute(
                    telecomRoute
                )

            if (
                confirmed
            ) {
                finishRequest(
                    callback,
                    confirmedStateResult(
                        requestId,
                        operation,
                        "Audio route confirmed"
                    )
                )

            } else {
                finishTimeout(
                    requestId,
                    operation,
                    callback,
                    "Audio route was not confirmed"
                )
            }
        }
    }

    private fun handleSendDtmf(
        requestId: String,
        digit: String,
        callback: IPhoneCommandCallback
    ) {
        val operation =
            PhoneContract.OP_SEND_DTMF

        if (
            !beginRequest(
                requestId,
                operation,
                callback
            )
        ) {
            return
        }

        val tone =
            digit.singleOrNull()

        if (
            tone == null ||
            tone !in
            VALID_DTMF_DIGITS
        ) {
            rejectInvalidArgument(
                requestId,
                operation,
                callback,
                "DTMF must be one of 0-9, * or #"
            )
            return
        }

        val current =
            repository.state.value
                .call.status

        if (
            current !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD,
                CallStatus.MUTED
            )
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "DTMF requires an active call",
                    PhoneContract.ERROR_NO_ACTIVE_CALL,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    currentContractState()
                )
            )
            return
        }

        when (
            val dispatch =
                telecom.sendDtmf(
                    tone.toString()
                )
        ) {
            TelecomCallController
                .CommandResult
                .Dispatched -> {

                sendAccepted(
                    callback,
                    requestId,
                    operation,
                    "DTMF accepted"
                )

                /*
                 * Contract semantics:
                 * CONFIRMED means the DTMF command was sent to Telecom.
                 * It does not claim remote IVR acknowledgement.
                 */
                finishRequest(
                    callback,
                    confirmedStateResult(
                        requestId,
                        operation,
                        "DTMF sent to Telecom"
                    )
                )
            }

            is TelecomCallController
                .CommandResult
                .Rejected -> {

                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        dispatch.reason,
                        PhoneContract.ERROR_DTMF_UNAVAILABLE,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        currentContractState()
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Command helpers
    // ---------------------------------------------------------------------

    private suspend fun placeCallAndConfirm(
        requestId: String,
        operation: String,
        phoneNumber: String,
        callback: IPhoneCommandCallback
    ) {
        val cleanNumber =
            validatePhoneNumber(
                phoneNumber
            )

        if (
            cleanNumber == null
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "Invalid phone number",
                    PhoneContract.ERROR_INVALID_PHONE_NUMBER,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return
        }

        when (
            val dispatch =
                telecom.placeCall(
                    cleanNumber
                )
        ) {
            TelecomCallController
                .CommandResult
                .Dispatched -> {

                sendAccepted(
                    callback,
                    requestId,
                    operation,
                    "Call request accepted"
                )
            }

            is TelecomCallController
                .CommandResult
                .Rejected -> {

                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        dispatch.reason,
                        PhoneContract.ERROR_CALL_FAILED,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        currentContractState()
                    )
                )
                return
            }
        }

        val confirmed =
            awaitCallState(
                OUTGOING_CALL_CONFIRM_TIMEOUT_MILLIS
            ) {
                    state ->

                state.status in
                    setOf(
                        CallStatus.DIALING,
                        CallStatus.RINGING,
                        CallStatus.ACTIVE,
                        CallStatus.HELD
                    )
            }

        finishConfirmedOrTimeout(
            requestId,
            operation,
            callback,
            confirmed,
            "Outgoing call confirmed by Telecom"
        )
    }

    /**
     * Returns true only when the command was dispatched.
     *
     * Successful dispatch publishes STATUS_ACCEPTED immediately.
     */
    private fun handleCommandDispatch(
        requestId: String,
        operation: String,
        dispatch:
            TelecomCallController.CommandResult,
        callback: IPhoneCommandCallback,
        rejectedErrorCode: String
    ): Boolean =
        when (
            dispatch
        ) {
            TelecomCallController
                .CommandResult
                .Dispatched -> {

                sendAccepted(
                    callback,
                    requestId,
                    operation,
                    "Phone command accepted"
                )

                true
            }

            is TelecomCallController
                .CommandResult
                .Rejected -> {

                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_REJECTED,
                        dispatch.reason,
                        rejectedErrorCode,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        currentContractState()
                    )
                )

                false
            }
        }

    private fun requireConnectedPhone(
        requestId: String,
        operation: String,
        callback: IPhoneCommandCallback
    ): Boolean {

        val bluetooth =
            repository.state.value
                .bluetooth

        if (
            bluetooth.state ==
            BluetoothConnectionState.CONNECTED
        ) {
            return true
        }

        finishRequest(
            callback,
            PhoneResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_UNAVAILABLE,
                "No HFP phone is connected",
                PhoneContract.ERROR_NO_PHONE_CONNECTED,
                -1,
                emptyList(),
                null,
                emptyList(),
                currentContractState()
            )
        )

        return false
    }

    // ---------------------------------------------------------------------
    // Request dedup
    // ---------------------------------------------------------------------

    /**
     * Every asynchronous requestId is deduplicated for the shared TTL.
     *
     * Same requestId:
     * - never executes the command twice,
     * - replays the last ACCEPTED/terminal result,
     * - or reports that the request is still in progress.
     */
    private fun beginRequest(
        requestId: String,
        operation: String,
        callback: IPhoneCommandCallback
    ): Boolean {

        if (
            requestId.isBlank()
        ) {
            sendResult(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "requestId must not be blank",
                    HyperNovaContract.ERROR_INVALID_ARGUMENT,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return false
        }

        var replay:
            PhoneResult? = null

        var operationConflict =
            false

        var inFlight =
            false

        synchronized(
            requestLock
        ) {
            cleanupRequestCacheLocked()

            val existing =
                requestCache[
                    requestId
                ]

            if (
                existing != null
            ) {
                if (
                    existing.operation !=
                    operation
                ) {
                    operationConflict =
                        true
                } else {
                    replay =
                        existing.result

                    inFlight =
                        existing.result ==
                            null
                }
            } else {
                requestCache[
                    requestId
                ] =
                    CachedRequest(
                        operation =
                            operation,

                        result =
                            null,

                        updatedAtMillis =
                            System.currentTimeMillis()
                    )
            }
        }

        if (
            operationConflict
        ) {
            sendResult(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_REJECTED,
                    "requestId was already used for another operation",
                    HyperNovaContract.ERROR_INVALID_ARGUMENT,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return false
        }

        replay?.let {
            sendResult(
                callback,
                it
            )
            return false
        }

        if (
            inFlight
        ) {
            sendResult(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_ACCEPTED,
                    "Request is already in progress",
                    HyperNovaContract.ERROR_NONE,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    null
                )
            )
            return false
        }

        return true
    }

    private fun sendAccepted(
        callback: IPhoneCommandCallback,
        requestId: String,
        operation: String,
        message: String
    ) {
        val result =
            PhoneResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_ACCEPTED,
                message,
                HyperNovaContract.ERROR_NONE,
                -1,
                emptyList(),
                null,
                emptyList(),
                currentContractState()
            )

        cacheRequestResult(
            result
        )

        sendResult(
            callback,
            result
        )
    }

    private fun finishRequest(
        callback: IPhoneCommandCallback,
        result: PhoneResult
    ) {
        cacheRequestResult(
            result
        )

        sendResult(
            callback,
            result
        )
    }

    private fun cacheRequestResult(
        result: PhoneResult
    ) {
        synchronized(
            requestLock
        ) {
            requestCache[
                result.requestId
            ] =
                CachedRequest(
                    operation =
                        result.operation,

                    result =
                        result,

                    updatedAtMillis =
                        System.currentTimeMillis()
                )

            cleanupRequestCacheLocked()
        }
    }

    private fun cleanupRequestCacheLocked() {
        val cutoff =
            System.currentTimeMillis() -
                HyperNovaContract
                    .REQUEST_DEDUP_TTL_MILLIS

        val iterator =
            requestCache.entries
                .iterator()

        while (
            iterator.hasNext()
        ) {
            val entry =
                iterator.next()

            if (
                entry.value
                    .updatedAtMillis <
                cutoff
            ) {
                iterator.remove()
            }
        }
    }

    // ---------------------------------------------------------------------
    // State confirmation
    // ---------------------------------------------------------------------

    private suspend fun awaitCallState(
        timeoutMillis: Long,
        predicate:
            (com.hypernova.phone.domain.TelecomCallState) ->
                Boolean
    ): PhoneDataState? =
        withTimeoutOrNull(
            timeoutMillis
        ) {
            repository.state
                .first {
                        state ->

                    predicate(
                        state.call
                    )
                }
        }

    private suspend fun awaitAudioRoute(
        telecomRoute: Int
    ): Boolean {
        val confirmed =
            withTimeoutOrNull(
                AUDIO_CONFIRM_TIMEOUT_MILLIS
            ) {
                while (
                    currentTelecomAudioRoute() !=
                    telecomRoute
                ) {
                    delay(
                        AUDIO_ROUTE_POLL_MILLIS
                    )
                }

                true
            }

        return confirmed
            ?: false
    }

    private fun finishConfirmedOrTimeout(
        requestId: String,
        operation: String,
        callback: IPhoneCommandCallback,
        confirmed: PhoneDataState?,
        confirmedMessage: String
    ) {
        if (
            confirmed != null
        ) {
            finishRequest(
                callback,
                PhoneResult(
                    requestId,
                    operation,
                    HyperNovaContract.STATUS_CONFIRMED,
                    confirmedMessage,
                    HyperNovaContract.ERROR_NONE,
                    -1,
                    emptyList(),
                    null,
                    emptyList(),
                    confirmed.toContractState()
                )
            )

        } else {
            finishTimeout(
                requestId,
                operation,
                callback,
                "Android did not confirm the requested state in time"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Call-history mapping
    // ---------------------------------------------------------------------

    private suspend fun mapHistoryEntry(
        entry: RecentCallEntry,
        explicitContactId: String?,
        fallbackDisplayName: String?
    ): PhoneCallHistoryEntry? {

        val callType =
            when (
                entry.historyType
            ) {
                CallHistoryType.INCOMING ->
                    PhoneContract.CALL_TYPE_INCOMING

                CallHistoryType.OUTGOING ->
                    PhoneContract.CALL_TYPE_OUTGOING

                CallHistoryType.MISSED ->
                    PhoneContract.CALL_TYPE_MISSED

                CallHistoryType.REJECTED ->
                    PhoneContract.CALL_TYPE_REJECTED

                CallHistoryType.OTHER ->
                    return null
            }

        val contactId =
            explicitContactId
                ?: entry.number
                    ?.let {
                            number ->

                        repository
                            .findContactIdByNumber(
                                number
                            )
                            ?.toString()
                    }

        val presentation =
            entry.presentation
                .toContractPresentation()

        val publicNumber =
            if (
                entry.presentation ==
                CallNumberPresentation.ALLOWED
            ) {
                entry.number
            } else {
                null
            }

        return PhoneCallHistoryEntry(
            entry.id.toString(),
            contactId,
            entry.displayName
                ?: fallbackDisplayName,
            publicNumber,
            presentation,
            callType,
            entry.timestamp,
            entry.durationSeconds
        )
    }

    private fun historyMatchesFilter(
        type: CallHistoryType,
        filter: Int
    ): Boolean =
        when (
            filter
        ) {
            PhoneContract.HISTORY_FILTER_ALL ->
                true

            PhoneContract.HISTORY_FILTER_INCOMING ->
                type ==
                    CallHistoryType.INCOMING

            PhoneContract.HISTORY_FILTER_OUTGOING ->
                type ==
                    CallHistoryType.OUTGOING

            PhoneContract.HISTORY_FILTER_MISSED ->
                type ==
                    CallHistoryType.MISSED

            PhoneContract.HISTORY_FILTER_REJECTED ->
                type ==
                    CallHistoryType.REJECTED

            else ->
                false
        }

    private fun isValidHistoryFilter(
        filter: Int
    ): Boolean =
        filter in
            setOf(
                PhoneContract.HISTORY_FILTER_ALL,
                PhoneContract.HISTORY_FILTER_INCOMING,
                PhoneContract.HISTORY_FILTER_OUTGOING,
                PhoneContract.HISTORY_FILTER_MISSED,
                PhoneContract.HISTORY_FILTER_REJECTED
            )

    private fun handleHistoryLoadFailure(
        requestId: String,
        operation: String,
        status: RecentsStatus,
        callback: IPhoneCommandCallback
    ): Boolean =
        when (
            status
        ) {
            RecentsStatus
                .RECENTS_PERMISSION_REQUIRED -> {

                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "READ_CALL_LOG permission is unavailable",
                        HyperNovaContract.ERROR_PERMISSION_DENIED,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )

                false
            }

            RecentsStatus
                .RECENTS_ERROR -> {

                finishRequest(
                    callback,
                    PhoneResult(
                        requestId,
                        operation,
                        HyperNovaContract.STATUS_UNAVAILABLE,
                        "Android CallLog is unavailable",
                        HyperNovaContract.ERROR_SERVICE_UNAVAILABLE,
                        -1,
                        emptyList(),
                        null,
                        emptyList(),
                        null
                    )
                )

                false
            }

            RecentsStatus.RECENTS_LOADING -> {
                finishTimeout(
                    requestId,
                    operation,
                    callback,
                    "Call history is still loading"
                )

                false
            }

            RecentsStatus.RECENTS_READY,
            RecentsStatus.RECENTS_EMPTY ->
                true
        }

    // ---------------------------------------------------------------------
    // Contract state mapping
    // ---------------------------------------------------------------------

    private fun currentContractState():
        PhoneState =
        (
            latestState
                ?: repository.state.value
            )
            .toContractState()

    private fun PhoneDataState.toContractState():
        PhoneState {

        val now =
            System.currentTimeMillis()

        val startedAt =
            call.startedAtMillis
                ?: 0L

        val durationSeconds =
            if (
                startedAt > 0L &&
                call.status in
                setOf(
                    CallStatus.ACTIVE,
                    CallStatus.MUTED,
                    CallStatus.HELD
                )
            ) {
                max(
                    0L,
                    now -
                        startedAt
                ) /
                    1_000L

            } else {
                0L
            }

        val activeCall =
            call.status in
                setOf(
                    CallStatus.ACTIVE,
                    CallStatus.MUTED,
                    CallStatus.HELD
                )

        val incomingCall =
            call.status ==
                CallStatus.INCOMING

        return PhoneState(
            availability
                .toContractAvailability(),

            bluetooth
                .connectedDeviceName,

            bluetooth.state ==
                BluetoothConnectionState.CONNECTED,

            call.status
                .toContractCallState(),

            /*
             * The current Telecom state has no stable contact ID.
             * Do not invent one.
             */
            null,

            call.displayName,

            call.number,

            startedAt,

            durationSeconds,

            call.isMuted,

            call.status ==
                CallStatus.HELD,

            currentContractAudioRoute(),

            call.canAnswer,

            incomingCall,

            call.canDisconnect,

            call.canHold,

            activeCall,

            activeCall,

            now
        )
    }

    private fun PhoneAvailability
        .toContractAvailability():
        Int =
        when (this) {

            PhoneAvailability.NO_PHONE ->
                PhoneContract
                    .AVAILABILITY_DISCONNECTED

            PhoneAvailability.CONNECTING,
            PhoneAvailability.CONTACTS_SYNCING ->
                PhoneContract
                    .AVAILABILITY_CONNECTING

            PhoneAvailability.READY ->
                PhoneContract
                    .AVAILABILITY_READY

            PhoneAvailability.ERROR ->
                PhoneContract
                    .AVAILABILITY_UNAVAILABLE
        }

    private fun CallStatus.toContractCallState():
        Int =
        when (this) {

            CallStatus.IDLE ->
                PhoneContract.CALL_STATE_IDLE

            CallStatus.DIALING,
            CallStatus.RINGING ->
                PhoneContract.CALL_STATE_DIALING

            CallStatus.INCOMING ->
                PhoneContract.CALL_STATE_INCOMING

            CallStatus.ACTIVE,
            CallStatus.MUTED ->
                PhoneContract.CALL_STATE_ACTIVE

            CallStatus.HELD ->
                PhoneContract.CALL_STATE_HELD

            CallStatus.CALL_ENDED,
            CallStatus.MISSED,
            CallStatus.REJECTED ->
                PhoneContract.CALL_STATE_ENDED

            CallStatus.FAILED ->
                PhoneContract.CALL_STATE_FAILED
        }

    private fun CallNumberPresentation
        .toContractPresentation():
        Int =
        when (this) {

            CallNumberPresentation.ALLOWED ->
                PhoneContract
                    .NUMBER_PRESENTATION_ALLOWED

            CallNumberPresentation.PRIVATE,
            CallNumberPresentation.RESTRICTED ->
                PhoneContract
                    .NUMBER_PRESENTATION_RESTRICTED

            CallNumberPresentation.UNKNOWN ->
                PhoneContract
                    .NUMBER_PRESENTATION_UNKNOWN

            CallNumberPresentation.PAYPHONE ->
                PhoneContract
                    .NUMBER_PRESENTATION_PAYPHONE

            CallNumberPresentation.UNAVAILABLE ->
                PhoneContract
                    .NUMBER_PRESENTATION_UNAVAILABLE
        }

    private fun currentContractAudioRoute():
        Int =
        when (
            currentTelecomAudioRoute()
        ) {
            CallAudioState.ROUTE_SPEAKER ->
                PhoneContract.AUDIO_ROUTE_VEHICLE

            CallAudioState.ROUTE_EARPIECE ->
                PhoneContract.AUDIO_ROUTE_PHONE

            CallAudioState.ROUTE_BLUETOOTH ->
                PhoneContract.AUDIO_ROUTE_BLUETOOTH

            else ->
                PhoneContract.AUDIO_ROUTE_UNKNOWN
        }

    private fun currentTelecomAudioRoute():
        Int? =
        try {
            HyperNovaInCallService
                .currentService
                ?.callAudioState
                ?.route
        } catch (
            exception: RuntimeException
        ) {
            null
        }

    private fun contractRouteToTelecom(
        route: Int
    ): Int? =
        when (
            route
        ) {
            PhoneContract.AUDIO_ROUTE_VEHICLE ->
                CallAudioState.ROUTE_SPEAKER

            PhoneContract.AUDIO_ROUTE_PHONE ->
                CallAudioState.ROUTE_EARPIECE

            PhoneContract.AUDIO_ROUTE_BLUETOOTH ->
                CallAudioState.ROUTE_BLUETOOTH

            else ->
                null
        }

    // ---------------------------------------------------------------------
    // Generic helpers
    // ---------------------------------------------------------------------

    private suspend fun awaitContactsState():
        PhoneDataState? {

        val current =
            repository.state.value

        if (
            current.contactsStatus ==
                ContactsStatus.CONTACTS_READY ||
            current.contactsStatus ==
                ContactsStatus.CONTACTS_EMPTY
        ) {
            return current
        }

        return withTimeoutOrNull(
            CONTACTS_LOAD_TIMEOUT_MILLIS
        ) {
            repository.state.first {
                    state ->

                state.contactsStatus ==
                    ContactsStatus.CONTACTS_READY ||
                    state.contactsStatus ==
                        ContactsStatus.CONTACTS_EMPTY ||
                    state.contactsStatus ==
                        ContactsStatus.CONTACTS_SYNC_FAILED ||
                    (
                        state.contactsStatus ==
                            ContactsStatus.PERMISSION_REQUIRED &&
                            !hasPermission(
                                Manifest.permission.READ_CONTACTS
                            )
                        )
            }
        }
    }

    private fun confirmedStateResult(
        requestId: String,
        operation: String,
        message: String
    ): PhoneResult =
        PhoneResult(
            requestId,
            operation,
            HyperNovaContract.STATUS_CONFIRMED,
            message,
            HyperNovaContract.ERROR_NONE,
            -1,
            emptyList(),
            null,
            emptyList(),
            currentContractState()
        )

    private fun finishTimeout(
        requestId: String,
        operation: String,
        callback: IPhoneCommandCallback,
        message: String
    ) {
        finishRequest(
            callback,
            PhoneResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_TIMEOUT,
                message,
                HyperNovaContract.ERROR_TIMEOUT,
                -1,
                emptyList(),
                null,
                emptyList(),
                currentContractState()
            )
        )
    }

    private fun rejectInvalidArgument(
        requestId: String,
        operation: String,
        callback: IPhoneCommandCallback,
        message: String
    ) {
        finishRequest(
            callback,
            PhoneResult(
                requestId,
                operation,
                HyperNovaContract.STATUS_REJECTED,
                message,
                HyperNovaContract.ERROR_INVALID_ARGUMENT,
                -1,
                emptyList(),
                null,
                emptyList(),
                null
            )
        )
    }

    private fun normalizeContactLimit(
        limit: Int
    ): Int =
        when {
            limit <= 0 ->
                PhoneContract
                    .DEFAULT_CONTACT_RESULT_LIMIT

            limit >
                PhoneContract
                    .MAX_CONTACT_RESULT_LIMIT ->
                PhoneContract
                    .MAX_CONTACT_RESULT_LIMIT

            else ->
                limit
        }

    private fun normalizeHistoryLimit(
        limit: Int
    ): Int =
        when {
            limit <= 0 ->
                PhoneContract
                    .DEFAULT_CALL_HISTORY_LIMIT

            limit >
                PhoneContract
                    .MAX_CALL_HISTORY_LIMIT ->
                PhoneContract
                    .MAX_CALL_HISTORY_LIMIT

            else ->
                limit
        }

    private fun parsePositiveId(
        value: String
    ): Long? =
        value.trim()
            .toLongOrNull()
            ?.takeIf {
                it > 0L
            }

    private fun validatePhoneNumber(
        value: String
    ): String? {

        val trimmed =
            value.trim()

        if (
            trimmed.isEmpty()
        ) {
            return null
        }

        val normalized =
            PhoneNumberUtils
                .normalizeNumber(
                    trimmed
                )

        val digitCount =
            normalized.count {
                it.isDigit()
            }

        return trimmed
            .takeIf {
                digitCount >=
                    MIN_PHONE_DIGITS
            }
    }

    private fun samePhoneNumber(
        first: String,
        second: String
    ): Boolean =
        try {
            first ==
                second ||
                PhoneNumberUtils.compare(
                    first,
                    second
                )

        } catch (
            exception: RuntimeException
        ) {
            PhoneNumberUtils
                .normalizeNumber(
                    first
                ) ==
                PhoneNumberUtils
                    .normalizeNumber(
                        second
                    )
        }

    private fun hasPermission(
        permission: String
    ): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            permission
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun broadcastState(
        state: PhoneState
    ) {
        val count =
            statusCallbacks
                .beginBroadcast()

        try {
            for (
                index in
                0 until count
            ) {
                try {
                    statusCallbacks
                        .getBroadcastItem(
                            index
                        )
                        .onStateChanged(
                            state
                        )

                } catch (
                    exception: RemoteException
                ) {
                    Log.w(
                        TAG,
                        "Phone status callback failed",
                        exception
                    )
                }
            }

        } finally {
            statusCallbacks
                .finishBroadcast()
        }
    }

    private fun sendResult(
        callback: IPhoneCommandCallback,
        result: PhoneResult
    ) {
        try {
            callback.onResult(
                result
            )

        } catch (
            exception: RemoteException
        ) {
            Log.w(
                TAG,
                "Phone callback unavailable " +
                    "operation=${result.operation}",
                exception
            )
        }
    }

    private companion object {
        const val TAG =
            "HN-PhoneCommand"

        const val CONTACTS_LOAD_TIMEOUT_MILLIS =
            5_000L

        const val COMMAND_CONFIRM_TIMEOUT_MILLIS =
            8_000L

        const val OUTGOING_CALL_CONFIRM_TIMEOUT_MILLIS =
            10_000L

        const val AUDIO_CONFIRM_TIMEOUT_MILLIS =
            4_000L

        const val AUDIO_ROUTE_POLL_MILLIS =
            50L

        const val MIN_PHONE_DIGITS =
            3

        val VALID_DTMF_DIGITS =
            setOf(
                '0',
                '1',
                '2',
                '3',
                '4',
                '5',
                '6',
                '7',
                '8',
                '9',
                '*',
                '#'
            )
    }
}

