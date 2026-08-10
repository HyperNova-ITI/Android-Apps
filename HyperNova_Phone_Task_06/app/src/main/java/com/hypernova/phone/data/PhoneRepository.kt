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
     * Contact name resolved specifically for the current real Telecom call.
     *
     * The TelecomCallController remains the owner of call state.
     * This flow adds presentation identity only.
     */
    private val resolvedCallName =
        MutableStateFlow<String?>(null)

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
            resolvedCallName
        ) { call, resolvedName ->

            val telecomName =
                meaningfulTelecomDisplayName(
                    call
                )

            call.copy(
                displayName =
                    telecomName
                        ?: resolvedName
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
        refreshCapabilities()
    }

    fun stop() {
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

                        resolvedCallName.value =
                            null

                        return@collectLatest
                    }

                    /*
                     * If Telecom already supplied a meaningful real name,
                     * there is no reason to query Contacts.
                     */
                    val telecomName =
                        meaningfulTelecomDisplayName(
                            call
                        )

                    if (
                        telecomName != null
                    ) {

                        resolvedCallNumber =
                            number

                        resolvedCallName.value =
                            telecomName

                        Log.i(
                            TAG,
                            "Using caller identity supplied by Android Telecom"
                        )

                        return@collectLatest
                    }

                    if (
                        number == null
                    ) {

                        resolvedCallNumber =
                            null

                        resolvedCallName.value =
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

                    resolvedCallName.value =
                        null

                    val contactName =
                        contacts
                            .findDisplayNameByNumber(
                                number
                            )
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty()
                            }

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

                    resolvedCallName.value =
                        contactName

                    if (
                        contactName != null
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
                true ||
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
            }
    }

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
