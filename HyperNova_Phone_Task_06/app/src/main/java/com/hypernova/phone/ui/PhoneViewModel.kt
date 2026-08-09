package com.hypernova.phone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hypernova.phone.data.PhoneRepository
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.PhoneScreen
import com.hypernova.phone.domain.PhoneUiState
import com.hypernova.phone.domain.RecentFilter
import com.hypernova.phone.telecom.TelecomCallController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PhoneViewModel(
    private val repository: PhoneRepository,
    private val telecom: TelecomCallController
) : ViewModel() {

    private val screen =
        MutableStateFlow(
            PhoneScreen.HOME
        )

    private val number =
        MutableStateFlow("")

    private val filter =
        MutableStateFlow(
            RecentFilter.ALL
        )

    private val contactSearchVisible =
        MutableStateFlow(false)

    private val inCallKeypadVisible =
        MutableStateFlow(false)

    private val inCallDtmfDigits =
        MutableStateFlow("")

    private val nowMillis =
        MutableStateFlow(
            System.currentTimeMillis()
        )

    private var activeTimer:
        Job? = null

    private val baseUiState =
        combine(
            repository.state,
            screen,
            number,
            filter,
            contactSearchVisible
        ) {
                data,
                selectedScreen,
                dialed,
                recentFilter,
                searching ->

            /*
             * A real Telecom call always owns the presentation.
             */
            val resolvedScreen =
                when (
                    data.call.status
                ) {
                    CallStatus.DIALING,
                    CallStatus.RINGING,
                    CallStatus.INCOMING,
                    CallStatus.ACTIVE,
                    CallStatus.MUTED,
                    CallStatus.HELD -> {
                        PhoneScreen.CALL
                    }

                    else -> {
                        selectedScreen
                    }
                }

            PhoneUiState(
                screen =
                    resolvedScreen,

                data =
                    data,

                dialedNumber =
                    dialed,

                recentFilter =
                    recentFilter,

                isContactSearchVisible =
                    searching
            )
        }

    private val callUiState =
        combine(
            baseUiState,
            inCallKeypadVisible,
            inCallDtmfDigits
        ) {
                state,
                keypadVisible,
                dtmfDigits ->

            state.copy(
                isInCallKeypadVisible =
                    keypadVisible,

                inCallDtmfDigits =
                    dtmfDigits
            )
        }

    val uiState:
        StateFlow<PhoneUiState> =
        combine(
            callUiState,
            nowMillis
        ) { state, now ->

            state.copy(
                nowMillis =
                    now
            )

        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(
                5_000
            ),
            PhoneUiState(
                data =
                    repository.state.value
            )
        )

    init {

        viewModelScope.launch {

            repository.state
                .collect { data ->

                    if (
                        data.call.status ==
                        CallStatus.ACTIVE &&
                        data.call.startedAtMillis !=
                        null
                    ) {

                        if (
                            activeTimer?.isActive !=
                            true
                        ) {

                            activeTimer =
                                launch {

                                    while (true) {

                                        nowMillis.value =
                                            System.currentTimeMillis()

                                        delay(
                                            1_000
                                        )
                                    }
                                }
                        }

                    } else {

                        activeTimer
                            ?.cancel()

                        activeTimer =
                            null
                    }

                    /*
                     * Never carry DTMF UI from one call into another.
                     */
                    if (
                        data.call.status in
                        setOf(
                            CallStatus.IDLE,
                            CallStatus.INCOMING,
                            CallStatus.CALL_ENDED,
                            CallStatus.MISSED,
                            CallStatus.REJECTED,
                            CallStatus.FAILED
                        )
                    ) {

                        inCallKeypadVisible.value =
                            false

                        inCallDtmfDigits.value =
                            ""
                    }
                }
        }
    }

    fun start() {
        repository.start()
    }

    fun stop() {
        repository.stop()
    }

    fun navigate(
        destination: PhoneScreen
    ) {

        if (
            screen.value !=
            destination
        ) {
            screen.value =
                destination
        }

        when (
            destination
        ) {

            PhoneScreen.CONTACTS ->
                repository
                    .ensureContactsLoaded()

            PhoneScreen.RECENTS ->
                repository
                    .ensureRecentsLoaded()

            else ->
                Unit
        }
    }

    fun appendDigit(
        digit: String
    ) {

        if (
            number.value.length <
            32
        ) {
            number.value +=
                digit
        }
    }

    fun deleteDigit() {
        number.value =
            number.value
                .dropLast(1)
    }

    fun selectRecentFilter(
        value: RecentFilter
    ) {
        filter.value =
            value
    }

    fun toggleContactSearch() {
        contactSearchVisible.value =
            !contactSearchVisible.value
    }

    fun onCapabilityChanged() {

        repository
            .refreshCapabilities()

        when (
            screen.value
        ) {

            PhoneScreen.CONTACTS ->
                repository
                    .ensureContactsLoaded()

            PhoneScreen.RECENTS ->
                repository
                    .ensureRecentsLoaded()

            else ->
                Unit
        }
    }

    fun placeCall():
        TelecomCallController.CommandResult =
        telecom.placeCall(
            number.value
        )

    fun callNumber(
        number: String
    ): TelecomCallController.CommandResult =
        telecom.placeCall(
            number
        )

    fun answer() =
        telecom.answer()

    fun decline() =
        telecom.decline()

    fun endCall() =
        telecom.disconnect()

    fun holdOrResume(
        held: Boolean
    ) =
        if (held) {
            telecom.unhold()
        } else {
            telecom.hold()
        }

    fun toggleMute() =
        telecom.toggleMute()

    fun toggleSpeaker() =
        telecom.toggleSpeaker()

    fun selectAudioRoute(
        route: Int
    ) =
        telecom.selectAudioRoute(
            route
        )

    /**
     * Show/hide the DTMF keypad for a real active/held call.
     */
    fun toggleInCallKeypad() {

        if (
            repository.state.value
                .call.status !in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD
            )
        ) {
            return
        }

        inCallKeypadVisible.value =
            !inCallKeypadVisible.value
    }

    /**
     * Used when Android Telecom itself asks for the Dialpad.
     */
    fun setInCallKeypadVisible(
        visible: Boolean
    ) {

        if (
            !visible
        ) {
            inCallKeypadVisible.value =
                false

            return
        }

        if (
            repository.state.value
                .call.status in
            setOf(
                CallStatus.ACTIVE,
                CallStatus.HELD
            )
        ) {
            inCallKeypadVisible.value =
                true
        }
    }

    /**
     * Send one real DTMF command and update only the UI history after
     * Telecom accepted the command.
     */
    fun sendDtmf(
        digit: String
    ): TelecomCallController.CommandResult {

        val result =
            telecom.sendDtmf(
                digit
            )

        if (
            result ==
            TelecomCallController
                .CommandResult
                .Dispatched
        ) {

            if (
                inCallDtmfDigits.value.length <
                MAX_DTMF_DISPLAY_LENGTH
            ) {
                inCallDtmfDigits.value +=
                    digit
            }
        }

        return result
    }

    override fun onCleared() {

        activeTimer
            ?.cancel()

        repository.stop()

        super.onCleared()
    }

    class Factory(
        private val repository: PhoneRepository,
        private val telecom: TelecomCallController
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            return PhoneViewModel(
                repository,
                telecom
            ) as T
        }
    }

    private companion object {

        const val MAX_DTMF_DISPLAY_LENGTH =
            32
    }
}
