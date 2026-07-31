package com.hypernova.climate.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hypernova.climate.R
import com.hypernova.climate.backend.ClimateBackend
import com.hypernova.climate.backend.ClimateBackendFactory
import com.hypernova.climate.databinding.FragmentClimateBinding
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.model.ClimateHealth
import com.hypernova.climate.ui.state.ClimateUiState
import com.hypernova.climate.ui.view.CabinAirflowView
import com.hypernova.climate.util.ClimateFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the Climate Home screen (README §13) from [ClimateUiState].
 * The screen always renders from `confirmedState`; requested values are shown
 * separately (README §4). No dummy data at runtime — debug builds receive a
 * preview state from `ClimatePreview` (debug source set), release shows the
 * honest unavailable state.
 */
class ClimateFragment : Fragment() {

    private var _binding: FragmentClimateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ClimateViewModel by viewModels()
    private lateinit var backend: ClimateBackend

    private val timeFormat = SimpleDateFormat("H:mm", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClimateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backend = ClimateBackendFactory.create()
        wireControls()
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------------

    private fun render(state: ClimateUiState) = with(binding) {
        tvCurrentTime.text = timeFormat.format(Date())

        val confirmed = state.confirmedState

        // Header mode + status dot.
        tvClimateMode.text =
            (confirmed?.mode ?: com.hypernova.climate.model.ClimateMode.UNAVAILABLE).name
        viewClimateStatusDot.backgroundTintList =
            ColorStateList.valueOf(healthColor(confirmed?.health))

        // Environment.
        tvCabinTemperature.text = ClimateFormatter.temperature(confirmed?.cabinTemperatureC)
        tvOutsideTemperature.text = ClimateFormatter.temperature(confirmed?.outsideTemperatureC)
        tvAirQuality.text = ClimateFormatter.airQuality(confirmed?.airQuality)

        // Zones.
        tvDriverTargetTemperature.text =
            ClimateFormatter.temperature(confirmed?.driverTargetTemperatureC)
        tvPassengerTargetTemperature.text =
            ClimateFormatter.temperature(confirmed?.passengerTargetTemperatureC)

        // Gauges — fraction of the temperature range each zone target reaches.
        val tMin = state.capabilities?.minimumTemperatureC ?: 16f
        val tMax = state.capabilities?.maximumTemperatureC ?: 30f
        driverTemperatureArc.setProgress(
            fractionOf(confirmed?.driverTargetTemperatureC, tMin, tMax)
        )
        passengerTemperatureArc.setProgress(
            fractionOf(confirmed?.passengerTargetTemperatureC, tMin, tMax)
        )

        // Heat/cool accent: driver gauge warms to amber when A/C is in HEAT mode.
        val heating = confirmed?.acMode == AcMode.HEAT
        driverTemperatureArc.setAccentColor(
            color(if (heating) R.color.hn_warning else R.color.hn_primary_cyan)
        )
        passengerTemperatureArc.setAccentColor(color(R.color.hn_warning))

        // Airflow streams over the car (mode = direction, fan = speed/density).
        val fan = confirmed?.fanLevel ?: 0
        val active = confirmed?.powerEnabled == true && fan > 0
        airflowView.setAirflow(
            driver = CabinAirflowView.ZoneAirflow(
                mode = confirmed?.driverAirflowMode,
                fanLevel = fan,
                accentColor = color(if (heating) R.color.hn_warning else R.color.hn_primary_cyan),
                active = active
            ),
            passenger = CabinAirflowView.ZoneAirflow(
                mode = confirmed?.passengerAirflowMode,
                fanLevel = fan,
                accentColor = color(R.color.hn_warning),
                active = active
            )
        )

        // Per-zone airflow selection (independent unless synced).
        renderZoneAirflow(
            confirmed?.driverAirflowMode,
            driverAirflowOption1, driverAirflowOption2, driverAirflowOption3,
            R.color.hn_primary_cyan
        )
        renderZoneAirflow(
            confirmed?.passengerAirflowMode,
            passengerAirflowOption1, passengerAirflowOption2, passengerAirflowOption3,
            R.color.hn_warning
        )

        // Fan.
        tvFanLevel.text = ClimateFormatter.fanLevel(confirmed?.fanLevel)
        renderFanSegments(confirmed?.fanLevel ?: 0)

        // Primary controls.
        renderToggle(btnClimatePower, confirmed?.powerEnabled == true)
        renderToggle(btnClimateAuto, confirmed?.autoModeEnabled == true)
        renderAcMode(confirmed?.acMode ?: AcMode.OFF)
        renderToggle(btnClimateSync, confirmed?.zonesSynchronized == true)

        // General airflow bar: highlight only when both zones share a mode.
        val commonAirflow = confirmed?.driverAirflowMode
            ?.takeIf { it == confirmed.passengerAirflowMode }
        renderAirflow(commonAirflow)

        // Air source.
        renderToggle(btnFreshAir, confirmed?.freshAirEnabled == true)
        renderToggle(btnRecirculation, confirmed?.recirculationEnabled == true)

        // Defrost.
        renderToggle(btnFrontDefrost, confirmed?.frontDefrostEnabled == true)
        renderToggle(btnRearDefrost, confirmed?.rearDefrostEnabled == true)
        renderToggle(btnMaxDefrost, confirmed?.maxDefrostEnabled == true)

        // Seat heating dots.
        renderSeatLevel(driverSeatHeatingLevel, confirmed?.driverSeatHeatingLevel ?: 0)
        renderSeatLevel(passengerSeatHeatingLevel, confirmed?.passengerSeatHeatingLevel ?: 0)

        // Capability-driven visibility (README §6, §34).
        applyCapabilities(state)

        // Confirmation bar.
        tvConfirmationMessage.text = when {
            state.isCommandPending -> getString(R.string.confirmation_pending)
            else -> getString(R.string.confirmation_hint)
        }
    }

    private fun applyCapabilities(state: ClimateUiState) = with(binding) {
        val caps = state.capabilities

        // Passenger zone only in dual-zone. Unknown (null) -> keep visible/empty.
        cardPassengerZone.visibility =
            if (caps == null || caps.isDualZone) View.VISIBLE else View.GONE

        // Sync only when supported.
        parentColumn(btnClimateSync)?.visibility =
            if (caps == null || caps.supportsZoneSync) View.VISIBLE else View.GONE

        // A/C only when supported.
        parentColumn(btnClimateAc)?.visibility =
            if (caps == null || caps.supportsAc) View.VISIBLE else View.GONE

        // Defrost / seat heating hidden when unsupported.
        btnFrontDefrost.visibility = visibleIf(caps == null || caps.supportsFrontDefrost)
        btnRearDefrost.visibility = visibleIf(caps == null || caps.supportsRearDefrost)
        btnMaxDefrost.visibility = visibleIf(caps == null || caps.supportsMaxDefrost)
        btnDriverSeatHeating.visibility = visibleIf(caps == null || caps.driverSeatHeatingLevels > 0)
        btnPassengerSeatHeating.visibility =
            visibleIf(caps == null || caps.passengerSeatHeatingLevels > 0)
    }

    // ----------------------------------------------------------------------
    // Render helpers
    // ----------------------------------------------------------------------

    private fun renderToggle(
        view: View,
        active: Boolean,
        activeColorRes: Int = R.color.hn_primary_cyan
    ) {
        val amber = activeColorRes == R.color.hn_warning
        // setBackgroundResource resets padding — capture and restore it.
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        view.setBackgroundResource(
            when {
                active && amber -> R.drawable.bg_control_selected_amber
                active -> R.drawable.bg_control_selected
                else -> R.drawable.bg_control_unselected
            }
        )
        view.setPadding(l, t, r, b)
        (view as? ImageView)?.let {
            setTint(it, color(if (active) activeColorRes else R.color.hn_text_secondary))
        }
    }

    private fun renderAirflow(mode: AirflowMode?) = with(binding) {
        renderToggle(btnAirflowFace, active = mode == AirflowMode.FACE)
        renderToggle(btnAirflowFeet, active = mode == AirflowMode.FEET)
        renderToggle(btnAirflowFaceFeet, active = mode == AirflowMode.FACE_AND_FEET)
    }

    /** A/C control: OFF (gray snowflake), COOL (cyan snowflake), HEAT (amber flame). */
    private fun renderAcMode(mode: AcMode) = with(binding) {
        when (mode) {
            AcMode.OFF -> {
                btnClimateAc.setImageResource(R.drawable.ic_ac)
                renderToggle(btnClimateAc, active = false)
            }
            AcMode.COOL -> {
                btnClimateAc.setImageResource(R.drawable.ic_ac)
                renderToggle(btnClimateAc, active = true, R.color.hn_primary_cyan)
            }
            AcMode.HEAT -> {
                btnClimateAc.setImageResource(R.drawable.ic_heat)
                renderToggle(btnClimateAc, active = true, R.color.hn_warning)
            }
        }
    }

    private fun renderZoneAirflow(
        mode: AirflowMode?,
        opt1: View,
        opt2: View,
        opt3: View,
        activeColorRes: Int
    ) {
        renderToggle(opt1, mode == AirflowMode.FACE, activeColorRes)
        renderToggle(opt2, mode == AirflowMode.FEET, activeColorRes)
        renderToggle(opt3, mode == AirflowMode.FACE_AND_FEET, activeColorRes)
    }

    /** Fraction (0f..1f) of the temperature range that [value] reaches. */
    private fun fractionOf(value: Float?, min: Float, max: Float): Float {
        if (value == null || max <= min) return 0f
        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }

    private fun renderFanSegments(level: Int) {
        val group = binding.fanLevelIndicator
        for (i in 0 until group.childCount) {
            group.getChildAt(i).setBackgroundResource(
                if (i < level) R.drawable.bg_seg_on else R.drawable.bg_seg_off
            )
        }
    }

    private fun renderSeatLevel(group: ViewGroup, level: Int) {
        for (i in 0 until group.childCount) {
            group.getChildAt(i).setBackgroundResource(
                if (i < level) R.drawable.bg_seg_on else R.drawable.bg_seg_off
            )
        }
    }

    private fun healthColor(health: ClimateHealth?): Int = color(
        when (health) {
            ClimateHealth.NORMAL -> R.color.hn_success
            ClimateHealth.DEGRADED -> R.color.hn_warning
            null -> R.color.hn_text_disabled
            else -> R.color.hn_error
        }
    )

    private fun visibleIf(condition: Boolean): Int =
        if (condition) View.VISIBLE else View.GONE

    private fun parentColumn(view: View): View? = view.parent as? View

    private fun setTint(view: ImageView, colorInt: Int) {
        view.imageTintList = ColorStateList.valueOf(colorInt)
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    // ----------------------------------------------------------------------
    // Controls
    // ----------------------------------------------------------------------

    private fun wireControls() = with(binding) {
        btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        btnClimatePower.setOnClickListener { viewModel.togglePower() }
        btnClimateAuto.setOnClickListener { viewModel.toggleAuto() }
        btnClimateAc.setOnClickListener { viewModel.cycleAcMode() }
        btnClimateSync.setOnClickListener { viewModel.toggleSync() }

        btnDriverTemperatureMinus.setOnClickListener { viewModel.driverTempDown() }
        btnDriverTemperaturePlus.setOnClickListener { viewModel.driverTempUp() }
        btnPassengerTemperatureMinus.setOnClickListener { viewModel.passengerTempDown() }
        btnPassengerTemperaturePlus.setOnClickListener { viewModel.passengerTempUp() }

        btnFanMinus.setOnClickListener { viewModel.fanDown() }
        btnFanPlus.setOnClickListener { viewModel.fanUp() }

        // General airflow direction sets both zones.
        btnAirflowFace.setOnClickListener { viewModel.setBothAirflow(AirflowMode.FACE) }
        btnAirflowFeet.setOnClickListener { viewModel.setBothAirflow(AirflowMode.FEET) }
        btnAirflowFaceFeet.setOnClickListener { viewModel.setBothAirflow(AirflowMode.FACE_AND_FEET) }

        // Per-zone airflow selectors (independent unless SYNC is on).
        driverAirflowOption1.setOnClickListener { viewModel.setDriverAirflow(AirflowMode.FACE) }
        driverAirflowOption2.setOnClickListener { viewModel.setDriverAirflow(AirflowMode.FEET) }
        driverAirflowOption3.setOnClickListener { viewModel.setDriverAirflow(AirflowMode.FACE_AND_FEET) }
        passengerAirflowOption1.setOnClickListener { viewModel.setPassengerAirflow(AirflowMode.FACE) }
        passengerAirflowOption2.setOnClickListener { viewModel.setPassengerAirflow(AirflowMode.FEET) }
        passengerAirflowOption3.setOnClickListener { viewModel.setPassengerAirflow(AirflowMode.FACE_AND_FEET) }

        btnFreshAir.setOnClickListener { viewModel.toggleFreshAir() }
        btnRecirculation.setOnClickListener { viewModel.toggleRecirculation() }

        btnFrontDefrost.setOnClickListener { viewModel.toggleFrontDefrost() }
        btnRearDefrost.setOnClickListener { viewModel.toggleRearDefrost() }
        btnMaxDefrost.setOnClickListener { viewModel.toggleMaxDefrost() }

        btnDriverSeatHeating.setOnClickListener { viewModel.cycleDriverSeatHeat() }
        btnPassengerSeatHeating.setOnClickListener { viewModel.cyclePassengerSeatHeat() }
    }

    override fun onStart() {
        super.onStart()
        backend.start()
        _binding?.airflowView?.resume()
    }

    override fun onStop() {
        _binding?.airflowView?.pause()
        backend.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
