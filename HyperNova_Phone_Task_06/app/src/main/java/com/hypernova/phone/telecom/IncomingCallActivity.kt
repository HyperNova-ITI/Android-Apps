package com.hypernova.phone.telecom

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.hypernova.phone.MainActivity
import com.hypernova.phone.domain.CallStatus
import com.hypernova.phone.domain.TelecomCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Compact HyperNova incoming-call bar.
 *
 * The current IVI app remains visible behind this small top window.
 * Android Telecom remains the call-state authority.
 */
class IncomingCallActivity : Activity() {

    private val uiScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private lateinit var telecom:
        TelecomCallController

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

    private var phoneOpened =
        false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

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

        val content =
            buildContent()

        setContentView(content)
        configureFloatingWindow()
        render(telecom.state.value)
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
                render(state)

                when (state.status) {
                    CallStatus.INCOMING,
                    CallStatus.RINGING -> {
                        hasSeenIncoming = true
                    }

                    CallStatus.ACTIVE,
                    CallStatus.MUTED,
                    CallStatus.HELD -> {
                        openPhoneForActiveCall()
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
        val number =
            state.number
                ?.trim()
                .orEmpty()

        val resolvedName =
            CallerIdentityResolver.resolve(
                context = applicationContext,
                telecomDisplayName = state.displayName,
                number = number
            )

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

    private fun openPhoneForActiveCall() {
        if (phoneOpened) {
            return
        }

        phoneOpened =
            true

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                action =
                    ACTION_OPEN_PHONE

                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    HyperNovaInCallService.EXTRA_SHOW_IN_CALL_DIALPAD,
                    false
                )
            }

        try {
            startActivity(intent)
            finish()
        } catch (
            exception:
                RuntimeException
        ) {
            phoneOpened =
                false
            restoreButtons()
            callLabel.text =
                "CALL CONNECTED"
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

    @Deprecated(
        "Incoming call must be answered or declined explicitly."
    )
    override fun onBackPressed() {
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ACTION_OPEN_PHONE =
            "com.hypernova.phone.action.OPEN"

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
