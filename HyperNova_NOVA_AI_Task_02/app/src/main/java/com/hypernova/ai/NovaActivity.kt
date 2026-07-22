package com.hypernova.ai

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hypernova.ai.databinding.ActivityNovaBinding
import com.hypernova.ai.runtime.NovaRuntimeService
import com.hypernova.ai.ui.NovaUiState
import com.hypernova.ai.ui.NovaViewModel
import com.hypernova.ai.ui.NovaVisibleState

class NovaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNovaBinding
    private val viewModel: NovaViewModel by viewModels()
    private var orbAnimator: ObjectAnimator? = null
    private var animatedState: NovaVisibleState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityNovaBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        val statusColor = ContextCompat.getColor(this@NovaActivity, state.visibleState.colorResource())
        textStatus.setTextColor(statusColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        textStateEyebrow.setTextColor(statusColor)

        val unavailable = state.visibleState == NovaVisibleState.UNAVAILABLE
        orbRing.imageAlpha = if (unavailable) 88 else 255
        orbRing.colorFilter = if (unavailable) {
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        } else {
            null
        }
        animateOrb(state.visibleState)

        buttonSecondary.visibility = if (unavailable) View.VISIBLE else View.GONE
        buttonSecondary.isEnabled = unavailable
    }

    private fun animateOrb(state: NovaVisibleState) {
        if (animatedState == state) return
        animatedState = state
        orbAnimator?.cancel()
        binding.orbRing.rotation = 0f
        binding.orbRing.scaleX = 1f
        binding.orbRing.scaleY = 1f
        binding.orbRing.alpha = 1f

        orbAnimator = when (state) {
            NovaVisibleState.LISTENING, NovaVisibleState.SPEAKING -> ObjectAnimator.ofPropertyValuesHolder(
                binding.orbRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f, 1.04f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f, 1.04f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.82f, 1f),
            ).apply {
                duration = if (state == NovaVisibleState.SPEAKING) 620L else 920L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
            NovaVisibleState.PROCESSING, NovaVisibleState.EXECUTING -> ObjectAnimator.ofFloat(
                binding.orbRing,
                View.ROTATION,
                0f,
                360f,
            ).apply {
                duration = if (state == NovaVisibleState.PROCESSING) 7_000L else 4_500L
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            else -> null
        }
    }

    override fun onDestroy() {
        orbAnimator?.cancel()
        super.onDestroy()
    }

    private fun NovaVisibleState.statusLabel(): String = when (this) {
        NovaVisibleState.IDLE -> "READY"
        NovaVisibleState.SUCCESS -> "COMPLETED"
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
