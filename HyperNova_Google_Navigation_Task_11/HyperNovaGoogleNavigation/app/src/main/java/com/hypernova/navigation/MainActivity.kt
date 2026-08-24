package com.hypernova.navigation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.hypernova.navigation.databinding.ActivityMainBinding
import com.hypernova.navigation.model.NavigationInitializationState
import com.hypernova.navigation.model.NavigationPhase
import com.hypernova.navigation.model.NavigationSessionState
import com.hypernova.navigation.persistence.DestinationTokenEntry
import com.hypernova.navigation.ui.NavigationViewModel
import com.hypernova.visuals.CockpitNavigationController
import kotlin.math.ceil
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: NavigationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dismissSearchKeyboard()

        CockpitNavigationController.bind(
            binding.cockpitNavigation,
            CockpitNavigationController.Destination.NAVIGATION,
        )
        viewModel.attachMapSurface(binding.navigationFragmentContainer)
        configureActions()
        configureBackBehavior()
        viewModel.attach(this)
        observeState()
        handleOpenPlaceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reused cockpit activities can resume after their application-owned WebView was
        // temporarily detached. This is idempotent and does not reload the Google document.
        viewModel.attachMapSurface(binding.navigationFragmentContainer)
        viewModel.attach(this)
        // An EditText is otherwise Android's first focus candidate and opens the IME every time the
        // cockpit switches from Launcher to Navigation. Navigation is map-first; text entry starts
        // only after the driver explicitly taps the search box.
        binding.root.post(::dismissSearchKeyboard)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.attachMapSurface(binding.navigationFragmentContainer)
        viewModel.attach(this)
        handleOpenPlaceIntent(intent)
    }

    override fun onDestroy() {
        viewModel.detachMapSurface(binding.navigationFragmentContainer)
        super.onDestroy()
    }

    private fun configureActions() {
        binding.searchButton.setOnClickListener { submitSearch() }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.cancelButton.setOnClickListener { viewModel.cancelNavigation() }
        binding.simulateButton.setOnClickListener { viewModel.startSimulation() }
        binding.configurationAction.setOnClickListener { viewModel.attach(this) }
    }

    private fun handleOpenPlaceIntent(value: Intent?) {
        val query = value?.getStringExtra(EXTRA_PLACE_QUERY)?.trim().orEmpty()
        if (query.isBlank()) return
        value?.removeExtra(EXTRA_PLACE_QUERY)
        binding.searchInput.setText(query)
        viewModel.openPlace(query)
    }

    private fun configureBackBehavior() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.results.value.isNotEmpty()) {
                        viewModel.clearResults()
                    } else {
                        returnHome()
                    }
                }
            },
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.session.collect(::renderSession) }
                launch { viewModel.results.collect(::renderResults) }
                launch {
                    viewModel.message.collect { value ->
                        binding.searchMessage.text = value.orEmpty()
                        binding.searchMessage.isVisible = !value.isNullOrBlank()
                        binding.root.post(::updateMapInsets)
                    }
                }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.searchButton.isEnabled = !busy
                        binding.searchInput.isEnabled = !busy
                    }
                }
            }
        }
    }

    private fun renderSession(state: NavigationSessionState) {
        binding.statusText.text =
            if (state.simulated) getString(R.string.simulated_status) else state.statusMessage

        val ready = state.initialization == NavigationInitializationState.READY_IDLE
        val driving = state.phase in setOf(NavigationPhase.GUIDING, NavigationPhase.REROUTING)
        binding.searchPanel.isVisible = ready && !driving

        val initializationBlocking = !ready
        binding.configurationPanel.isVisible = initializationBlocking
        if (initializationBlocking) renderInitialization(state)

        val hasRoute = state.routeId.isNotBlank()
        binding.routePanel.isVisible = hasRoute
        if (hasRoute) {
            binding.routeTitle.text = state.selectedDestination?.title.orEmpty()
            binding.routeMetrics.text = formatMetrics(state)
            binding.simulateButton.isVisible = false
            binding.cancelButton.isVisible = true
        }
        binding.root.post(::updateMapInsets)
    }

    private fun renderInitialization(state: NavigationSessionState) {
        binding.configurationMessage.text = state.statusMessage
        when (state.initialization) {
            NavigationInitializationState.CONFIGURATION_REQUIRED -> {
                binding.configurationTitle.setText(R.string.configuration_required_title)
                binding.configurationAction.isVisible = false
            }
            NavigationInitializationState.TERMS_REQUIRED -> {
                binding.configurationTitle.setText(R.string.terms_required_title)
                binding.configurationAction.setText(R.string.review_terms_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.LOCATION_UNAVAILABLE -> {
                binding.configurationTitle.setText(R.string.location_required_title)
                binding.configurationAction.setText(R.string.retry_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.INITIALIZING -> {
                binding.configurationTitle.setText(R.string.navigation_initializing)
                binding.configurationAction.isVisible = false
            }
            NavigationInitializationState.GOOGLE_SERVICES_UNAVAILABLE,
            NavigationInitializationState.ERROR,
            -> {
                binding.configurationTitle.setText(R.string.navigation_unavailable_title)
                binding.configurationAction.setText(R.string.retry_action)
                binding.configurationAction.isVisible = true
            }
            NavigationInitializationState.READY_IDLE -> Unit
        }
    }

    private fun renderResults(results: List<DestinationTokenEntry>) {
        binding.searchResults.removeAllViews()
        results.forEach { entry ->
            val button =
                MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text = buildString {
                        append(entry.record.title)
                        if (entry.record.subtitle.isNotBlank()) append("\n${entry.record.subtitle}")
                    }
                    contentDescription =
                        getString(
                            R.string.destination_result_content_description,
                            entry.record.title,
                            entry.record.subtitle,
                        )
                    isAllCaps = false
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight = resources.getDimensionPixelSize(R.dimen.hn_navigation_touch_target)
                    setOnClickListener { viewModel.select(entry) }
                }
            binding.searchResults.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8.dp },
            )
        }
        binding.root.post(::updateMapInsets)
    }

    private fun submitSearch() {
        dismissSearchKeyboard()
        viewModel.search(binding.searchInput.text?.toString().orEmpty())
    }

    private fun dismissSearchKeyboard() {
        binding.searchInput.clearFocus()
        binding.root.requestFocus()
        ViewCompat.getWindowInsetsController(binding.root)
            ?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun formatMetrics(state: NavigationSessionState): String {
        val eta =
            if (state.etaSeconds >= 0) {
                getString(R.string.eta_minutes_format, ceil(state.etaSeconds / 60.0).toLong())
            } else {
                "—"
            }
        val distance =
            if (state.distanceMeters >= 0) {
                getString(R.string.distance_km_format, state.distanceMeters / 1000.0)
            } else {
                "—"
            }
        return getString(R.string.route_metrics_format, eta, distance)
    }

    /** Keeps Google attribution and controls outside the HyperNova overlay panels. */
    private fun updateMapInsets() {
        val top =
            if (binding.searchPanel.isVisible) {
                (binding.searchPanel.bottom + 8.dp)
                    .coerceAtMost(binding.navigationFragmentContainer.height)
            } else {
                0
            }
        val bottom =
            if (binding.routePanel.isVisible) {
                (binding.navigationFragmentContainer.height - binding.routePanel.top + 16.dp)
                    .coerceAtLeast(0)
            } else {
                0
            }
        viewModel.setMapInsets(top, bottom)
    }

    private fun returnHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        moveTaskToBack(true)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXTRA_PLACE_QUERY = "com.hypernova.navigation.extra.PLACE_QUERY"
    }
}
