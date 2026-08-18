package com.hypernova.ai

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hypernova.ai.databinding.ActivityNovaBinding
import com.hypernova.ai.runtime.NovaEvidenceCard
import com.hypernova.ai.runtime.NovaRuntimeService
import com.hypernova.ai.ui.NovaUiState
import com.hypernova.ai.ui.NovaViewModel
import com.hypernova.ai.ui.NovaVisibleState
import com.hypernova.visuals.CockpitNavigationController

class NovaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNovaBinding
    private val viewModel: NovaViewModel by viewModels()
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var followUpDeadlineElapsedRealtimeMs: Long? = null
    private val followUpCountdownTick = object : Runnable {
        override fun run() {
            val deadline = followUpDeadlineElapsedRealtimeMs ?: return
            val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val remainingSeconds = (remainingMs + 999L) / 1_000L
            binding.textSecondaryMessage.text = if (remainingMs > 0L) {
                "Continue speaking · ${remainingSeconds}s"
            } else {
                "Listening window closed"
            }
            if (remainingMs > 0L) {
                countdownHandler.postDelayed(this, minOf(remainingMs, 250L))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityNovaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CockpitNavigationController.bind(
            binding.cockpitNavigation,
            CockpitNavigationController.Destination.NOVA,
        )
        configureFullScreenMode()
        applySystemBarInsets()

        viewModel.uiState.observe(this, ::render)
        binding.buttonBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.buttonSecondary.setOnClickListener { startRuntime(reconnect = true) }
        startRuntime(reconnect = false)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
    }

    private fun configureFullScreenMode() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        configureFullScreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureFullScreenMode()
    }

    private fun startRuntime(reconnect: Boolean = false) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, NovaRuntimeService::class.java).apply {
                if (reconnect) action = NovaRuntimeService.ACTION_RECONNECT
            },
        )
    }

    private fun render(state: NovaUiState) = with(binding) {
        textStatus.text = state.visibleState.statusLabel()
        textGreetingSubtitle.setText(state.visibleState.subtitleResource())
        textVoiceHint.setText(state.visibleState.voiceHintResource())
        textStateEyebrow.text = state.eyebrow
        textPrimaryMessage.text = state.primaryMessage
        textSecondaryMessage.text = state.secondaryMessage.orEmpty()
        textSecondaryMessage.visibility = if (state.secondaryMessage == null) View.GONE else View.VISIBLE
        renderFollowUpCountdown(state.followUpDeadlineElapsedRealtimeMs)

        val statusColor = ContextCompat.getColor(this@NovaActivity, state.visibleState.colorResource())
        textStatus.setTextColor(statusColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        textStateEyebrow.setTextColor(statusColor)
        stateProgress.setIndicatorColor(statusColor)
        stateProgress.visibility = if (state.showActivityProgress) View.VISIBLE else View.GONE
        renderEvidence(state.evidenceCards)

        val unavailable = state.visibleState == NovaVisibleState.UNAVAILABLE
        novaFace.alpha = if (unavailable) 0.44f else 1f
        novaFace.setPalette(
            accent = ContextCompat.getColor(this@NovaActivity, R.color.hypernova_cyan),
            secondaryAccent = ContextCompat.getColor(this@NovaActivity, R.color.hypernova_purple),
            success = ContextCompat.getColor(this@NovaActivity, R.color.hypernova_success),
            warning = ContextCompat.getColor(this@NovaActivity, R.color.hypernova_warning),
            error = ContextCompat.getColor(this@NovaActivity, R.color.hypernova_error),
        )
        novaFace.setStateName(state.visibleState.name)

        buttonSecondary.visibility = if (unavailable) View.VISIBLE else View.GONE
        buttonSecondary.isEnabled = unavailable
    }

    private fun renderEvidence(cards: List<NovaEvidenceCard>) = with(binding) {
        evidenceContainer.removeAllViews()
        evidenceScroller.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        cards.take(4).forEach { card ->
            val view = layoutInflater.inflate(
                R.layout.item_nova_evidence,
                evidenceContainer,
                false,
            )
            view.findViewById<TextView>(R.id.textEvidenceTitle).text =
                "${card.index}. ${card.title}"
            view.findViewById<TextView>(R.id.textEvidenceDetail).apply {
                text = card.detail.orEmpty()
                visibility = if (card.detail.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.textEvidenceSource).text = card.source
            card.sourceUri?.let { uri ->
                view.isClickable = true
                view.isFocusable = true
                view.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                    }
                }
            }
            evidenceContainer.addView(view)
        }
    }

    private fun renderFollowUpCountdown(deadlineElapsedRealtimeMs: Long?) {
        countdownHandler.removeCallbacks(followUpCountdownTick)
        followUpDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs
        if (deadlineElapsedRealtimeMs != null) {
            binding.textSecondaryMessage.visibility = View.VISIBLE
            followUpCountdownTick.run()
        }
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(followUpCountdownTick)
        super.onDestroy()
    }

    private fun NovaVisibleState.statusLabel(): String = when (this) {
        NovaVisibleState.IDLE -> "READY"
        NovaVisibleState.PROCESSING -> "UNDERSTANDING"
        NovaVisibleState.EXECUTING -> "ACTING"
        NovaVisibleState.SPEAKING -> "RESPONDING"
        NovaVisibleState.SUCCESS -> "COMPLETED"
        NovaVisibleState.ERROR -> "NEEDS ATTENTION"
        else -> name
    }

    private fun NovaVisibleState.subtitleResource(): Int = when (this) {
        NovaVisibleState.IDLE -> R.string.ready_subtitle
        NovaVisibleState.LISTENING -> R.string.listening_subtitle
        NovaVisibleState.PROCESSING -> R.string.processing_subtitle
        NovaVisibleState.EXECUTING -> R.string.executing_subtitle
        NovaVisibleState.SUCCESS -> R.string.success_subtitle
        NovaVisibleState.ERROR -> R.string.error_subtitle
        NovaVisibleState.SPEAKING -> R.string.speaking_subtitle
        NovaVisibleState.UNAVAILABLE -> R.string.unavailable_subtitle
    }

    private fun NovaVisibleState.colorResource(): Int = when (this) {
        NovaVisibleState.SUCCESS -> R.color.hypernova_success
        NovaVisibleState.ERROR -> R.color.hypernova_error
        NovaVisibleState.UNAVAILABLE -> R.color.hypernova_warning
        else -> R.color.hypernova_cyan
    }

    private fun NovaVisibleState.voiceHintResource(): Int = when (this) {
        NovaVisibleState.IDLE -> R.string.idle_voice_hint
        NovaVisibleState.LISTENING -> R.string.listening_voice_hint
        NovaVisibleState.PROCESSING -> R.string.processing_voice_hint
        NovaVisibleState.EXECUTING -> R.string.executing_voice_hint
        NovaVisibleState.SUCCESS -> R.string.success_voice_hint
        NovaVisibleState.ERROR -> R.string.error_voice_hint
        NovaVisibleState.SPEAKING -> R.string.speaking_voice_hint
        NovaVisibleState.UNAVAILABLE -> R.string.unavailable_voice_hint
    }
}
