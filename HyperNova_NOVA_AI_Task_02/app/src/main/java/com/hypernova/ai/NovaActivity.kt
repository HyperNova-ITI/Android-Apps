package com.hypernova.ai

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hypernova.ai.databinding.ActivityNovaBinding
import com.hypernova.ai.runtime.NovaEvidenceCard
import com.hypernova.ai.runtime.NovaMessage
import com.hypernova.ai.runtime.NovaMessageRole
import com.hypernova.ai.runtime.NovaMessageTone
import com.hypernova.ai.runtime.NovaRuntimeService
import com.hypernova.ai.runtime.NovaRuntimeState
import com.hypernova.ai.ui.NovaUiState
import com.hypernova.ai.ui.NovaViewModel
import com.hypernova.ai.ui.NovaVisibleState
import com.hypernova.visuals.CockpitNavigationController
import com.hypernova.visuals.NovaFaceView

class NovaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNovaBinding
    private val viewModel: NovaViewModel by viewModels()
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var followUpDeadlineElapsedRealtimeMs: Long? = null
    private var muted = false
    private var renderedMessageCount = 0

    private val followUpCountdownTick = object : Runnable {
        override fun run() {
            val deadline = followUpDeadlineElapsedRealtimeMs ?: return
            val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remainingMs <= 0L) {
                followUpDeadlineElapsedRealtimeMs = null
                renderSessionHint()
                return
            }
            val remainingSeconds = ((remainingMs + 999L) / 1_000L).toInt()
            binding.textSessionHint.text =
                getString(R.string.follow_up_countdown, remainingSeconds)
            // The label changes once per second. Wake exactly at the next displayed boundary
            // instead of invalidating this Activity four times per second throughout the window.
            val untilNextSecond = remainingMs % 1_000L
            val delay = if (untilNextSecond == 0L) 1_000L else untilNextSecond
            countdownHandler.postDelayed(this, minOf(remainingMs, delay))
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
        NovaRuntimeState.conversation.observe(this, ::renderConversation)
        NovaRuntimeState.muted.observe(this, ::renderMuted)

        binding.buttonBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.buttonClearMemory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_memory)
                .setMessage(R.string.clear_memory_confirmation)
                .setNegativeButton(R.string.keep_memory, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    sendRuntimeAction(NovaRuntimeService.ACTION_RESET_MEMORY)
                }
                .show()
        }
        binding.buttonSecondary.setOnClickListener { startRuntime(reconnect = true) }
        binding.buttonCancel.setOnClickListener { sendRuntimeAction(NovaRuntimeService.ACTION_CANCEL) }
        binding.buttonMute.setOnClickListener {
            sendRuntimeAction(NovaRuntimeService.ACTION_SET_MUTED) {
                putExtra(NovaRuntimeService.EXTRA_MUTED, !muted)
            }
        }

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
        if (reconnect) {
            sendRuntimeAction(NovaRuntimeService.ACTION_RECONNECT)
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NovaRuntimeService::class.java),
            )
        }
    }

    private fun sendRuntimeAction(action: String, configure: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, NovaRuntimeService::class.java).apply {
                this.action = action
                configure()
            },
        )
    }

    private fun render(state: NovaUiState) = with(binding) {
        val unavailable = state.visibleState == NovaVisibleState.UNAVAILABLE

        textStatus.text = state.visibleState.statusLabel()
        val statusColor = ContextCompat.getColor(this@NovaActivity, state.visibleState.colorResource())
        textStatus.setTextColor(statusColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)

        applyFacePalette(novaFace)
        applyFacePalette(novaFaceHero)
        novaFace.alpha = if (unavailable) 0.44f else 1f
        novaFaceHero.alpha = novaFace.alpha
        novaFace.setStateName(state.visibleState.name)
        novaFaceHero.setStateName(state.visibleState.name)

        textConversationEmpty.setText(
            if (unavailable) {
                R.string.conversation_empty_unavailable
            } else {
                R.string.conversation_empty_ready
            },
        )

        // Offline, the only useful control is reconnecting; the rest would do nothing.
        buttonSecondary.visibility = if (unavailable) View.VISIBLE else View.GONE
        buttonClearMemory.visibility = if (unavailable) View.GONE else View.VISIBLE
        buttonMute.visibility = if (unavailable) View.GONE else View.VISIBLE
        buttonCancel.visibility = if (unavailable) View.GONE else View.VISIBLE
        buttonCancel.isEnabled = state.canCancel

        renderFollowUpCountdown(state.followUpDeadlineElapsedRealtimeMs)
    }

    private fun applyFacePalette(view: NovaFaceView) {
        view.setPalette(
            accent = ContextCompat.getColor(this, R.color.hypernova_cyan),
            secondaryAccent = ContextCompat.getColor(this, R.color.hypernova_purple),
            success = ContextCompat.getColor(this, R.color.hypernova_success),
            warning = ContextCompat.getColor(this, R.color.hypernova_warning),
            error = ContextCompat.getColor(this, R.color.hypernova_error),
        )
    }

    private fun renderMuted(value: Boolean) {
        muted = value
        binding.buttonMute.setText(if (value) R.string.unmute else R.string.mute)
        binding.buttonMute.contentDescription = getString(
            if (value) R.string.unmute_description else R.string.mute_description,
        )
        renderSessionHint()
    }

    /**
     * The hint slot carries one thing at a time, in priority order: an open follow-up window is
     * time-critical and is written by the countdown itself, a mute is a state the driver needs
     * reminding of, and otherwise the bar stays empty rather than restating the status chip.
     */
    private fun renderSessionHint() {
        if (followUpDeadlineElapsedRealtimeMs != null) return
        binding.textSessionHint.text = if (muted) getString(R.string.muted_hint) else ""
    }

    private fun renderConversation(messages: List<NovaMessage>) {
        val container = binding.conversationContainer
        val empty = messages.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.conversationScroller.visibility = if (empty) View.INVISIBLE else View.VISIBLE

        // Rows are rebound in place rather than rebuilt: a single turn is reported many times over
        // as the transcript, progress, action and reply arrive, and re-inflating the whole history
        // on each of those would drop frames while NOVA is speaking.
        messages.forEachIndexed { index, message ->
            val row = container.getChildAt(index) ?: layoutInflater
                .inflate(R.layout.item_nova_message, container, false)
                .also(container::addView)
            bindMessage(row as ViewGroup, message)
        }
        while (container.childCount > messages.size) {
            container.removeViewAt(container.childCount - 1)
        }

        if (messages.size != renderedMessageCount) {
            renderedMessageCount = messages.size
            binding.conversationScroller.post {
                binding.conversationScroller.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun bindMessage(row: ViewGroup, message: NovaMessage) {
        val driver = message.role == NovaMessageRole.DRIVER

        // The bubble fills the row and the row is inset on one side. LinearLayout ignores
        // maxWidth, so an asymmetric inset is what gives the two speakers their stagger and caps
        // how wide a long reply can run.
        val inset = resources.getDimensionPixelSize(R.dimen.nova_bubble_inset)
        row.setPaddingRelative(if (driver) inset else 0, 0, if (driver) 0 else inset, 0)

        val bubble = row.findViewById<LinearLayout>(R.id.messageBubble)
        bubble.setBackgroundResource(
            when {
                driver -> R.drawable.bg_nova_bubble_driver
                message.tone == NovaMessageTone.ERROR -> R.drawable.bg_nova_bubble_alert
                else -> R.drawable.bg_nova_bubble_nova
            },
        )

        val roleView = row.findViewById<TextView>(R.id.textMessageRole)
        roleView.setText(if (driver) R.string.role_driver else R.string.role_nova)
        roleView.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    driver -> R.color.hypernova_text_secondary
                    message.tone == NovaMessageTone.ERROR -> R.color.hypernova_error
                    message.tone == NovaMessageTone.SUCCESS -> R.color.hypernova_success
                    else -> R.color.hypernova_cyan
                },
            ),
        )

        row.findViewById<TextView>(R.id.textMessageBody).text = message.text

        val actionCard = row.findViewById<LinearLayout>(R.id.messageActionCard)
        val action = message.action
        if (action == null) {
            actionCard.visibility = View.GONE
        } else {
            actionCard.visibility = View.VISIBLE
            row.findViewById<TextView>(R.id.textActionLabel).text = action.label
            row.findViewById<TextView>(R.id.textActionTitle).text = action.title
        }

        val progress = row.findViewById<LinearProgressIndicator>(R.id.messageProgress)
        progress.visibility = if (message.pending) View.VISIBLE else View.GONE

        bindEvidence(row, message.evidence)
    }

    private fun bindEvidence(row: ViewGroup, cards: List<NovaEvidenceCard>) {
        val scroller = row.findViewById<View>(R.id.messageEvidenceScroller)
        val container = row.findViewById<LinearLayout>(R.id.messageEvidenceContainer)
        scroller.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        container.removeAllViews()
        cards.take(4).forEach { card ->
            val view = layoutInflater.inflate(R.layout.item_nova_evidence, container, false)
            view.findViewById<TextView>(R.id.textEvidenceTitle).text = "${card.index}. ${card.title}"
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
            container.addView(view)
        }
    }

    private fun renderFollowUpCountdown(deadlineElapsedRealtimeMs: Long?) {
        countdownHandler.removeCallbacks(followUpCountdownTick)
        followUpDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs
        if (deadlineElapsedRealtimeMs != null) {
            followUpCountdownTick.run()
        } else {
            renderSessionHint()
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

    private fun NovaVisibleState.colorResource(): Int = when (this) {
        NovaVisibleState.SUCCESS -> R.color.hypernova_success
        NovaVisibleState.ERROR -> R.color.hypernova_error
        NovaVisibleState.UNAVAILABLE -> R.color.hypernova_warning
        else -> R.color.hypernova_cyan
    }
}
