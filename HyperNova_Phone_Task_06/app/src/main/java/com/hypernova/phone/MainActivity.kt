package com.hypernova.phone

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telecom.CallAudioState
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hypernova.phone.data.PhoneRepository
import com.hypernova.phone.databinding.ActivityMainBinding
import com.hypernova.phone.domain.PhoneScreen
import com.hypernova.phone.domain.RecentFilter
import com.hypernova.phone.telecom.HyperNovaInCallService
import com.hypernova.phone.telecom.TelecomCallController
import com.hypernova.phone.ui.PhoneScreenRenderer
import com.hypernova.phone.ui.PhoneViewModel
import kotlinx.coroutines.launch

class MainActivity :
    androidx.appcompat.app.AppCompatActivity(),
    PhoneScreenRenderer.Actions {

    private lateinit var binding: ActivityMainBinding

    private lateinit var renderer: PhoneScreenRenderer

    private val isAutomotive: Boolean by lazy {
        packageManager.hasSystemFeature(
            PackageManager.FEATURE_AUTOMOTIVE
        )
    }

    private val telecom by lazy {
        TelecomCallController(
            this
        )
    }

    private val repository by lazy {
        PhoneRepository(
            this,
            lifecycleScope
        )
    }

    private val viewModel:
        PhoneViewModel by viewModels {

        PhoneViewModel.Factory(
            repository,
            telecom
        )
    }

    /*
     * Runtime permission UI is kept only for normal Android handset
     * development.
     *
     * On Android Automotive OS the HyperNova product image owns the
     * permission policy through /system_ext/etc/default-permissions.
     *
     * Therefore AAOS must never display app-triggered runtime permission
     * dialogs for the HyperNova Phone application.
     */
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            viewModel.onCapabilityChanged()
        }

    private val roleLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            viewModel.onCapabilityChanged()
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        /*
         * HyperNova Phone never owns a separate theme preference.
         *
         * AAOS/System UI is the single source of truth:
         *
         * System Day   -> Phone Light
         * System Night -> Phone Dark
         */
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )

        super.onCreate(
            savedInstanceState
        )

        enableEdgeToEdge()

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(
            binding.root
        )

        /*
         * Automotive HOME button.
         *
         * Do not hard-code com.hypernova.launcher here.
         *
         * Android resolves CATEGORY_HOME to the product HOME activity.
         * On the HyperNova image that activity is HyperNova Launcher.
         */
        binding.iviHomeButton.setOnClickListener {
            openSystemHome()
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.main
        ) { view, insets ->

            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            insets
        }

        renderer =
            PhoneScreenRenderer(
                binding,
                this
            )

        lifecycleScope.launch {

            repeatOnLifecycle(
                androidx.lifecycle.Lifecycle.State.STARTED
            ) {

                viewModel.uiState.collect {
                    renderer.render(
                        it
                    )
                }
            }
        }

        handleTelecomIntent(
            intent
        )
    }

    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        handleTelecomIntent(
            intent
        )
    }

    override fun onStart() {

        super.onStart()

        viewModel.start()
    }

    override fun onStop() {

        viewModel.stop()

        super.onStop()
    }

    override fun navigate(
        screen: PhoneScreen
    ) =
        viewModel.navigate(
            screen
        )

    override fun requestBluetooth() =
        requestManagedPermission(
            Manifest.permission.BLUETOOTH_CONNECT
        )

    override fun requestContacts() =
        requestManagedPermission(
            Manifest.permission.READ_CONTACTS
        )

    override fun requestHistory() =
        requestManagedPermission(
            Manifest.permission.READ_CALL_LOG
        )

    override fun requestCallPermission() =
        requestManagedPermission(
            Manifest.permission.CALL_PHONE
        )

    override fun requestDialerRole() {

        /*
         * ROLE_DIALER is useful for standalone handset Telecom testing.
         *
         * The production HyperNova AAOS image must not interrupt the IVI
         * experience with Android role-selection UI. Final vehicle calls
         * are provided by the AAOS Bluetooth/HFP integration layer.
         */
        if (isAutomotive) {

            Log.i(
                TAG,
                "Ignoring ROLE_DIALER request on AAOS; role UI is disabled"
            )

            viewModel.onCapabilityChanged()

            return
        }

        val manager =
            getSystemService(
                RoleManager::class.java
            )

        if (
            manager?.isRoleAvailable(
                RoleManager.ROLE_DIALER
            ) == true &&
            !manager.isRoleHeld(
                RoleManager.ROLE_DIALER
            )
        ) {

            roleLauncher.launch(
                manager.createRequestRoleIntent(
                    RoleManager.ROLE_DIALER
                )
            )

        } else {

            toast(
                "Dialer role is unavailable or already held"
            )
        }
    }

    override fun appendDigit(
        value: String
    ) =
        viewModel.appendDigit(
            value
        )

    override fun deleteDigit() =
        viewModel.deleteDigit()

    override fun placeCall() =
        report(
            viewModel.placeCall()
        )

    override fun callNumber(
        number: String
    ) =
        report(
            viewModel.callNumber(
                number
            )
        )

    override fun selectRecentFilter(
        filter: RecentFilter
    ) =
        viewModel.selectRecentFilter(
            filter
        )

    override fun answer() =
        report(
            viewModel.answer()
        )

    override fun decline() =
        report(
            viewModel.decline()
        )

    override fun endCall() =
        report(
            viewModel.endCall()
        )

    override fun holdOrResume(
        held: Boolean
    ) =
        report(
            viewModel.holdOrResume(
                held
            )
        )

    override fun toggleMute() =
        report(
            viewModel.toggleMute()
        )

    override fun toggleSpeaker() =
        report(
            viewModel.toggleSpeaker()
        )

    override fun toggleInCallKeypad() =
        viewModel.toggleInCallKeypad()

    override fun sendDtmf(
        value: String
    ) =
        report(
            viewModel.sendDtmf(
                value
            ),
            showSuccessToast = false
        )

    override fun showAudioRoutes() {

        val routes =
            listOf(
                AudioRouteChoice(
                    label = "Phone / Earpiece",
                    route =
                        CallAudioState.ROUTE_EARPIECE
                ),
                AudioRouteChoice(
                    label = "Bluetooth",
                    route =
                        CallAudioState.ROUTE_BLUETOOTH
                ),
                AudioRouteChoice(
                    label = "Wired headset",
                    route =
                        CallAudioState.ROUTE_WIRED_HEADSET
                ),
                AudioRouteChoice(
                    label = "Speaker",
                    route =
                        CallAudioState.ROUTE_SPEAKER
                )
            )

        AlertDialog.Builder(this)
            .setTitle(
                "Call audio output"
            )
            .setItems(
                routes
                    .map {
                        it.label
                    }
                    .toTypedArray()
            ) { dialog, which ->

                val choice =
                    routes[which]

                report(
                    viewModel.selectAudioRoute(
                        choice.route
                    )
                )

                dialog.dismiss()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    /**
     * Return to the HOME activity selected by Android.
     *
     * HyperNova Launcher remains the product HOME.
     *
     * Keeping this generic avoids creating a direct dependency from
     * HyperNova Phone to a launcher package/class name.
     */
    private fun openSystemHome() {

        val homeIntent =
            Intent(
                Intent.ACTION_MAIN
            ).apply {

                addCategory(
                    Intent.CATEGORY_HOME
                )

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }

        try {

            startActivity(
                homeIntent
            )

        } catch (
            exception: Exception
        ) {

            Log.e(
                TAG,
                "Unable to open Android HOME",
                exception
            )

            toast(
                "Home is unavailable"
            )
        }
    }

    private fun handleTelecomIntent(
        intent: Intent?
    ) {

        val showDialpad =
            intent?.getBooleanExtra(
                HyperNovaInCallService.EXTRA_SHOW_IN_CALL_DIALPAD,
                false
            ) ?: false

        if (
            showDialpad
        ) {

            viewModel.setInCallKeypadVisible(
                true
            )
        }
    }

    /**
     * Permission handling differs by runtime environment.
     *
     * Handset:
     *   Keep standard Android permission UI for standalone development.
     *
     * AAOS:
     *   Never open runtime permission UI.
     *   HyperNova permissions are provisioned by the AOSP product policy.
     */
    private fun requestManagedPermission(
        permission: String
    ) {

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) ==
                PackageManager.PERMISSION_GRANTED

        if (
            granted
        ) {

            viewModel.onCapabilityChanged()

            return
        }

        if (
            isAutomotive
        ) {

            Log.w(
                TAG,
                "AAOS permission is missing from product policy: $permission"
            )

            /*
             * Deliberately do NOT invoke permissionLauncher here.
             *
             * A production IVI must not display Android runtime permission
             * dialogs. A missing grant on AAOS is an image/configuration
             * problem and must be fixed in the AOSP product.
             */
            viewModel.onCapabilityChanged()

            return
        }

        permissionLauncher.launch(
            arrayOf(
                permission
            )
        )
    }

    private fun report(
        result:
            TelecomCallController.CommandResult,
        showSuccessToast: Boolean = true
    ) {

        when (
            result
        ) {

            TelecomCallController.CommandResult.Dispatched -> {

                if (
                    showSuccessToast
                ) {

                    toast(
                        "Request sent to Android Telecom; waiting for confirmation"
                    )
                }
            }

            is TelecomCallController.CommandResult.Rejected -> {

                toast(
                    result.reason
                )
            }
        }
    }

    private fun toast(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private data class AudioRouteChoice(
        val label: String,
        val route: Int
    )

    companion object {

        private const val TAG =
            "HyperNovaPhone"
    }
}
