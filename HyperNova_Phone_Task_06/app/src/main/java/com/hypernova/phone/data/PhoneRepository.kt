package com.hypernova.phone.data

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import com.hypernova.phone.bluetooth.BluetoothPhoneClient
import com.hypernova.phone.contacts.CallHistoryRepository
import com.hypernova.phone.contacts.CallerIdentity
import com.hypernova.phone.contacts.ContactDetailsRecord
import com.hypernova.phone.contacts.PhoneNumberMatching
import com.hypernova.phone.contacts.RecentCallsLoadResult
import com.hypernova.phone.contacts.ContactsRepository
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.CapabilityStatus
import com.hypernova.phone.domain.ContactEntry
import com.hypernova.phone.domain.ContactsStatus
import com.hypernova.phone.domain.PhoneAvailability
import com.hypernova.phone.domain.PhoneCapabilities
import com.hypernova.phone.domain.PhoneDataState
import com.hypernova.phone.domain.RecentCallEntry
import com.hypernova.phone.domain.RecentsStatus
import com.hypernova.phone.domain.TelecomCallState
import com.hypernova.phone.telecom.TelecomCallController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import kotlinx.coroutines.delay

/**
 * Aggregates real Android framework data for the HyperNova Phone UI.
 *
 * Call identity policy:
 *
 * Android Telecom callerDisplayName
 *              ↓
 * meaningful real name?
 *      ┌───────┴────────┐
 *      │                │
 *     yes              no
 *      │                │
 * use Telecom      Contacts PhoneLookup
 *                       │
 *                 saved contact?
 *                  │          │
 *                 yes         no
 *                  │          │
 *                name       number
 *
 * No fake caller identity is generated.
 */
class PhoneRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val PROVIDER_REFRESH_DEBOUNCE_MILLIS =
        450L

    private val CONTACTS_PROVIDER_QUIET_WINDOW_MILLIS =
        5_000L


    private val bluetooth =
        BluetoothPhoneClient(context)

    private val telecom =
        TelecomCallController(context)

    private val contacts =
        ContactsRepository(context)

    private val history =
        CallHistoryRepository(context)

    private val contactsStatus =
        MutableStateFlow(
            ContactsStatus.PERMISSION_REQUIRED
        )

    private val recentsStatus =
        MutableStateFlow(
            RecentsStatus.RECENTS_PERMISSION_REQUIRED
        )

    private val contactsEntries =
        MutableStateFlow<List<ContactEntry>>(
            emptyList()
        )

    private val recentEntries =
        MutableStateFlow<List<RecentCallEntry>>(
            emptyList()
        )

    /**
     * Caller identity (real display name + real photo URI) resolved
     * specifically for the current real Telecom call.
     *
     * The TelecomCallController remains the owner of call state.
     * This flow adds presentation identity only.
     */
    private val resolvedCallIdentity =
        MutableStateFlow<CallerIdentity?>(null)

    /**
     * Remember the number already queried so repeated Telecom Details
     * callbacks do not repeatedly query the ContactsProvider.
     *
     * A null resolved name is still considered a completed lookup.
     */
    private var resolvedCallNumber:
        String? = null

    private var contactsJob:
        Job? = null

    private var recentsJob:
        Job? = null

    private val recentsGate =
        RecentsLoadGate()

    private var providerObserversRegistered =
        false

    private var contactsProviderRefreshJob:
        Job? = null

    private var callLogProviderRefreshJob:
        Job? = null

    @Volatile
    private var lastContactsProviderChangeElapsedMillis:
        Long = 0L

    private val contactsProviderObserver =
        object :
            ContentObserver(
                Handler(
                    Looper.getMainLooper()
                )
            ) {

            override fun onChange(
                selfChange: Boolean
            ) {
                super.onChange(
                    selfChange
                )

                lastContactsProviderChangeElapsedMillis =
                    SystemClock.elapsedRealtime()

                scheduleContactsProviderRefresh()
            }
        }

    private val callLogProviderObserver =
        object :
            ContentObserver(
                Handler(
                    Looper.getMainLooper()
                )
            ) {

            override fun onChange(
                selfChange: Boolean
            ) {
                super.onChange(
                    selfChange
                )

                scheduleCallLogProviderRefresh()
            }
        }

    /**
     * Call state presented to the UI.
     *
     * Telecom remains the authoritative source for:
     *
     * - call status
     * - number
     * - hold capability
     * - timer
     * - disconnect capability
     *
     * We only enrich displayName with a real Contacts lookup when
     * Telecom itself did not provide a meaningful name.
     */
    private val presentedCall =
        combine(
            telecom.state,
            resolvedCallIdentity
        ) { call, resolvedIdentity ->

            val telecomName =
                meaningfulTelecomDisplayName(
                    call
                )

            /*
             * Guard against cross-call leakage: only surface a resolved
             * identity when it was resolved for this call's number.
             */
            val callNumber =
                call.number
                    ?.let {
                        PhoneNumberMatching.normalize(
                            it
                        )
                    }

            val safeIdentity =
                if (
                    callNumber != null &&
                    resolvedCallNumber != null &&
                    PhoneNumberMatching.sameNumber(
                        callNumber,
                        resolvedCallNumber
                    )
                ) {
                    resolvedIdentity
                } else {
                    null
                }

            call.copy(
                displayName =
                    telecomName
                        ?: safeIdentity?.displayName,
                photoUri =
                    safeIdentity?.photoUri
            )
        }

    private data class StateInputs(
        val bluetooth:
            com.hypernova.phone.domain.BluetoothUiState,

        val call:
            TelecomCallState,

        val contactsStatus:
            ContactsStatus,

        val recentsStatus:
            RecentsStatus,

        val contacts:
            List<ContactEntry>
    )

    val state: StateFlow<PhoneDataState> =
        combine(
            bluetooth.state,
            presentedCall,
            contactsStatus,
            recentsStatus,
            contactsEntries
        ) {
                bluetoothState,
                callState,
                contactState,
                recentState,
                contactEntries ->

            StateInputs(
                bluetooth =
                    bluetoothState,

                call =
                    callState,

                contactsStatus =
                    contactState,

                recentsStatus =
                    recentState,

                contacts =
                    contactEntries
            )

        }.combine(
            recentEntries
        ) { inputs, recents ->

            val availability =
                when {

                    inputs.bluetooth.state ==
                        com.hypernova.phone.domain
                            .BluetoothConnectionState
                            .CONNECTING -> {

                        PhoneAvailability.CONNECTING
                    }

                    inputs.bluetooth.state ==
                        com.hypernova.phone.domain
                            .BluetoothConnectionState
                            .CONNECTION_FAILED -> {

                        PhoneAvailability.ERROR
                    }

                inputs.bluetooth.state ==
                    com.hypernova.phone.domain
                        .BluetoothConnectionState
                        .CONNECTED &&
                    inputs.contactsStatus ==
                        ContactsStatus.CONTACTS_SYNCING -> {

                    PhoneAvailability.CONTACTS_SYNCING
                }

                inputs.bluetooth.state ==
                    com.hypernova.phone.domain
                        .BluetoothConnectionState
                        .CONNECTED -> {

                    PhoneAvailability.READY
                }

                    else -> {
                        PhoneAvailability.NO_PHONE
                    }
                }

            PhoneDataState(
                availability =
                    availability,

                bluetooth =
                    inputs.bluetooth,

                capabilities =
                    capabilities(),

                contactsStatus =
                    inputs.contactsStatus,

                recentsStatus =
                    inputs.recentsStatus,

                contacts =
                    inputs.contacts,

                recents =
                    recents,

                call =
                    inputs.call
            )

        }.stateIn(
            scope,
            SharingStarted.WhileSubscribed(
                5_000
            ),
            initialData()
        )

    init {
        observeCurrentCallIdentity()
    }

    fun start() {
        bluetooth.start()

        registerProviderObservers()

        refreshCapabilities()

        /*
         * Read the current provider snapshot immediately.
         * PBAP provider notifications keep it live afterwards.
         */
        refreshContacts()
        refreshRecents()
    }

    fun stop() {
        unregisterProviderObservers()

        contactsProviderRefreshJob
            ?.cancel()

        callLogProviderRefreshJob
            ?.cancel()

        bluetooth.stop()
    }

    fun refreshCapabilities() {
        bluetooth.refresh()

        if (
            !has(
                Manifest.permission.READ_CONTACTS
            )
        ) {
            contactsStatus.value =
                ContactsStatus.PERMISSION_REQUIRED
        }

        if (
            !has(
                Manifest.permission.READ_CALL_LOG
            )
        ) {
            recentsStatus.value =
                RecentsStatus
                    .RECENTS_PERMISSION_REQUIRED
        }
    }

    private fun registerProviderObservers() {

        if (providerObserversRegistered) {
            return
        }

        try {
            context.contentResolver
                .registerContentObserver(
                    ContactsContract.AUTHORITY_URI,
                    true,
                    contactsProviderObserver
                )

            context.contentResolver
                .registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    true,
                    callLogProviderObserver
                )

            providerObserversRegistered =
                true

            Log.i(
                TAG,
                "Contacts/CallLog provider observers registered"
            )

        } catch (security: SecurityException) {

            try {
                context.contentResolver
                    .unregisterContentObserver(
                        contactsProviderObserver
                    )
            } catch (_: Exception) {
            }

            try {
                context.contentResolver
                    .unregisterContentObserver(
                        callLogProviderObserver
                    )
            } catch (_: Exception) {
            }

            Log.w(
                TAG,
                "Provider observer registration unavailable",
                security
            )
        }
    }

    private fun unregisterProviderObservers() {

        if (!providerObserversRegistered) {
            return
        }

        try {
            context.contentResolver
                .unregisterContentObserver(
                    contactsProviderObserver
                )

            context.contentResolver
                .unregisterContentObserver(
                    callLogProviderObserver
                )
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Provider observer cleanup failed",
                exception
            )
        } finally {
            providerObserversRegistered =
                false
        }
    }

    private fun scheduleContactsProviderRefresh() {

        contactsProviderRefreshJob
            ?.cancel()

        contactsProviderRefreshJob =
            scope.launch {

                delay(
                    PROVIDER_REFRESH_DEBOUNCE_MILLIS
                )

                history
                    .invalidateContactIdentityCache()

                refreshContacts()
                refreshRecents()

                refreshCurrentCallIdentityAfterContactsChange()
            }
    }

    private fun scheduleCallLogProviderRefresh() {

        callLogProviderRefreshJob
            ?.cancel()

        callLogProviderRefreshJob =
            scope.launch {

                delay(
                    PROVIDER_REFRESH_DEBOUNCE_MILLIS
                )

                refreshRecents()
            }
    }

    /**
     * A NOVA search miss is not definitive while PBAP is actively
     * mutating ContactsProvider.
     */
    fun contactsProviderChangingRecently():
        Boolean {

        val changedAt =
            lastContactsProviderChangeElapsedMillis

        if (changedAt <= 0L) {
            return false
        }

        return SystemClock.elapsedRealtime() -
            changedAt <=
            CONTACTS_PROVIDER_QUIET_WINDOW_MILLIS
    }

    /**
     * Retry active caller identity if the call began before PBAP delivered
     * the matching contact.
     */
    private suspend fun refreshCurrentCallIdentityAfterContactsChange() {

        val current =
            telecom.state.value

        if (
            current.status ==
                CallStatus.IDLE ||
            current.status ==
                CallStatus.CALL_ENDED
        ) {
            return
        }

        val number =
            current.number
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return

        val identity =
            resolveCallerIdentity(
                number
            )

        val afterLookup =
            telecom.state.value

        val afterNumber =
            afterLookup.number
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        if (
            afterNumber ==
                number &&
            afterLookup.status !=
                CallStatus.IDLE &&
            afterLookup.status !=
                CallStatus.CALL_ENDED
        ) {
            resolvedCallNumber =
                number

            resolvedCallIdentity.value =
                identity
        }
    }

    /**
     * Resolve the current Telecom call's number against Android Contacts.
     *
     * collectLatest ensures that if the call changes while the provider
     * lookup is still running, the old lookup cannot update the next call.
     */
    private fun observeCurrentCallIdentity() {

        scope.launch {

            telecom.state
                .collectLatest { call ->

                    val number =
                        call.number
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty()
                            }

                    /*
                     * A finished/no-call state must never retain the
                     * previous person's identity.
                     */
                    if (
                        call.status ==
                            CallStatus.IDLE ||
                        call.status ==
                            CallStatus.CALL_ENDED
                    ) {

                        resolvedCallNumber =
                            null

                        resolvedCallIdentity.value =
                            null

                        return@collectLatest
                    }

                    if (
                        number == null
                    ) {

                        resolvedCallNumber =
                            null

                        resolvedCallIdentity.value =
                            null

                        return@collectLatest
                    }

                    /*
                     * Telecom may publish several Details callbacks during
                     * one call. Query the ContactsProvider only once per
                     * telephone number.
                     */
                    if (
                        resolvedCallNumber ==
                            number
                    ) {
                        return@collectLatest
                    }

                    resolvedCallNumber =
                        number

                    resolvedCallIdentity.value =
                        null

                    val identity =
                        resolveCallerIdentity(
                            number
                        )

                    /*
                     * Ensure the lookup result still belongs to the
                     * currently active Telecom call.
                     */
                    val current =
                        telecom.state.value

                    val currentNumber =
                        current.number
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty()
                            }

                    if (
                        currentNumber !=
                            number ||
                        current.status ==
                            CallStatus.IDLE ||
                        current.status ==
                            CallStatus.CALL_ENDED
                    ) {

                        Log.i(
                            TAG,
                            "Discarding stale contact identity result"
                        )

                        return@collectLatest
                    }

                    resolvedCallIdentity.value =
                        identity

                    val hasResolvedIdentity =
                        identity != null &&
                            (
                                identity.photoUri != null ||
                                    (
                                        identity.displayName != null &&
                                            !PhoneNumberMatching.sameNumber(
                                                identity.displayName,
                                                number
                                            )
                                    )
                            )

                    if (
                        hasResolvedIdentity
                    ) {

                        Log.i(
                            TAG,
                            "Current call identity resolved from Android Contacts"
                        )

                    } else {

                        Log.i(
                            TAG,
                            "Current call number has no saved contact identity"
                        )
                    }
                }
        }
    }

    /**
     * Some Android Telecom implementations put the telephone number
     * itself into callerDisplayName.
     *
     * Treat that as a number, not as a useful contact name.
     */
    private fun meaningfulTelecomDisplayName(
        call: TelecomCallState
    ): String? {

        val displayName =
            call.displayName
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return null

        val number =
            call.number
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        if (
            number == null
        ) {
            return displayName
        }

        if (
            displayName ==
                number
        ) {
            return null
        }

        val samePhoneNumber =
            try {

                PhoneNumberUtils.compare(
                    displayName,
                    number
                )

            } catch (
                _: RuntimeException
            ) {

                false
            }

        return if (
            samePhoneNumber
        ) {
            null
        } else {
            displayName
        }
    }

    fun ensureContactsLoaded() {
        loadContacts(
            force = false
        )
    }

    /**
     * CONTACTS_EMPTY and CONTACTS_READY are snapshots, not permanent
     * terminal states. PBAP can change the provider at any time.
     */
    fun refreshContacts() {
        loadContacts(
            force = true
        )
    }

    private fun loadContacts(
        force: Boolean
    ) {

        if (
            !has(
                Manifest.permission.READ_CONTACTS
            )
        ) {
            contactsStatus.value =
                ContactsStatus.PERMISSION_REQUIRED

            return
        }

        if (
            contactsJob?.isActive ==
                true
        ) {
            return
        }

        if (
            !force &&
            contactsStatus.value in
                setOf(
                    ContactsStatus.CONTACTS_READY,
                    ContactsStatus.CONTACTS_EMPTY
                )
        ) {
            return
        }

        contactsStatus.value =
            ContactsStatus.CONTACTS_SYNCING

        contactsJob =
            scope.launch {

                val (
                    status,
                    values
                ) =
                    contacts.load()

                contactsStatus.value =
                    status

                contactsEntries.value =
                    values

                Log.i(
                    TAG,
                    "Contacts load completed: " +
                        "$status entries=${values.size}"
                )
            }
    }

    /**
     * Load one real Android contact by its ContactsProvider CONTACT_ID.
     *
     * This is the command-layer path used after NOVA has selected one
     * search candidate. The returned record contains every real phone row
     * for that contact, including the real Phone._ID used as numberId.
     *
     * No contact or number data is synthesized here.
     */
    suspend fun getContactDetails(
        contactId: Long
    ): ContactDetailsRecord? =
        contacts.loadContact(
            contactId
        )

    /**
     * Command-layer snapshot from the real Android CallLog.
     *
     * This does not mutate the Phone UI filter.
     */
    suspend fun getCallHistorySnapshot():
        RecentCallsLoadResult =
        history.load()

    /**
     * Resolve a real phone number to a real ContactsProvider CONTACT_ID.
     */
    suspend fun findContactIdByNumber(
        number: String
    ): Long? =
        contacts.findContactIdByNumber(
            number
        )

    /**
     * Resolve caller identity using real ContactsProvider data only.
     *
     * Includes real photo URI, stale lookup protection, and provider-change retry.
     */
    suspend fun resolveCallerIdentity(
        number: String
    ): com.hypernova.phone.contacts.CallerIdentity? =
        contacts.resolveCallerIdentity(
            number
        )

    /**
     * Repeated Recents commands share this one job and preserve the
     * current list while it refreshes.
     */
    fun ensureRecentsLoaded() {

        if (
            !has(
                Manifest.permission.READ_CALL_LOG
            )
        ) {

            recentsStatus.value =
                RecentsStatus
                    .RECENTS_PERMISSION_REQUIRED

            return
        }

        if (
            recentsJob?.isActive ==
                true ||
            recentsStatus.value in
                setOf(
                    RecentsStatus.RECENTS_READY,
                    RecentsStatus.RECENTS_EMPTY
                )
        ) {
            return
        }

        loadRecents(
            force = false
        )
    }

    fun refreshRecents() {
        loadRecents(
            force = true
        )
    }

    private fun loadRecents(
        force: Boolean
    ) {

        if (
            !has(
                Manifest.permission.READ_CALL_LOG
            )
        ) {

            recentsStatus.value =
                RecentsStatus
                    .RECENTS_PERMISSION_REQUIRED

            return
        }

        val generation =
            recentsGate.begin(
                force
            )
                ?: return

        recentsJob?.cancel()

        recentsStatus.value =
            RecentsStatus.RECENTS_LOADING

        recentsJob =
            scope.launch {

                val result =
                    history.load()

                if (
                    !recentsGate
                        .isCurrent(
                            generation
                        )
                ) {
                    return@launch
                }

                recentsStatus.value =
                    result.status

                /*
                 * A provider error retains any previous good rows rather
                 * than replacing the UI with an empty list.
                 */
                if (
                    result.status !=
                        RecentsStatus.RECENTS_ERROR
                ) {
                    recentEntries.value =
                        result.entries
                }

                recentsGate.complete(
                    generation
                )

                Log.i(
                    TAG,
                    "Recents load completed: ${result.status}"
                )
            }
    }

    private fun initialData() =
        PhoneDataState(
            availability =
                PhoneAvailability.NO_PHONE,

            bluetooth =
                bluetooth.state.value,

            capabilities =
                capabilities(),

            contactsStatus =
                ContactsStatus.PERMISSION_REQUIRED,

            recentsStatus =
                RecentsStatus
                    .RECENTS_PERMISSION_REQUIRED,

            contacts =
                emptyList(),

            recents =
                emptyList(),

            call =
                TelecomCallState()
        )

    private fun capabilities():
        PhoneCapabilities {

        val bluetoothCapability =
            if (
                has(
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                )
            ) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.PERMISSION_REQUIRED
            }

        val contactsCapability =
            if (
                has(
                    Manifest.permission
                        .READ_CONTACTS
                )
            ) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.PERMISSION_REQUIRED
            }

        val historyCapability =
            if (
                has(
                    Manifest.permission
                        .READ_CALL_LOG
                )
            ) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.PERMISSION_REQUIRED
            }

        val telecomCapability =
            if (
                context.getSystemService(
                    TelecomManager::class.java
                ) == null
            ) {

                CapabilityStatus.UNSUPPORTED

            } else if (
                has(
                    Manifest.permission.CALL_PHONE
                )
            ) {

                CapabilityStatus.AVAILABLE

            } else {

                CapabilityStatus.PERMISSION_REQUIRED
            }

        val roleManager =
            context.getSystemService(
                RoleManager::class.java
            )

        val dialerCapability =
            when {

                roleManager == null ||
                    !roleManager.isRoleAvailable(
                        RoleManager.ROLE_DIALER
                    ) -> {

                    CapabilityStatus.UNSUPPORTED
                }

                roleManager.isRoleHeld(
                    RoleManager.ROLE_DIALER
                ) -> {

                    CapabilityStatus.AVAILABLE
                }

                else -> {
                    CapabilityStatus.ROLE_REQUIRED
                }
            }

        return PhoneCapabilities(
            bluetooth =
                bluetoothCapability,

            telecom =
                telecomCapability,

            dialer =
                dialerCapability,

            contacts =
                contactsCapability,

            callHistory =
                historyCapability
        )
    }

    private fun has(
        permission: String
    ): Boolean {

        return ContextCompat
            .checkSelfPermission(
                context,
                permission
            ) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG =
            "HN-Phone"
    }
}
