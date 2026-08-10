package com.hypernova.phone.domain

/**
 * Values are intentionally capability-oriented so a normal APK never
 * implies privileged access that Android has not actually granted.
 */
enum class CapabilityStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    ROLE_REQUIRED,
    PLATFORM_PRIVILEGE_REQUIRED,
    UNSUPPORTED,
    ERROR
}

enum class PhoneAvailability {
    NO_PHONE,
    CONNECTING,
    READY,
    CONTACTS_SYNCING,
    ERROR
}

enum class BluetoothConnectionState {
    BLUETOOTH_DISABLED,
    DEVICE_LIST,
    PAIRING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    CONNECTION_FAILED
}

enum class ContactsStatus {
    CONTACTS_EMPTY,
    CONTACTS_SYNCING,
    CONTACTS_READY,
    CONTACTS_SYNC_FAILED,
    PERMISSION_REQUIRED
}

enum class CallStatus {
    IDLE,
    DIALING,
    RINGING,
    INCOMING,
    ACTIVE,
    MUTED,
    HELD,
    CALL_ENDED,
    MISSED,
    REJECTED,
    FAILED
}

enum class PhoneScreen {
    HOME,
    KEYPAD,
    CONTACTS,
    RECENTS,
    DEVICES,
    CALL
}

enum class RecentFilter {
    ALL,
    MISSED,
    INCOMING,
    OUTGOING
}

enum class RecentsStatus {
    RECENTS_LOADING,
    RECENTS_READY,
    RECENTS_EMPTY,
    RECENTS_PERMISSION_REQUIRED,
    RECENTS_ERROR
}

enum class CallNumberPresentation {
    ALLOWED,
    PRIVATE,
    RESTRICTED,
    UNKNOWN,
    PAYPHONE,
    UNAVAILABLE
}

data class PhoneCapabilities(
    val bluetooth: CapabilityStatus,
    val telecom: CapabilityStatus,
    val dialer: CapabilityStatus,
    val contacts: CapabilityStatus,
    val callHistory: CapabilityStatus,
    val hfp: CapabilityStatus =
        CapabilityStatus.PLATFORM_PRIVILEGE_REQUIRED,
    val pbap: CapabilityStatus =
        CapabilityStatus.PLATFORM_PRIVILEGE_REQUIRED
)

data class BluetoothDeviceInfo(
    val name: String,
    val addressSuffix: String
)

data class BluetoothUiState(
    val state: BluetoothConnectionState,
    val pairedDevices: List<BluetoothDeviceInfo> = emptyList(),
    val connectedDeviceName: String? = null,
    val detail: String = ""
)

data class ContactEntry(
    val id: Long,
    val displayName: String,
    val number: String,
    val label: String?,
    val isFavorite: Boolean
)

data class RecentCallEntry(
    val id: Long,
    val displayName: String?,
    val number: String?,
    val type: RecentFilter,
    val timestamp: Long,
    val durationSeconds: Long,
    val presentation: CallNumberPresentation =
        CallNumberPresentation.ALLOWED
)

/**
 * Presentation is derived only from the real provider row.
 * A missing contact never removes the CallLog row.
 */
object RecentCallLabels {

    fun primary(
        displayName: String?,
        number: String?,
        presentation: CallNumberPresentation
    ): String =
        displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: number
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: when (presentation) {
                CallNumberPresentation.PRIVATE ->
                    "Private number"

                CallNumberPresentation.RESTRICTED ->
                    "Restricted number"

                CallNumberPresentation.PAYPHONE ->
                    "Payphone"

                CallNumberPresentation.UNAVAILABLE ->
                    "Number unavailable"

                CallNumberPresentation.UNKNOWN,
                CallNumberPresentation.ALLOWED ->
                    "Unknown number"
            }
}

/**
 * Real Android Telecom call state.
 *
 * isMuted is updated only after InCallService receives the real
 * CallAudioState from Android.
 */
data class TelecomCallState(
    val status: CallStatus = CallStatus.IDLE,
    val displayName: String? = null,
    val number: String? = null,
    val startedAtMillis: Long? = null,
    val isMuted: Boolean = false,
    val canHold: Boolean = false,
    val canAnswer: Boolean = false,
    val canDisconnect: Boolean = false,
    val message: String? = null
)

data class PhoneDataState(
    val availability: PhoneAvailability,
    val bluetooth: BluetoothUiState,
    val capabilities: PhoneCapabilities,
    val contactsStatus: ContactsStatus,
    val recentsStatus: RecentsStatus,
    val contacts: List<ContactEntry>,
    val recents: List<RecentCallEntry>,
    val call: TelecomCallState
)

/**
 * Pure UI state.
 *
 * inCallKeypadVisible and inCallDtmfDigits never represent call state.
 * They only describe the presentation of the active Telecom call.
 */
data class PhoneUiState(
    val screen: PhoneScreen = PhoneScreen.HOME,
    val data: PhoneDataState,
    val dialedNumber: String = "",
    val recentFilter: RecentFilter = RecentFilter.ALL,
    val isContactSearchVisible: Boolean = false,
    val isInCallKeypadVisible: Boolean = false,
    val inCallDtmfDigits: String = "",
    val nowMillis: Long = System.currentTimeMillis()
)
