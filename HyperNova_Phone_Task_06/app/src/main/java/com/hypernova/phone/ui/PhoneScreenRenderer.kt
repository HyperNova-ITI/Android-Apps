package com.hypernova.phone.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.hypernova.phone.R
import com.hypernova.phone.databinding.ActivityMainBinding
import com.hypernova.phone.domain.BluetoothConnectionState
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.CapabilityStatus
import com.hypernova.phone.domain.ContactEntry
import com.hypernova.phone.domain.PhoneScreen
import com.hypernova.phone.domain.PhoneUiState
import com.hypernova.phone.domain.RecentCallEntry
import com.hypernova.phone.domain.RecentCallLabels
import com.hypernova.phone.domain.RecentFilter
import com.hypernova.phone.domain.RecentsStatus
import java.text.DateFormat
import java.util.Date

/**
 * XML hosts the shell.
 *
 * Every visible row is derived from real Android provider / Telecom state.
 */
class PhoneScreenRenderer(
    private val binding: ActivityMainBinding,
    private val actions: Actions
) {

    interface Actions {

        fun navigate(
            screen: PhoneScreen
        )

        fun navigateBack()

        fun requestBluetooth()

        fun requestContacts()

        fun requestHistory()

        fun requestCallPermission()

        fun requestDialerRole()

        fun appendDigit(
            value: String
        )

        fun deleteDigit()

        fun placeCall()

        fun callNumber(
            number: String
        )

        fun selectRecentFilter(
            filter: RecentFilter
        )

        fun answer()

        fun decline()

        fun endCall()

        fun holdOrResume(
            held: Boolean
        )

        fun toggleMute()

        fun toggleSpeaker()

        fun toggleInCallKeypad()

        fun sendDtmf(
            value: String
        )

        fun showAudioRoutes()
    }

    fun render(
        state: PhoneUiState
    ) {

        binding.content
            .removeAllViews()

        val fullCall =
            state.screen ==
                PhoneScreen.CALL &&
                state.data.call.status !=
                CallStatus.IDLE

        binding.header.visibility =
            View.VISIBLE

        binding.bottomNavigation.visibility =
            if (
                fullCall
            ) {
                View.GONE
            } else {
                View.VISIBLE
            }

        binding.content.layoutParams =
            (
                binding.content.layoutParams
                    as androidx.constraintlayout.widget
                        .ConstraintLayout
                        .LayoutParams
                ).apply {

                bottomToTop =
                    if (fullCall) {
                        binding.cockpitNavigation.id
                    } else {
                        binding.bottomNavigation.id
                    }

                bottomToBottom =
                    androidx.constraintlayout.widget
                        .ConstraintLayout
                        .LayoutParams
                        .UNSET
            }

        binding.headerTitle.text =
            when (
                state.screen
            ) {

                PhoneScreen.HOME ->
                    "PHONE"

                PhoneScreen.KEYPAD ->
                    "KEYPAD"

                PhoneScreen.CONTACTS ->
                    "CONTACTS"

                PhoneScreen.RECENTS ->
                    "RECENTS"

                PhoneScreen.DEVICES ->
                    "DEVICES"

                PhoneScreen.CALL ->
                    "PHONE"
            }

        binding.headerStatus.text =
            headerStatus(
                state
            )

        binding.backButton.visibility =
            if (fullCall) View.INVISIBLE else View.VISIBLE

        binding.backButton
            .setOnClickListener {

                actions.navigateBack()
            }

        updateNav(
            state.screen
        )

        when (
            state.screen
        ) {

            PhoneScreen.HOME ->
                home(
                    state
                )

            PhoneScreen.KEYPAD ->
                keypad(
                    state
                )

            PhoneScreen.CONTACTS ->
                contacts(
                    state
                )

            PhoneScreen.RECENTS ->
                recents(
                    state
                )

            PhoneScreen.DEVICES ->
                devices(
                    state
                )

            PhoneScreen.CALL ->
                call(
                    state
                )
        }
    }

    private fun headerStatus(
        state: PhoneUiState
    ): String {

        val call =
            state.data.call

        return when (
            call.status
        ) {

            CallStatus.INCOMING ->
                "Incoming call"

            CallStatus.ACTIVE ->
                if (
                    call.isMuted
                ) {
                    "Microphone muted"
                } else {
                    "Call in progress"
                }

            CallStatus.HELD ->
                "Call on hold"

            CallStatus.DIALING,
            CallStatus.RINGING ->
                "Calling"

            else ->
                when (
                    state.data.bluetooth.state
                ) {

                    BluetoothConnectionState
                        .BLUETOOTH_DISABLED ->
                        "Bluetooth off"

                    BluetoothConnectionState
                        .CONNECTING ->
                        "Connecting"

                BluetoothConnectionState
                    .CONNECTED ->
                    state.data.bluetooth
                        .connectedDeviceName
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            "Bluetooth link · $it"
                        }
                        ?: "Bluetooth link · HFP connected"

                    else ->
                        "No phone connected"
                }
        }
    }

    private fun updateNav(
        screen: PhoneScreen
    ) {

        listOf(
            binding.navHome to
                PhoneScreen.HOME,

            binding.navKeypad to
                PhoneScreen.KEYPAD,

            binding.navContacts to
                PhoneScreen.CONTACTS,

            binding.navRecents to
                PhoneScreen.RECENTS
        ).forEach {
                (
                    button,
                    target
                ) ->

            val selected =
                target ==
                    screen

            button.isSelected = selected
            button.setTextColor(
                color(
                    if (selected) R.color.hn_primary_cyan
                    else R.color.hn_text_secondary
                )
            )
            button.iconTint = ColorStateList.valueOf(
                color(
                    if (selected) R.color.hn_primary_cyan
                    else R.color.hn_text_secondary
                )
            )
            button.background = drawable(
                if (selected) R.drawable.bg_phone_nav_selected
                else R.drawable.bg_phone_nav
            )
            configureNavPressAnimation(button)

            button
                .setOnClickListener {

                    actions.navigate(
                        target
                    )
                }
        }
    }

    private fun configureNavPressAnimation(button: MaterialButton) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(100L)
                    .start()

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            false
        }
    }

    private fun home(
        state: PhoneUiState
    ) =
        scroll { column ->

            column.addView(
                sectionLabel(
                    "PHONE STATUS"
                )
            )

            column.addView(
                connectionCard(
                    state
                )
            )

            if (
                state.data.bluetooth.state !=
                BluetoothConnectionState
                    .BLUETOOTH_DISABLED
            ) {


            }

            column.addView(
                sectionLabel(
                    "FAVORITES"
                )
            )

            val favorites =
                state.data.contacts
                    .filter {
                        it.isFavorite
                    }
                    .take(4)

            if (
                favorites.isEmpty()
            ) {

                column.addView(
                    emptyCard(
                        "No favorite contacts",
                        "Favorites appear from the real Contacts provider when access is granted."
                    )
                )

            } else {

                favorites.forEach {

                    column.addView(
                        contactRow(
                            it
                        )
                    )
                }
            }

            column.addView(
                sectionLabel(
                    "RECENT CALLS"
                )
            )

            if (
                state.data.recents
                    .isEmpty()
            ) {

                column.addView(
                    emptyCard(
                        "No recent calls",
                        "Recent calls appear only from the device Call Log provider."
                    )
                )

            } else {

                state.data.recents
                    .take(3)
                    .forEach {

                        column.addView(
                            recentRow(
                                it
                            )
                        )
                    }
            }
        }

    private fun connectionCard(
        state: PhoneUiState
    ): View =
        card {

            gravity =
                Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(24),
                dp(26),
                dp(24),
                dp(24)
            )

            val ring =
                ImageView(
                    context
                ).apply {

                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                dp(96),
                                dp(96)
                            )

                    setImageResource(
                        if (
                            state.data.bluetooth.state ==
                            BluetoothConnectionState
                                .BLUETOOTH_DISABLED
                        ) {
                            R.drawable.ic_device
                        } else {
                            R.drawable.ic_bluetooth
                        }
                    )

                    background =
                        drawable(
                            R.drawable.bg_avatar
                        )

                    setPadding(
                        dp(27),
                        dp(27),
                        dp(27),
                        dp(27)
                    )

                    contentDescription =
                        context.getString(
                            R.string.cd_bluetooth
                        )
                }

            addView(
                ring
            )

            addView(
                title(
                    when (
                    state.data.bluetooth.state
                ) {

                    BluetoothConnectionState
                        .BLUETOOTH_DISABLED ->
                        "Bluetooth is off"

                    BluetoothConnectionState
                        .CONNECTED ->
                        state.data.bluetooth
                            .connectedDeviceName
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "Phone connected"

                    BluetoothConnectionState
                        .CONNECTING ->
                        "Connecting phone"

                    else ->
                        "Phone disconnected"
                },
                    24
                ).withTop(
                    16
                )
            )

            addView(
                body(
                    state.data.bluetooth.detail
                        .ifBlank {
                            "No phone is connected for hands-free calling."
                        },
                    Gravity.CENTER
                ).withTop(
                    7
                )
            )

            addView(
                primaryButton(
                    if (
                        state.data.capabilities.bluetooth ==
                        CapabilityStatus
                            .PERMISSION_REQUIRED
                    ) {
                        "Allow Bluetooth access"
                    } else {
                        "Open device list"
                    }
                ) {

                    if (
                        state.data.capabilities.bluetooth ==
                        CapabilityStatus
                            .PERMISSION_REQUIRED
                    ) {
                        actions.requestBluetooth()
                    } else {
                        actions.navigate(
                            PhoneScreen.DEVICES
                        )
                    }

                }.withTop(
                    22
                )
            )

        }

    private fun keypad(
        state: PhoneUiState
    ) =
        scroll { column ->

            column.gravity =
                Gravity.CENTER_HORIZONTAL

            val number =
                if (
                    state.dialedNumber
                        .isEmpty()
                ) {
                    "Enter a number"
                } else {
                    state.dialedNumber
                }

            column.addView(
                TextView(
                    context
                ).apply {

                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                ViewGroup
                                    .LayoutParams
                                    .MATCH_PARENT,
                                dp(70)
                            )

                    gravity =
                        Gravity.CENTER

                    text =
                        number

                    textSize =
                        if (
                            state.dialedNumber
                                .isEmpty()
                        ) {
                            20f
                        } else {
                            31f
                        }

                    setTextColor(
                        color(
                            if (
                                state.dialedNumber
                                    .isEmpty()
                            ) {
                                R.color
                                    .hn_text_secondary
                            } else {
                                R.color
                                    .hn_text_primary
                            }
                        )
                    )

                    typeface =
                        Typeface.create(
                            "sans",
                            Typeface.NORMAL
                        )
                }
            )

            column.addView(
                GridLayout(
                    context
                ).apply {

                    columnCount =
                        3

                    useDefaultMargins =
                        true

                    setPadding(
                        dp(32),
                        dp(4),
                        dp(32),
                        dp(10)
                    )

                    listOf(
                        "1", "2", "3",
                        "4", "5", "6",
                        "7", "8", "9",
                        "*", "0", "#"
                    ).forEach { digit ->

                        addView(
                            keyButton(
                                digit
                            ) {

                                actions.appendDigit(
                                    digit
                                )
                            },
                            GridLayout
                                .LayoutParams()
                                .apply {

                                    width =
                                        0

                                    height =
                                        dp(70)

                                    columnSpec =
                                        GridLayout.spec(
                                            GridLayout.UNDEFINED,
                                            1f
                                        )
                                }
                        )
                    }
                }
            )

            column.addView(
                body(
                    keypadCapability(
                        state
                    ),
                    Gravity.CENTER
                )
            )

            val enabled =
                state.dialedNumber
                    .isNotEmpty() &&
                    state.data.capabilities.telecom ==
                    CapabilityStatus.AVAILABLE

            column.addView(
                primaryButton(
                    "Call",
                    enabled
                ) {

                    if (
                        enabled
                    ) {
                        actions.placeCall()
                    } else {
                        actions.requestCallPermission()
                    }

                }.withTop(
                    14
                )
            )

            column.addView(
                outlineButton(
                    "Delete"
                ) {
                    actions.deleteDigit()
                }.withTop(
                    10
                )
            )
        }

    private fun keypadCapability(
        state: PhoneUiState
    ) =
        when (
            state.data.capabilities.telecom
        ) {

            CapabilityStatus.AVAILABLE ->
                "Android Telecom is available. Call status changes only after Telecom confirms them."

            CapabilityStatus.PERMISSION_REQUIRED ->
                "Calling is unavailable until Phone access is granted."

            else ->
                "Phone service unavailable on this device."
        }

    private fun contacts(
        state: PhoneUiState
    ) =
        scroll { column ->

            column.addView(
                searchBar(
                    "Search contacts",
                    "Voice search will be provided by NOVA AI integration."
                )
            )

            when (
                state.data.capabilities.contacts
            ) {

                CapabilityStatus
                    .PERMISSION_REQUIRED ->

                    column.addView(
                        permissionCard(
                            "Contacts access required",
                            "Grant access to show real contacts synchronized by the platform.",
                            "Allow contacts"
                        ) {
                            actions.requestContacts()
                        }.withTop(
                            14
                        )
                    )

                else -> {

                    if (
                        state.data.contacts
                            .isEmpty()
                    ) {

                        column.addView(
                            emptyCard(
                                "No contacts available",
                                "No contacts were returned by the Contacts provider."
                            ).withTop(
                                14
                            )
                        )

                    } else {

                        val favorites =
                            state.data.contacts
                                .filter {
                                    it.isFavorite
                                }

                        if (
                            favorites.isNotEmpty()
                        ) {

                            column.addView(
                                sectionLabel(
                                    "FAVORITES"
                                )
                            )

                            favorites.forEach {

                                column.addView(
                                    contactRow(
                                        it
                                    )
                                )
                            }
                        }

                        column.addView(
                            sectionLabel(
                                "ALL CONTACTS"
                            )
                        )

                        state.data.contacts
                            .forEach {

                                column.addView(
                                    contactRow(
                                        it
                                    )
                                )
                            }
                    }
                }
            }
        }

    private fun recents(
        state: PhoneUiState
    ) =
        scroll { column ->

            val tabs =
                LinearLayout(
                    context
                ).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER
                }

            RecentFilter.entries
                .forEach { filter ->

                    tabs.addView(
                        outlineButton(
                            filter.name
                                .lowercase()
                                .replaceFirstChar {
                                    it.titlecase()
                                }
                        ) {

                            actions
                                .selectRecentFilter(
                                    filter
                                )

                        }.apply {

                            layoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        0,
                                        dp(42),
                                        1f
                                    ).apply {

                                        marginEnd =
                                            dp(6)
                                    }

                            if (
                                filter ==
                                state.recentFilter
                            ) {

                                background =
                                    drawable(
                                        R.drawable
                                            .bg_surface_selected
                                    )

                                setTextColor(
                                    color(
                                        R.color
                                            .hn_primary_cyan
                                    )
                                )
                            }
                        }
                    )
                }

            column.addView(
                tabs
            )

            if (
                state.data.capabilities.callHistory ==
                CapabilityStatus
                    .PERMISSION_REQUIRED
            ) {

                column.addView(
                    permissionCard(
                        "Recent-call access required",
                        "Grant access to show actual call history from the device provider.",
                        "Allow recent calls"
                    ) {
                        actions.requestHistory()
                    }.withTop(
                        14
                    )
                )

            } else {

                val rows =
                    state.data.recents
                        .filter {

                            state.recentFilter ==
                                RecentFilter.ALL ||
                                it.type ==
                                state.recentFilter
                        }

                if (
                    rows.isNotEmpty()
                ) {

                    if (
                        state.data.recentsStatus ==
                        RecentsStatus
                            .RECENTS_LOADING
                    ) {

                        column.addView(
                            body(
                                "Updating recent calls…",
                                Gravity.CENTER
                            ).withTop(
                                14
                            )
                        )
                    }

                    rows.forEach {

                        column.addView(
                            recentRow(
                                it
                            )
                        )
                    }

                } else {

                    when (
                        state.data.recentsStatus
                    ) {

                        RecentsStatus
                            .RECENTS_LOADING ->
                            column.addView(
                                emptyCard(
                                    "Loading recent calls",
                                    "Reading the newest call-log entries…"
                                ).withTop(
                                    14
                                )
                            )

                        RecentsStatus
                            .RECENTS_ERROR ->
                            column.addView(
                                emptyCard(
                                    "Recent calls unavailable",
                                    "The Call Log provider did not return data. Try again from this screen."
                                ).withTop(
                                    14
                                )
                            )

                        RecentsStatus
                            .RECENTS_EMPTY,
                        RecentsStatus
                            .RECENTS_READY ->
                            column.addView(
                                emptyCard(
                                    "No recent calls",
                                    "No entries match this filter."
                                ).withTop(
                                    14
                                )
                            )

                        RecentsStatus
                            .RECENTS_PERMISSION_REQUIRED ->
                            Unit
                    }
                }
            }
        }

    private fun devices(
        state: PhoneUiState
    ) =
        scroll { column ->

            column.addView(
                sectionLabel(
                    "PAIRED DEVICES"
                )
            )

            if (
                state.data.capabilities.bluetooth ==
                CapabilityStatus
                    .PERMISSION_REQUIRED
            ) {

                column.addView(
                    permissionCard(
                        "Bluetooth access required",
                        "Grant access to view paired devices available to this application.",
                        "Allow Bluetooth"
                    ) {
                        actions.requestBluetooth()
                    }
                )

            } else if (
                state.data.bluetooth
                    .pairedDevices
                    .isEmpty()
            ) {

                column.addView(
                    emptyCard(
                        "No paired devices",
                        "Pairing and HFP connection are completed by the vehicle Bluetooth platform.",
                        "Open Bluetooth settings"
                    ) {
                        actions.requestBluetooth()
                    }
                )

            } else {

                state.data.bluetooth
                    .pairedDevices
                    .forEach { device ->

                        column.addView(
                            card {

                                orientation =
                                    LinearLayout.HORIZONTAL

                                gravity =
                                    Gravity.CENTER_VERTICAL

                                addView(
                                    icon(
                                        R.drawable.ic_device
                                    ).fixed(
                                        46,
                                        46
                                    )
                                )

                                addView(
                                    LinearLayout(
                                        context
                                    ).apply {

                                        orientation =
                                            LinearLayout.VERTICAL

                                        addView(
                                            title(
                                                device.name,
                                                16
                                            )
                                        )

                                        addView(
                                            body(
                                                "Paired · ID ending ${device.addressSuffix}"
                                            )
                                        )

                                    }.weighted()
                                )

                                addView(
                                    outlineButton(
                                        "Visible"
                                    ) {
                                    }
                                )
                            }.withTop(
                                8
                            )
                        )
                    }
            }

            column.addView(
                sectionLabel(
                    "PHONE AUDIO"
                )
            )


            column.addView(
                sectionLabel(
                    "AVAILABLE DEVICES"
                )
            )

            column.addView(
                emptyCard(
                    "Discovery is not running",
                    "Device discovery and pairing are restricted to parked-safe vehicle policy and platform Bluetooth integration."
                )
            )
        }

    private fun call(
        state: PhoneUiState
    ) =
        scroll { column ->

            column.gravity =
                Gravity.CENTER_HORIZONTAL

            val call =
                state.data.call

            val status =
                when (
                    call.status
                ) {

                    CallStatus.INCOMING ->
                        "Incoming call"

                    CallStatus.ACTIVE ->
                        elapsed(
                            call.startedAtMillis,
                            state.nowMillis
                        )

                    CallStatus.MUTED ->
                        elapsed(
                            call.startedAtMillis,
                            state.nowMillis
                        )

                    CallStatus.HELD ->
                        "On hold"

                    CallStatus.DIALING,
                    CallStatus.RINGING ->
                        "Dialing…"

                    CallStatus.CALL_ENDED ->
                        "Call ended"

                    else ->
                        call.status.name
                            .lowercase()
                            .replaceFirstChar {
                                it.titlecase()
                            }
                }

            val accent =
                when (
                    call.status
                ) {

                    CallStatus.INCOMING,
                    CallStatus.ACTIVE,
                    CallStatus.MUTED ->
                        R.color.hn_success

                    CallStatus.HELD ->
                        R.color.hn_warning

                    CallStatus.CALL_ENDED,
                    CallStatus.FAILED ->
                        R.color.hn_error

                    else ->
                        R.color.hn_primary_cyan
                }

            column.addView(
                TextView(
                    context
                ).apply {

                    text =
                        status

                    textSize =
                        18f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        color(
                            accent
                        )
                    )

                    typeface =
                        Typeface.DEFAULT_BOLD

                }.withTop(
                    24
                )
            )

            column.addView(
                title(
                    call.displayName
                        ?: call.number
                        ?: "Call information unavailable",
                    30,
                    Gravity.CENTER
                ).withTop(
                    12
                )
            )

            if (
                call.displayName !=
                null &&
                call.number !=
                null
            ) {

                column.addView(
                    body(
                        call.number,
                        Gravity.CENTER
                    ).withTop(
                        5
                    )
                )
            }

            column.addView(
                icon(
                    R.drawable.ic_phone
                ).apply {

                    background =
                        drawable(
                            R.drawable.bg_avatar
                        )

                    setPadding(
                        dp(42),
                        dp(42),
                        dp(42),
                        dp(42)
                    )

                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                dp(150),
                                dp(150)
                            ).apply {

                                topMargin =
                                    dp(24)

                                bottomMargin =
                                    dp(22)
                            }
                }
            )

            when (
                call.status
            ) {

                CallStatus.INCOMING -> {

                    val choices =
                        LinearLayout(
                            context
                        ).apply {

                            gravity =
                                Gravity.CENTER

                            orientation =
                                LinearLayout.HORIZONTAL
                        }

                    choices.addView(
                        errorButton(
                            "Decline"
                        ) {
                            actions.decline()
                        }.apply {

                            layoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        0,
                                        dp(64),
                                        1f
                                    ).apply {

                                        marginEnd =
                                            dp(10)
                                    }
                        }
                    )

                    choices.addView(
                        primaryButton(
                            "Answer"
                        ) {
                            actions.answer()
                        }.apply {

                            layoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        0,
                                        dp(64),
                                        1f
                                    )
                        }
                    )

                    column.addView(
                        choices
                    )
                }

                CallStatus.CALL_ENDED -> {

                    column.addView(
                        outlineButton(
                            "Back to recents"
                        ) {

                            actions.navigate(
                                PhoneScreen.RECENTS
                            )
                        }
                    )
                }

                else -> {

                    activeCallControls(
                        column,
                        state
                    )

                    if (
                        state.isInCallKeypadVisible
                    ) {

                        inCallDtmfPad(
                            column,
                            state
                        )
                    }

                    column.addView(
                        errorButton(
                            "END CALL"
                        ) {
                            actions.endCall()
                        }.withTop(
                            22
                        )
                    )
                }
            }
        }

    private fun activeCallControls(
        column: LinearLayout,
        state: PhoneUiState
    ) {

        val call =
            state.data.call

        val firstRow =
            LinearLayout(
                context
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        firstRow.addView(
            callControlButton(
                if (
                    call.isMuted
                ) {
                    "MUTED"
                } else {
                    "MUTE"
                },
                selected =
                    call.isMuted
            ) {

                actions.toggleMute()

            }.callWeight(
                endMargin =
                    7
            )
        )

        firstRow.addView(
            callControlButton(
                if (
                    state.isInCallKeypadVisible
                ) {
                    "CLOSE KEYPAD"
                } else {
                    "KEYPAD"
                },
                selected =
                    state.isInCallKeypadVisible
            ) {

                actions
                    .toggleInCallKeypad()

            }.callWeight(
                endMargin =
                    7
            )
        )

        firstRow.addView(
            callControlButton(
                "SPEAKER"
            ) {

                actions.toggleSpeaker()

            }.callWeight()
        )

        column.addView(
            firstRow
        )

        val secondRow =
            LinearLayout(
                context
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        if (
            call.canHold ||
            call.status ==
            CallStatus.HELD
        ) {

            secondRow.addView(
                callControlButton(
                    if (
                        call.status ==
                        CallStatus.HELD
                    ) {
                        "RESUME"
                    } else {
                        "HOLD"
                    },
                    selected =
                        call.status ==
                        CallStatus.HELD
                ) {

                    actions.holdOrResume(
                        call.status ==
                            CallStatus.HELD
                    )

                }.callWeight(
                    endMargin =
                        7
                )
            )

        } else {

            secondRow.addView(
                callControlButton(
                    "HOLD",
                    enabled =
                        false
                ) {
                }.callWeight(
                    endMargin =
                        7
                )
            )
        }

        secondRow.addView(
            callControlButton(
                "AUDIO"
            ) {

                actions.showAudioRoutes()

            }.callWeight()
        )

        column.addView(
            secondRow
                .withTop(
                    10
                )
        )
    }

    private fun inCallDtmfPad(
        column: LinearLayout,
        state: PhoneUiState
    ) {

        column.addView(
            card {

                gravity =
                    Gravity.CENTER_HORIZONTAL

                addView(
                    TextView(
                        context
                    ).apply {

                        text =
                            state.inCallDtmfDigits
                                .ifBlank {
                                    "Send DTMF tones"
                                }

                        gravity =
                            Gravity.CENTER

                        textSize =
                            if (
                                state.inCallDtmfDigits
                                    .isBlank()
                            ) {
                                15f
                            } else {
                                24f
                            }

                        setTextColor(
                            color(
                                if (
                                    state.inCallDtmfDigits
                                        .isBlank()
                                ) {
                                    R.color
                                        .hn_text_secondary
                                } else {
                                    R.color
                                        .hn_text_primary
                                }
                            )
                        )
                    }
                )

                addView(
                    GridLayout(
                        context
                    ).apply {

                        columnCount =
                            3

                        useDefaultMargins =
                            true

                        setPadding(
                            dp(18),
                            dp(8),
                            dp(18),
                            dp(4)
                        )

                        listOf(
                            "1", "2", "3",
                            "4", "5", "6",
                            "7", "8", "9",
                            "*", "0", "#"
                        ).forEach { digit ->

                            addView(
                                keyButton(
                                    digit
                                ) {

                                    actions.sendDtmf(
                                        digit
                                    )
                                },
                                GridLayout
                                    .LayoutParams()
                                    .apply {

                                        width =
                                            0

                                        height =
                                            dp(58)

                                        columnSpec =
                                            GridLayout.spec(
                                                GridLayout.UNDEFINED,
                                                1f
                                            )
                                    }
                            )
                        }
                    }
                )

            }.withTop(
                16
            )
        )
    }

    private fun contactRow(
        contact: ContactEntry
    ): View =
        card {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            addView(
                initialAvatar(
                    contact.displayName
                ).fixed(
                    48,
                    48
                )
            )

            addView(
                LinearLayout(
                    context
                ).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    addView(
                        title(
                            contact.displayName,
                            16
                        )
                    )

                    addView(
                        body(
                            contact.label
                                ?: "Phone"
                        )
                    )

                }.weighted()
            )

            addView(
                outlineButton(
                    "Call"
                ) {

                    actions.callNumber(
                        contact.number
                    )
                }
            )

        }.withTop(
            8
        )

    private fun recentRow(
        row: RecentCallEntry
    ): View =
        card {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            addView(
                initialAvatar(
                    row.displayName
                        ?: "?"
                ).fixed(
                    46,
                    46
                )
            )

            addView(
                LinearLayout(
                    context
                ).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    addView(
                        title(
                            RecentCallLabels
                                .primary(
                                    row.displayName,
                                    row.number,
                                    row.presentation
                                ),
                            16
                        )
                    )

                    val label =
                        "${
                            row.type.name
                                .lowercase()
                                .replaceFirstChar {
                                    it.titlecase()
                                }
                        } · ${
                            DateFormat
                                .getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                )
                                .format(
                                    Date(
                                        row.timestamp
                                    )
                                )
                        }"

                    addView(
                        body(
                            label
                        ).apply {

                            if (
                                row.type ==
                                RecentFilter.MISSED
                            ) {

                                setTextColor(
                                    color(
                                        R.color
                                            .hn_error
                                    )
                                )
                            }
                        }
                    )

                }.weighted()
            )

            row.number
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { number ->

                    addView(
                        outlineButton(
                            "Call"
                        ) {

                            actions.callNumber(
                                number
                            )
                        }
                    )
                }

        }.withTop(
            8
        )

    private fun searchBar(
        hint: String,
        detail: String
    ): View =
        card {

            addView(
                title(
                    hint,
                    16
                )
            )

            addView(
                body(
                    detail
                ).withTop(
                    4
                )
            )
        }

    private fun emptyCard(
        headline: String,
        message: String,
        action: String? = null,
        listener: (() -> Unit)? = null
    ): View =
        card {

            gravity =
                Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(20),
                dp(22),
                dp(20),
                dp(22)
            )

            addView(
                title(
                    headline,
                    18,
                    Gravity.CENTER
                )
            )

            addView(
                body(
                    message,
                    Gravity.CENTER
                ).withTop(
                    6
                )
            )

            if (
                action !=
                null &&
                listener !=
                null
            ) {

                addView(
                    outlineButton(
                        action,
                        listener
                    ).withTop(
                        14
                    )
                )
            }
        }

    private fun permissionCard(
        headline: String,
        message: String,
        action: String,
        listener: () -> Unit
    ): View =
        card {

            addView(
                title(
                    headline,
                    18
                )
            )

            addView(
                body(
                    message
                ).withTop(
                    6
                )
            )

            addView(
                primaryButton(
                    action,
                    true,
                    listener
                ).withTop(
                    16
                )
            )
        }

    private fun scroll(
        content:
            (LinearLayout) -> Unit
    ) {

        val scroll =
            ScrollView(
                context
            ).apply {

                isFillViewport =
                    true

                clipToPadding =
                    false

                setPadding(
                    dp(16),
                    dp(8),
                    dp(16),
                    dp(18)
                )
            }

        val column =
            LinearLayout(
                context
            ).apply {

                orientation =
                    LinearLayout.VERTICAL
            }

        scroll.addView(
            column
        )

        content(
            column
        )

        binding.content
            .addView(
                scroll
            )
    }

    private fun card(
        content:
            LinearLayout.() -> Unit
    ): LinearLayout =
        LinearLayout(
            context
        ).apply {

            orientation =
                LinearLayout.VERTICAL

            background =
                drawable(
                    R.drawable.bg_surface
                )

            setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14)
            )

            content()
        }

    private fun sectionLabel(
        text: String
    ) =
        TextView(
            context
        ).apply {

            this.text =
                text

            textSize =
                11f

            typeface =
                Typeface.DEFAULT_BOLD

            setTextColor(
                color(
                    R.color
                        .hn_text_secondary
                )
            )

            setPadding(
                dp(4),
                dp(20),
                0,
                dp(6)
            )
        }

    private fun title(
        text: String,
        size: Int,
        gravity: Int = Gravity.START
    ) =
        TextView(
            context
        ).apply {

            this.text =
                text

            textSize =
                size.toFloat()

            this.gravity =
                gravity

            typeface =
                Typeface.create(
                    "sans",
                    Typeface.BOLD
                )

            maxLines =
                1

            ellipsize =
                android.text
                    .TextUtils
                    .TruncateAt
                    .END

            setTextColor(
                color(
                    R.color
                        .hn_text_primary
                )
            )
        }

    private fun body(
        text: String,
        gravity: Int = Gravity.START
    ) =
        TextView(
            context
        ).apply {

            this.text =
                text

            textSize =
                13f

            this.gravity =
                gravity

            setTextColor(
                color(
                    R.color
                        .hn_text_secondary
                )
            )

            maxLines =
                3

            ellipsize =
                android.text
                    .TextUtils
                    .TruncateAt
                    .END
        }

    private fun icon(
        resource: Int
    ) =
        ImageView(
            context
        ).apply {

            setImageResource(
                resource
            )

            contentDescription =
                null

            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )
        }

    private fun initialAvatar(
        label: String
    ) =
        TextView(
            context
        ).apply {

            text =
                label
                    .take(1)
                    .uppercase()

            gravity =
                Gravity.CENTER

            textSize =
                18f

            typeface =
                Typeface.DEFAULT_BOLD

            background =
                drawable(
                    R.drawable.bg_avatar
                )

            setTextColor(
                color(
                    R.color
                        .hn_text_primary
                )
            )
        }

    private fun keyButton(
        text: String,
        click: () -> Unit
    ) =
        Button(
            context
        ).apply {

            this.text =
                text

            textSize =
                22f

            setTextColor(
                color(
                    R.color
                        .hn_text_primary
                )
            )

            background =
                drawable(
                    R.drawable.bg_key
                )

            minWidth =
                0

            minimumWidth =
                0

            setOnClickListener {
                click()
            }
        }

    private fun primaryButton(
        text: String,
        enabled: Boolean = true,
        click: () -> Unit
    ) =
        Button(
            context
        ).apply {

            this.text =
                text

            isEnabled =
                enabled

            textSize =
                14f

            typeface =
                Typeface.DEFAULT_BOLD

            setTextColor(
                color(
                    if (
                        enabled
                    ) {
                        R.color
                            .hn_background_primary
                    } else {
                        R.color
                            .hn_text_disabled
                    }
                )
            )

            background =
                drawable(
                    if (
                        enabled
                    ) {
                        R.drawable
                            .bg_cyan_button
                    } else {
                        R.drawable
                            .bg_key
                    }
                )

            minHeight =
                dp(52)

            setOnClickListener {
                click()
            }
        }

    private fun outlineButton(
        text: String,
        click: () -> Unit
    ) =
        Button(
            context
        ).apply {

            this.text =
                text

            textSize =
                13f

            setTextColor(
                color(
                    R.color
                        .hn_text_primary
                )
            )

            background =
                drawable(
                    R.drawable
                        .bg_outline_button
                )

            minHeight =
                dp(48)

            minWidth =
                0

            setOnClickListener {
                click()
            }
        }

    private fun callControlButton(
        text: String,
        selected: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit
    ) =
        Button(
            context
        ).apply {

            this.text =
                text

            isEnabled =
                enabled

            textSize =
                12f

            typeface =
                Typeface.DEFAULT_BOLD

            setTextColor(
                color(
                    when {

                        !enabled ->
                            R.color
                                .hn_text_disabled

                        selected ->
                            R.color
                                .hn_primary_cyan

                        else ->
                            R.color
                                .hn_text_primary
                    }
                )
            )

            background =
                drawable(
                    if (
                        selected
                    ) {
                        R.drawable
                            .bg_surface_selected
                    } else {
                        R.drawable
                            .bg_outline_button
                    }
                )

            minHeight =
                dp(58)

            minWidth =
                0

            setOnClickListener {

                if (
                    enabled
                ) {
                    click()
                }
            }
        }

    private fun errorButton(
        text: String,
        click: () -> Unit
    ) =
        Button(
            context
        ).apply {

            this.text =
                text

            textSize =
                14f

            typeface =
                Typeface.DEFAULT_BOLD

            setTextColor(
                Color.WHITE
            )

            background =
                drawable(
                    R.drawable
                        .bg_error_button
                )

            minHeight =
                dp(60)

            setOnClickListener {
                click()
            }
        }

    private fun View.callWeight(
        endMargin: Int = 0
    ) =
        apply {

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        dp(58),
                        1f
                    ).apply {

                        marginEnd =
                            dp(
                                endMargin
                            )
                    }
        }

    private fun View.withTop(
        margin: Int
    ) =
        apply {

            layoutParams =
                (
                    layoutParams
                        ?: LinearLayout
                            .LayoutParams(
                                ViewGroup
                                    .LayoutParams
                                    .MATCH_PARENT,
                                ViewGroup
                                    .LayoutParams
                                    .WRAP_CONTENT
                            )
                    ).let {

                    it as LinearLayout
                        .LayoutParams

                }.apply {

                    topMargin =
                        dp(
                            margin
                        )
                }
        }

    private fun View.fixed(
        width: Int,
        height: Int
    ) =
        apply {

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        dp(width),
                        dp(height)
                    ).apply {

                        marginEnd =
                            dp(12)
                    }
        }

    private fun View.weighted() =
        apply {

            layoutParams =
                LinearLayout
                    .LayoutParams(
                        0,
                        ViewGroup
                            .LayoutParams
                            .WRAP_CONTENT,
                        1f
                    ).apply {

                        marginEnd =
                            dp(10)
                    }
        }

    private fun elapsed(
        started: Long?,
        now: Long
    ): String {

        if (
            started ==
            null
        ) {
            return "Active call"
        }

        val seconds =
            (
                now -
                    started
                ) / 1000

        return "%02d:%02d"
            .format(
                seconds / 60,
                seconds % 60
            )
    }

    private val context:
        Context
        get() =
            binding.root.context

    private fun dp(
        value: Int
    ) =
        (
            value *
                context.resources
                    .displayMetrics
                    .density
            ).toInt()

    private fun color(
        resource: Int
    ) =
        ContextCompat
            .getColor(
                context,
                resource
            )

    private fun drawable(
        resource: Int
    ) =
        ContextCompat
            .getDrawable(
                context,
                resource
            )!!
}
