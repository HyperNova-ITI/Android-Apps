package com.hypernova.phone.telecom

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.hypernova.phone.contacts.CallerIdentity
import com.hypernova.phone.contacts.CallerIdentityFallbacks
import com.hypernova.phone.contacts.ContactsRepository
import com.hypernova.phone.contacts.PhoneNumberMatching
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.TelecomCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Compact HyperNova incoming-call bar.
 *
 * The current IVI app remains visible behind this small top window.
 * Android Telecom remains the call-state authority.
 */
class IncomingCallActivity : ComponentActivity() {

    private val uiScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private lateinit var telecom:
        TelecomCallController

    private lateinit var contactsRepository:
        ContactsRepository

    private lateinit var callerName:
        TextView

    private lateinit var callerNumber:
        TextView

    private lateinit var callLabel:
        TextView

    private lateinit var avatar:
        TextView

    private lateinit var answerButton:
        TextView

    private lateinit var declineButton:
        TextView

    private var hasSeenIncoming =
        false

    private var latestCallState =
        TelecomCallState()

    private var resolvedIdentityNumber:
        String? = null

    private var resolvedIdentity:
        CallerIdentity? = null

    private var identityLookupNumber:
        String? = null

    private var identityLookupJob:
        Job? = null

    private var providerRefreshJob:
        Job? = null

    private var contactsObserverRegistered =
        false

    private var callLogObserverRegistered =
        false

    private val providerObserver =
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

                providerRefreshJob
                    ?.cancel()

                providerRefreshJob =
                    uiScope.launch {
                        delay(
                            PROVIDER_REFRESH_DEBOUNCE_MILLIS
                        )

                        requestCallerIdentity(
                            latestCallState,
                            force = true
                        )
                    }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            }
        )

        actionBar?.hide()
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        window.setBackgroundDrawable(
            ColorDrawable(Color.TRANSPARENT)
        )
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        telecom =
            TelecomCallController(applicationContext)

        contactsRepository =
            ContactsRepository(applicationContext)

        val content =
            buildContent()

        setContentView(content)
        configureFloatingWindow()
        latestCallState =
            telecom.state.value
        render(latestCallState)
        registerProviderObservers()
        observeCall()
        animateBarIn(content)
    }

    private fun configureFloatingWindow() {
        val attributes =
            window.attributes

        attributes.gravity =
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        attributes.width =
            ViewGroup.LayoutParams.MATCH_PARENT
        attributes.height =
            ViewGroup.LayoutParams.WRAP_CONTENT
        attributes.y =
            dp(18)

        window.attributes =
            attributes

        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun observeCall() {
        uiScope.launch {
            telecom.state.collectLatest { state ->
                latestCallState =
                    state

                render(state)

                when (state.status) {
                    CallStatus.INCOMING,
                    CallStatus.RINGING -> {
                        hasSeenIncoming = true
                    }

                    CallStatus.ACTIVE,
                    CallStatus.MUTED,
                    CallStatus.HELD -> {
                        /*
                         * HyperNovaInCallService owns the single full-screen
                         * launch after Telecom confirms ACTIVE.
                         */
                        finish()
                    }

                    CallStatus.CALL_ENDED,
                    CallStatus.MISSED,
                    CallStatus.REJECTED,
                    CallStatus.FAILED -> {
                        finish()
                    }

                    CallStatus.IDLE -> {
                        if (hasSeenIncoming) {
                            finish()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun render(
        state: TelecomCallState
    ) {
        renderPresentation(
            state
        )

        requestCallerIdentity(
            state,
            force = false
        )
    }

    private fun renderPresentation(
        state: TelecomCallState
    ) {
        val number =
            PhoneNumberMatching
                .normalize(
                    state.number
                )
                .orEmpty()

        val telecomName =
            CallerIdentityFallbacks
                .meaningfulName(
                    state.displayName,
                    number
                )

        val providerName =
            if (
                resolvedIdentityNumber != null &&
                PhoneNumberMatching.sameNumber(
                    resolvedIdentityNumber,
                    number
                )
            ) {
                resolvedIdentity
                    ?.displayName
                    ?.takeUnless {
                        PhoneNumberMatching.sameNumber(
                            it,
                            number
                        )
                    }
            } else {
                null
            }

        val resolvedName =
            telecomName
                ?: providerName

        callerName.text =
            resolvedName
                ?: number.takeIf {
                    it.isNotBlank()
                }
                ?: "Unknown caller"

        callerNumber.text =
            if (
                resolvedName != null &&
                number.isNotBlank()
            ) {
                number
            } else {
                "Bluetooth hands-free"
            }

        avatar.text =
            resolvedName
                ?.trim()
                ?.firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                ?: "☎"

        callLabel.text =
            when (state.status) {
                CallStatus.INCOMING,
                CallStatus.RINGING ->
                    "INCOMING CALL"

                CallStatus.ACTIVE,
                CallStatus.MUTED ->
                    "CALL CONNECTED"

                CallStatus.HELD ->
                    "CALL ON HOLD"

                else ->
                    "PHONE"
            }
    }

    /**
     * Provider work never runs in the Telecom/main-thread callback.
     * ContactsRepository performs PhoneLookup, a bounded contacts scan,
     * CallLog fallback, and raw-number fallback on Dispatchers.IO.
     */
    private fun requestCallerIdentity(
        state: TelecomCallState,
        force: Boolean
    ) {
        if (
            state.status !in
            setOf(
                CallStatus.INCOMING,
                CallStatus.RINGING
            )
        ) {
            identityLookupJob
                ?.cancel()

            identityLookupNumber =
                null
            resolvedIdentityNumber =
                null
            resolvedIdentity =
                null

            return
        }

        val number =
            PhoneNumberMatching
                .normalize(
                    state.number
                )
                ?.takeIf {
                    PhoneNumberMatching.isUsableNumber(
                        it
                    )
                }
                ?: return

        if (
            CallerIdentityFallbacks
                .meaningfulName(
                    state.displayName,
                    number
                ) != null
        ) {
            return
        }

        if (
            !force &&
            identityLookupNumber != null &&
            PhoneNumberMatching.sameNumber(
                identityLookupNumber,
                number
            )
        ) {
            return
        }

        identityLookupNumber =
            number

        if (
            resolvedIdentityNumber == null ||
            !PhoneNumberMatching.sameNumber(
                resolvedIdentityNumber,
                number
            )
        ) {
            resolvedIdentityNumber =
                null
            resolvedIdentity =
                null
        }

        identityLookupJob
            ?.cancel()

        identityLookupJob =
            uiScope.launch {
                val identity =
                    contactsRepository
                        .resolveCallerIdentity(
                            number
                        )

                val current =
                    latestCallState

                if (
                    current.status !in
                    setOf(
                        CallStatus.INCOMING,
                        CallStatus.RINGING
                    ) ||
                    !PhoneNumberMatching.sameNumber(
                        current.number,
                        number
                    )
                ) {
                    return@launch
                }

                resolvedIdentityNumber =
                    number
                resolvedIdentity =
                    identity

                renderPresentation(
                    current
                )
            }
    }

    private fun registerProviderObservers() {
        if (
            hasPermission(
                Manifest.permission.READ_CONTACTS
            )
        ) {
            try {
                contentResolver
                    .registerContentObserver(
                        ContactsContract.AUTHORITY_URI,
                        true,
                        providerObserver
                    )

                contactsObserverRegistered =
                    true
            } catch (_: SecurityException) {
            }
        }

        if (
            hasPermission(
                Manifest.permission.READ_CALL_LOG
            )
        ) {
            try {
                contentResolver
                    .registerContentObserver(
                        CallLog.Calls.CONTENT_URI,
                        true,
                        providerObserver
                    )

                callLogObserverRegistered =
                    true
            } catch (_: SecurityException) {
            }
        }
    }

    private fun unregisterProviderObservers() {
        if (
            contactsObserverRegistered ||
            callLogObserverRegistered
        ) {
            runCatching {
                contentResolver
                    .unregisterContentObserver(
                        providerObserver
                    )
            }
        }

        contactsObserverRegistered =
            false
        callLogObserverRegistered =
            false
    }

    private fun hasPermission(
        permission: String
    ): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            permission
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildContent(): View {
        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16),
                    dp(6),
                    dp(16),
                    dp(6)
                )

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }

        val bar =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(18),
                    dp(14),
                    dp(16),
                    dp(14)
                )

                background =
                    roundedDrawable(
                        fillColor = COLOR_BAR,
                        strokeColor = COLOR_BORDER,
                        radiusDp = 24
                    )

                elevation =
                    dp(12).toFloat()
            }

        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        avatar =
            TextView(this).apply {
                gravity =
                    Gravity.CENTER
                text =
                    "☎"
                textSize =
                    24f
                typeface =
                    Typeface.DEFAULT_BOLD

                setTextColor(
                    COLOR_CYAN
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL
                        setColor(
                            COLOR_AVATAR
                        )
                        setStroke(
                            dp(2),
                            COLOR_CYAN
                        )
                    }
            }

        bar.addView(
            avatar,
            LinearLayout.LayoutParams(
                dp(64),
                dp(64)
            )
        )

        val identity =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(18),
                    0,
                    dp(14),
                    0
                )
            }

        bar.addView(
            identity,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        callLabel =
            TextView(this).apply {
                text =
                    "INCOMING CALL"
                textSize =
                    11f
                typeface =
                    Typeface.DEFAULT_BOLD
                letterSpacing =
                    0.13f
                setTextColor(
                    COLOR_CYAN
                )
            }

        identity.addView(callLabel)

        callerName =
            TextView(this).apply {
                text =
                    "Unknown caller"
                textSize =
                    22f
                typeface =
                    Typeface.DEFAULT_BOLD
                setTextColor(
                    COLOR_PRIMARY
                )
                maxLines =
                    1
                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

        identity.addView(callerName)

        callerNumber =
            TextView(this).apply {
                textSize =
                    13f
                setTextColor(
                    COLOR_SECONDARY
                )
                maxLines =
                    1
                setPadding(
                    0,
                    dp(2),
                    0,
                    0
                )
            }

        identity.addView(callerNumber)

        declineButton =
            createButton(
                label = "DECLINE",
                fill = COLOR_DECLINE,
                border = COLOR_DECLINE_BORDER,
                textColor = COLOR_PRIMARY
            )

        bar.addView(
            declineButton,
            LinearLayout.LayoutParams(
                dp(112),
                dp(58)
            ).apply {
                rightMargin =
                    dp(10)
            }
        )

        answerButton =
            createButton(
                label = "ANSWER",
                fill = COLOR_CYAN,
                border = COLOR_CYAN,
                textColor = COLOR_ANSWER_TEXT
            )

        bar.addView(
            answerButton,
            LinearLayout.LayoutParams(
                dp(112),
                dp(58)
            )
        )

        declineButton.setOnClickListener {
            when (
                val result =
                    telecom.decline()
            ) {
                TelecomCallController.CommandResult.Dispatched -> {
                    setPending(
                        "DECLINING…"
                    )
                }

                is TelecomCallController.CommandResult.Rejected -> {
                    restoreButtons()
                    callerNumber.text =
                        result.reason
                }
            }
        }

        answerButton.setOnClickListener {
            when (
                val result =
                    telecom.answer()
            ) {
                TelecomCallController.CommandResult.Dispatched -> {
                    /*
                     * Wait for Telecom to confirm ACTIVE.
                     */
                    setPending(
                        "CONNECTING…"
                    )
                }

                is TelecomCallController.CommandResult.Rejected -> {
                    restoreButtons()
                    callerNumber.text =
                        result.reason
                }
            }
        }

        return root
    }

    private fun createButton(
        label: String,
        fill: Int,
        border: Int,
        textColor: Int
    ): TextView {
        return TextView(this).apply {
            text =
                label
            gravity =
                Gravity.CENTER
            textSize =
                13f
            typeface =
                Typeface.DEFAULT_BOLD
            letterSpacing =
                0.06f
            setTextColor(
                textColor
            )
            isClickable =
                true
            isFocusable =
                true
            background =
                roundedDrawable(
                    fillColor = fill,
                    strokeColor = border,
                    radiusDp = 18
                )
        }
    }

    private fun setPending(
        label: String
    ) {
        callLabel.text =
            label
        answerButton.isEnabled =
            false
        declineButton.isEnabled =
            false
        answerButton.alpha =
            0.55f
        declineButton.alpha =
            0.55f
    }

    private fun restoreButtons() {
        answerButton.isEnabled =
            true
        declineButton.isEnabled =
            true
        answerButton.alpha =
            1f
        declineButton.alpha =
            1f
    }

    private fun animateBarIn(
        content: View
    ) {
        content.post {
            content.translationY =
                -content.height.toFloat() -
                    dp(20)
            content.alpha =
                0f

            content.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .start()
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius =
                dp(radiusDp).toFloat()
            setStroke(
                dp(1),
                strokeColor
            )
        }
    }

    private fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    override fun onDestroy() {
        unregisterProviderObservers()
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PROVIDER_REFRESH_DEBOUNCE_MILLIS =
            450L

        val COLOR_BAR =
            Color.parseColor(
                "#071520"
            )

        val COLOR_BORDER =
            Color.parseColor(
                "#1B4352"
            )

        val COLOR_AVATAR =
            Color.parseColor(
                "#092633"
            )

        val COLOR_CYAN =
            Color.parseColor(
                "#35E6F4"
            )

        val COLOR_PRIMARY =
            Color.parseColor(
                "#F4FAFC"
            )

        val COLOR_SECONDARY =
            Color.parseColor(
                "#8FA8B5"
            )

        val COLOR_DECLINE =
            Color.parseColor(
                "#35131B"
            )

        val COLOR_DECLINE_BORDER =
            Color.parseColor(
                "#E85C6A"
            )

        val COLOR_ANSWER_TEXT =
            Color.parseColor(
                "#031217"
            )
    }
}
