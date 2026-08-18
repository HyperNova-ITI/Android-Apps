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
import com.hypernova.climate.data.ClimateZone
import com.hypernova.climate.databinding.FragmentClimateBinding
import com.hypernova.climate.model.AcMode
import com.hypernova.climate.model.AirflowMode
import com.hypernova.climate.model.ClimateHealth
import com.hypernova.climate.ui.state.ClimateUiState
import com.hypernova.climate.ui.view.CabinAirflowView
import com.hypernova.climate.util.ClimateFormatter
import kotlinx.coroutines.launch

/**
 * Renders the Climate Home screen (README §13) from [ClimateUiState].
 * Sensor telemetry and online controls always use the confirmed backend state.
 * When the gateway cannot accept commands, only the controllable presentation
 * values use an in-memory fallback; it is never written to shared state or sent
 * to the vehicle.
 */
class ClimateFragment : Fragment() {

    private var _binding: FragmentClimateBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ClimateViewModel by viewModels()
    private lateinit var backend: ClimateBackend
    private var lastUiState: ClimateUiState? = null
    private var offlineVisualState: OfflineClimateVisualState? = null

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
        lastUiState = state
        val confirmed = state.confirmedState
        val realCommandsAvailable = state.canSendCommands && !state.isCommandPending
        val offlineVisualMode = !realCommandsAvailable && !state.isCommandPending
        val offline = if (offlineVisualMode) {
            offlineVisualState ?: createOfflineVisualState(state).also {
                offlineVisualState = it
            }
        } else {
            // A fresh confirmed backend state immediately replaces the local
            // presentation values. They are never replayed to the vehicle.
            offlineVisualState = null
            null
        }

        // Header mode + status dot.
        tvClimateMode.text = when {
            state.isCommandPending -> "APPLYING"
            !state.canSendCommands -> "OFFLINE"
            state.isStale -> "STALE"
            else -> (confirmed?.mode ?: com.hypernova.climate.model.ClimateMode.UNAVAILABLE).name
        }
        viewClimateStatusDot.backgroundTintList =
            ColorStateList.valueOf(
                if (state.isCommandPending) color(R.color.hn_warning)
                else healthColor(confirmed?.health)
            )

        val uiInteractionEnabled = !state.isCommandPending
        listOf(
            btnClimatePower,
            btnDriverTemperatureMinus,
            btnDriverTemperaturePlus,
            btnPassengerTemperatureMinus,
            btnPassengerTemperaturePlus,
            btnFanMinus,
            btnFanPlus,
        ).forEach {
            it.isEnabled = uiInteractionEnabled
            it.alpha = if (uiInteractionEnabled) 1f else 0.45f
        }

        // Environment.
        tvCabinTemperature.text = ClimateFormatter.temperature(confirmed?.cabinTemperatureC)
        tvOutsideTemperature.text = ClimateFormatter.temperature(confirmed?.outsideTemperatureC)
        tvAirQuality.text = ClimateFormatter.airQuality(confirmed?.airQuality)

        // Zones.
        tvDriverTargetTemperature.text =
            ClimateFormatter.temperature(
                offline?.driverTargetTemperatureC ?: confirmed?.driverTargetTemperatureC,
            )
        tvPassengerTargetTemperature.text =
            ClimateFormatter.temperature(
                offline?.passengerTargetTemperatureC ?: confirmed?.passengerTargetTemperatureC,
            )

        // Gauges — fraction of the temperature range each zone target reaches.
        val tMin = state.capabilities?.minimumTemperatureC ?: 16f
        val tMax = state.capabilities?.maximumTemperatureC ?: 30f
        driverTemperatureArc.setProgress(
            fractionOf(offline?.driverTargetTemperatureC ?: confirmed?.driverTargetTemperatureC, tMin, tMax)
        )
        passengerTemperatureArc.setProgress(
            fractionOf(
                offline?.passengerTargetTemperatureC ?: confirmed?.passengerTargetTemperatureC,
                tMin,
                tMax,
            )
        )

        // Heat/cool accent: driver gauge warms to amber when A/C is in HEAT mode.
        val acMode = offline?.acMode ?: confirmed?.acMode ?: AcMode.OFF
        val heating = acMode == AcMode.HEAT
        driverTemperatureArc.setAccentColor(
            color(if (heating) R.color.hn_warning else R.color.hn_primary_cyan)
        )
        passengerTemperatureArc.setAccentColor(color(R.color.hn_warning))

        // Airflow streams over the car (mode = direction, fan = speed/density).
        val fan = offline?.fanLevel ?: confirmed?.fanLevel ?: 0
        val powerEnabled = offline?.powerEnabled ?: (confirmed?.powerEnabled == true)
        val active = powerEnabled && fan > 0

        // The backend still exposes one cabin-wide airflowMode.
        // Per-zone values override it only when the user selects a zone locally.
        val driverAirflowMode = offline?.driverAirflowMode
            ?: confirmed?.driverAirflowMode
            ?: confirmed?.airflowMode

        val passengerAirflowMode = offline?.passengerAirflowMode
            ?: confirmed?.passengerAirflowMode
            ?: confirmed?.airflowMode

        airflowView.setAirflow(
            driver = CabinAirflowView.ZoneAirflow(
                mode = driverAirflowMode,
                fanLevel = fan,
                accentColor = color(
                    if (heating) R.color.hn_warning
                    else R.color.hn_primary_cyan
                ),
                active = active
            ),
            passenger = CabinAirflowView.ZoneAirflow(
                mode = passengerAirflowMode,
                fanLevel = fan,
                accentColor = color(R.color.hn_warning),
                active = active
            )
        )

        // Per-zone airflow:
        // option 1 = FACE
        // option 2 = FEET
        // option 3 = FACE + FEET
        renderZoneAirflow(
            driverAirflowMode,
            driverAirflowOption1,
            driverAirflowOption2,
            driverAirflowOption3,
            R.color.hn_primary_cyan
        )

        renderZoneAirflow(
            passengerAirflowMode,
            passengerAirflowOption1,
            passengerAirflowOption2,
            passengerAirflowOption3,
            R.color.hn_warning
        )

        // Fan.
        tvFanLevel.text = ClimateFormatter.fanLevel(fan)
        renderFanSegments(fan)

        // Primary controls.
        renderToggle(btnClimatePower, powerEnabled)
        renderToggle(btnClimateAuto, offline?.autoModeEnabled ?: (confirmed?.autoModeEnabled == true))
        renderAcMode(acMode)
        renderToggle(btnClimateSync, offline?.zonesSynchronized ?: (confirmed?.zonesSynchronized == true))

        // Air source.
        renderToggle(btnFreshAir, offline?.freshAirEnabled ?: (confirmed?.freshAirEnabled == true))
        renderToggle(
            btnRecirculation,
            offline?.recirculationEnabled ?: (confirmed?.recirculationEnabled == true),
        )

        // Defrost.
        renderToggle(btnFrontDefrost, offline?.frontDefrostEnabled ?: (confirmed?.frontDefrostEnabled == true))
        renderToggle(btnRearDefrost, offline?.rearDefrostEnabled ?: (confirmed?.rearDefrostEnabled == true))
        renderToggle(btnMaxDefrost, offline?.maxDefrostEnabled ?: (confirmed?.maxDefrostEnabled == true))

        // Seat heating dots.


        // Keep the approved Climate composition present even when a vehicle does
        // not implement every optional command. Capability values continue to
        // decide whether an optional control can be used; they no longer remove
        // the surrounding UI and leave the panel visually incomplete.
        applyCapabilities(state, offlineVisualMode)

    }

    private fun applyCapabilities(state: ClimateUiState, offlineVisualMode: Boolean) = with(binding) {
        val caps = state.capabilities

        listOf(
            cardPassengerZone,
            cabinEnvironmentSection,
            airQualityEnvironmentSection,
            outsideEnvironmentSection,
            airQualityDivider,
            outsideTemperatureDivider,
            primaryControlsDivider,
            airSourceSection,
            defrostSection,
        ).forEach { it.visibility = View.VISIBLE }

        setPresentationControl(
            parentColumn(btnClimateAuto),
            btnClimateAuto,
            offlineVisualMode || caps?.supportsAutoMode == true,
        )
        setPresentationControl(
            parentColumn(btnClimateAc),
            btnClimateAc,
            offlineVisualMode || caps?.supportsAc == true,
        )
        setPresentationControl(
            parentColumn(btnClimateSync),
            btnClimateSync,
            offlineVisualMode || caps?.supportsZoneSync == true,
        )

        setPresentationControl(
            null,
            driverAirflowOption1,
            offlineVisualMode || caps?.supportedAirflowModes?.contains(AirflowMode.FACE) == true,
        )
        setPresentationControl(
            null,
            driverAirflowOption2,
            offlineVisualMode || caps?.supportedAirflowModes?.contains(AirflowMode.FEET) == true,
        )
        setPresentationControl(
            null,
            driverAirflowOption3,
            offlineVisualMode || caps?.supportedAirflowModes?.contains(AirflowMode.FACE_AND_FEET) == true,
        )
        setPresentationControl(
            null,
            passengerAirflowOption1,
            offlineVisualMode || caps?.isDualZone == true &&
                caps.supportedAirflowModes.contains(AirflowMode.FACE),
        )
        setPresentationControl(
            null,
            passengerAirflowOption2,
            offlineVisualMode || caps?.isDualZone == true &&
                caps.supportedAirflowModes.contains(AirflowMode.FEET),
        )
        setPresentationControl(
            null,
            passengerAirflowOption3,
            offlineVisualMode || caps?.isDualZone == true &&
                caps.supportedAirflowModes.contains(AirflowMode.FACE_AND_FEET),
        )

        setPresentationControl(null, btnFreshAir, offlineVisualMode || caps?.supportsFreshAir == true)
        setPresentationControl(
            null,
            btnRecirculation,
            offlineVisualMode || caps?.supportsRecirculation == true,
        )
        setPresentationControl(
            null,
            btnFrontDefrost,
            offlineVisualMode || caps?.supportsFrontDefrost == true,
        )
        setPresentationControl(null, btnRearDefrost, offlineVisualMode || caps?.supportsRearDefrost == true)
        setPresentationControl(null, btnMaxDefrost, offlineVisualMode || caps?.supportsMaxDefrost == true)
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

    private fun healthColor(health: ClimateHealth?): Int = color(
        when (health) {
            ClimateHealth.NORMAL -> R.color.hn_success
            ClimateHealth.DEGRADED -> R.color.hn_warning
            null -> R.color.hn_text_disabled
            else -> R.color.hn_error
        }
    )

    private fun parentColumn(view: View): View? = view.parent as? View

    /**
     * Optional functions remain represented in the approved UI but cannot be
     * invoked unless this vehicle capability explicitly enables them.
     */
    private fun setPresentationControl(container: View?, control: View, available: Boolean) {
        container?.apply {
            visibility = View.VISIBLE
            alpha = if (available) 1f else UNSUPPORTED_CONTROL_ALPHA
        }
        control.apply {
            visibility = View.VISIBLE
            isEnabled = available
            alpha = if (container == null) {
                if (available) 1f else UNSUPPORTED_CONTROL_ALPHA
            } else {
                1f
            }
        }
    }

    private fun setTint(view: ImageView, colorInt: Int) {
        view.imageTintList = ColorStateList.valueOf(colorInt)
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    /**
     * UI-only control state used only while the real backend cannot accept
     * commands. It is deliberately not stored in ClimateStateOwner and has no
     * path to VehicleGatewayRuntime, the service, or AIDL.
     */
    private data class OfflineClimateVisualState(
        val driverTargetTemperatureC: Float,
        val passengerTargetTemperatureC: Float,
        val fanLevel: Int,
        val powerEnabled: Boolean,
        val driverAirflowMode: AirflowMode,
        val passengerAirflowMode: AirflowMode,
        val autoModeEnabled: Boolean,
        val acMode: AcMode,
        val zonesSynchronized: Boolean,
        val freshAirEnabled: Boolean,
        val recirculationEnabled: Boolean,
        val frontDefrostEnabled: Boolean,
        val rearDefrostEnabled: Boolean,
        val maxDefrostEnabled: Boolean,
    )

    private fun createOfflineVisualState(state: ClimateUiState): OfflineClimateVisualState {
        val confirmed = state.confirmedState
        val minimum = state.capabilities?.minimumTemperatureC ?: OFFLINE_MIN_TEMPERATURE_C
        val maximum = state.capabilities?.maximumTemperatureC ?: OFFLINE_MAX_TEMPERATURE_C
        val maximumFan = state.capabilities?.maximumFanLevel ?: OFFLINE_MAX_FAN_LEVEL
        return OfflineClimateVisualState(
            driverTargetTemperatureC = (confirmed?.driverTargetTemperatureC
                ?: OFFLINE_DEFAULT_TEMPERATURE_C).coerceIn(minimum, maximum),
            passengerTargetTemperatureC = (confirmed?.passengerTargetTemperatureC
                ?: OFFLINE_DEFAULT_TEMPERATURE_C).coerceIn(minimum, maximum),
            fanLevel = (confirmed?.fanLevel ?: OFFLINE_DEFAULT_FAN_LEVEL).coerceIn(0, maximumFan),
            powerEnabled = confirmed?.powerEnabled ?: true,
            driverAirflowMode = confirmed?.driverAirflowMode
                ?: confirmed?.airflowMode
                ?: AirflowMode.FACE,
            passengerAirflowMode = confirmed?.passengerAirflowMode
                ?: confirmed?.airflowMode
                ?: AirflowMode.FACE,
            autoModeEnabled = confirmed?.autoModeEnabled ?: false,
            acMode = confirmed?.acMode ?: AcMode.OFF,
            zonesSynchronized = confirmed?.zonesSynchronized ?: false,
            freshAirEnabled = confirmed?.freshAirEnabled ?: false,
            recirculationEnabled = confirmed?.recirculationEnabled ?: false,
            frontDefrostEnabled = confirmed?.frontDefrostEnabled ?: false,
            rearDefrostEnabled = confirmed?.rearDefrostEnabled ?: false,
            maxDefrostEnabled = confirmed?.maxDefrostEnabled ?: false,
        )
    }

    private fun updateOfflineVisualState(transform: (OfflineClimateVisualState) -> OfflineClimateVisualState) {
        val state = lastUiState ?: return
        if (state.canSendCommands || state.isCommandPending) return
        offlineVisualState = transform(offlineVisualState ?: createOfflineVisualState(state))
        render(state)
    }

    private fun adjustOfflineTemperature(zone: ClimateZone, direction: Int) {
        val state = lastUiState ?: return
        val minimum = state.capabilities?.minimumTemperatureC ?: OFFLINE_MIN_TEMPERATURE_C
        val maximum = state.capabilities?.maximumTemperatureC ?: OFFLINE_MAX_TEMPERATURE_C
        val step = state.capabilities?.temperatureStepC ?: OFFLINE_TEMPERATURE_STEP_C
        updateOfflineVisualState { visual ->
            when (zone) {
                ClimateZone.DRIVER -> visual.copy(
                    driverTargetTemperatureC = (visual.driverTargetTemperatureC + step * direction)
                        .coerceIn(minimum, maximum),
                )
                ClimateZone.PASSENGER -> visual.copy(
                    passengerTargetTemperatureC = (visual.passengerTargetTemperatureC + step * direction)
                        .coerceIn(minimum, maximum),
                )
                ClimateZone.ALL -> visual
            }
        }
    }

    private fun adjustOfflineFan(direction: Int) {
        val maximum = lastUiState?.capabilities?.maximumFanLevel ?: OFFLINE_MAX_FAN_LEVEL
        updateOfflineVisualState { visual ->
            visual.copy(fanLevel = (visual.fanLevel + direction).coerceIn(0, maximum))
        }
    }

    private fun dispatchClimateControl(realAction: () -> Unit, offlineAction: () -> Unit) {
        val state = lastUiState ?: return
        when {
            state.isCommandPending -> Unit
            state.canSendCommands -> realAction()
            else -> offlineAction()
        }
    }

    // ----------------------------------------------------------------------
    // Controls
    // ----------------------------------------------------------------------

    private fun wireControls() = with(binding) {
        btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        btnClimatePower.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::togglePower,
                offlineAction = { updateOfflineVisualState { it.copy(powerEnabled = !it.powerEnabled) } },
            )
        }
        btnClimateAuto.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleAuto,
                offlineAction = { updateOfflineVisualState { it.copy(autoModeEnabled = !it.autoModeEnabled) } },
            )
        }
        btnClimateAc.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::cycleAcMode,
                offlineAction = {
                    updateOfflineVisualState {
                        it.copy(
                            acMode = when (it.acMode) {
                                AcMode.OFF -> AcMode.COOL
                                AcMode.COOL -> AcMode.HEAT
                                AcMode.HEAT -> AcMode.OFF
                            },
                        )
                    }
                },
            )
        }
        btnClimateSync.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleSync,
                offlineAction = {
                    updateOfflineVisualState { it.copy(zonesSynchronized = !it.zonesSynchronized) }
                },
            )
        }

        btnDriverTemperatureMinus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustTargetTemperature(ClimateZone.DRIVER, -1) },
                offlineAction = { adjustOfflineTemperature(ClimateZone.DRIVER, -1) },
            )
        }
        btnDriverTemperaturePlus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustTargetTemperature(ClimateZone.DRIVER, 1) },
                offlineAction = { adjustOfflineTemperature(ClimateZone.DRIVER, 1) },
            )
        }
        btnPassengerTemperatureMinus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustTargetTemperature(ClimateZone.PASSENGER, -1) },
                offlineAction = { adjustOfflineTemperature(ClimateZone.PASSENGER, -1) },
            )
        }
        btnPassengerTemperaturePlus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustTargetTemperature(ClimateZone.PASSENGER, 1) },
                offlineAction = { adjustOfflineTemperature(ClimateZone.PASSENGER, 1) },
            )
        }

        btnFanMinus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustFanLevel(-1) },
                offlineAction = { adjustOfflineFan(-1) },
            )
        }
        btnFanPlus.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.adjustFanLevel(1) },
                offlineAction = { adjustOfflineFan(1) },
            )
        }

        driverAirflowOption1.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.DRIVER, AirflowMode.FACE) },
                offlineAction = { updateOfflineVisualState { it.copy(driverAirflowMode = AirflowMode.FACE) } },
            )
        }
        driverAirflowOption2.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.DRIVER, AirflowMode.FEET) },
                offlineAction = { updateOfflineVisualState { it.copy(driverAirflowMode = AirflowMode.FEET) } },
            )
        }
        driverAirflowOption3.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.DRIVER, AirflowMode.FACE_AND_FEET) },
                offlineAction = {
                    updateOfflineVisualState { it.copy(driverAirflowMode = AirflowMode.FACE_AND_FEET) }
                },
            )
        }
        passengerAirflowOption1.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.PASSENGER, AirflowMode.FACE) },
                offlineAction = { updateOfflineVisualState { it.copy(passengerAirflowMode = AirflowMode.FACE) } },
            )
        }
        passengerAirflowOption2.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.PASSENGER, AirflowMode.FEET) },
                offlineAction = { updateOfflineVisualState { it.copy(passengerAirflowMode = AirflowMode.FEET) } },
            )
        }
        passengerAirflowOption3.setOnClickListener {
            dispatchClimateControl(
                realAction = { viewModel.setAirflowMode(ClimateZone.PASSENGER, AirflowMode.FACE_AND_FEET) },
                offlineAction = {
                    updateOfflineVisualState { it.copy(passengerAirflowMode = AirflowMode.FACE_AND_FEET) }
                },
            )
        }
        btnFreshAir.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::enableFreshAir,
                offlineAction = { updateOfflineVisualState { it.copy(freshAirEnabled = !it.freshAirEnabled) } },
            )
        }
        btnRecirculation.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleRecirculation,
                offlineAction = {
                    updateOfflineVisualState { it.copy(recirculationEnabled = !it.recirculationEnabled) }
                },
            )
        }

        btnFrontDefrost.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleFrontDefrost,
                offlineAction = { updateOfflineVisualState { it.copy(frontDefrostEnabled = !it.frontDefrostEnabled) } },
            )
        }
        btnRearDefrost.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleRearDefrost,
                offlineAction = { updateOfflineVisualState { it.copy(rearDefrostEnabled = !it.rearDefrostEnabled) } },
            )
        }
        btnMaxDefrost.setOnClickListener {
            dispatchClimateControl(
                realAction = viewModel::toggleMaxDefrost,
                offlineAction = { updateOfflineVisualState { it.copy(maxDefrostEnabled = !it.maxDefrostEnabled) } },
            )
        }
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

    private companion object {
        const val UNSUPPORTED_CONTROL_ALPHA = 0.45f
        const val OFFLINE_DEFAULT_TEMPERATURE_C = 22f
        const val OFFLINE_MIN_TEMPERATURE_C = 16f
        const val OFFLINE_MAX_TEMPERATURE_C = 28f
        const val OFFLINE_TEMPERATURE_STEP_C = 1f
        const val OFFLINE_DEFAULT_FAN_LEVEL = 3
        const val OFFLINE_MAX_FAN_LEVEL = 5
    }
}
