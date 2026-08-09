/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.android.car.settings.hypernova;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.hypernova.settings.R;
import com.android.car.settings.common.BaseFragment;
import com.android.car.settings.wifi.CarWifiManager;
import com.android.car.settings.wifi.WifiPasswordDialog;
import com.android.wifitrackerlib.WifiEntry;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-width HyperNova Wi-Fi UI backed by the real AAOS Wi-Fi framework.
 */
public final class HyperNovaWifiSettingsFragment extends BaseFragment
        implements CarWifiManager.Listener {

    private static final int SECURITY_OPEN = 0;
    private static final int SECURITY_WPA2 = 1;
    private static final int SECURITY_WPA3 = 2;

    private CarWifiManager mCarWifiManager;

    /*
     * WifiTrackerLib may temporarily publish an empty or unchanged list while
     * a new scan is running. Keep the last useful list visible so the screen
     * does not flash or return to an empty/loading state on every refresh.
     */
    private static final int MAX_TRANSIENT_EMPTY_WIFI_REFRESHES = 2;

    /*
     * WifiTrackerLib can occasionally omit one otherwise healthy AP from a
     * single scan snapshot. Keep the last seen WifiEntry briefly so one
     * incomplete scan does not make an individual row disappear and then
     * reappear on the next callback.
     */
    private static final long AVAILABLE_NETWORK_CACHE_TTL_MS =
            20_000L;

    private int mConsecutiveEmptyAvailableRefreshes;
    private String mLastAvailableNetworksSignature = "";

    private final Map<String, CachedAvailableWifiEntry> mAvailableNetworkCache =
            new LinkedHashMap<>();

    private final Runnable mAvailableNetworkCacheExpiryRunnable =
            () -> {

                if (isAdded()) {
                    refreshWifiUi();
                }
            };

    private WifiManager mWifiManager;
    private HyperNovaConnectivityClient mConnectivityClient;

    private static final long MANUAL_SCAN_TIMEOUT_MS =
            8_000L;

    private static final long MANUAL_SCAN_MIN_INTERVAL_MS =
            5_000L;

    private static final long REFRESH_MESSAGE_DURATION_MS =
            2_500L;

    private static final long WIFI_CONNECTION_TIMEOUT_MS =
            25_000L;

    /*
     * ConnectivityService and WifiNetworkFactory process network
     * requests asynchronously. Wait briefly before issuing the explicit
     * WifiEntry connection command.
     */
    private static final long WIFI_REQUEST_SETTLE_DELAY_MS =
            1_200L;

    private final Handler mMainHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable mConnectionTimeoutRunnable =
            this::handlePendingConnectionTimeout;

    private View mWifiRefreshButton;
    private ImageView mWifiRefreshIcon;
    private TextView mWifiRefreshText;
    private TextView mAvailableEmptyState;

    @Nullable
    private ObjectAnimator mWifiRefreshAnimator;

    private boolean mWifiScanReceiverRegistered;
    private boolean mManualScanInProgress;
    private boolean mHasReceivedWifiEntriesCallback;

    private long mLastManualScanElapsedRealtime =
            Long.MIN_VALUE;

    private View mWifiCard;
    private Switch mWifiSwitch;
    private TextView mWifiSummary;

    private View mConnectedSection;
    private LinearLayout mConnectedContainer;

    private View mAvailableSection;
    private LinearLayout mAvailableContainer;

    private View mAddNetwork;

    private boolean mUpdatingSwitch;

    @Nullable
    private WifiConfiguration mPendingManualConfiguration;

    @Nullable
    private Dialog mPendingManualDialog;

    @Nullable
    private TextView mPendingManualConnectButton;

    @Nullable
    private String mPendingConnectionKey;

    @Nullable
    private String mPendingManualSsid;

    @Nullable
    private String mConnectionFailureKey;

    private final BroadcastReceiver mWifiScanReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    if (!WifiManager
                            .SCAN_RESULTS_AVAILABLE_ACTION
                            .equals(intent.getAction())) {

                        return;
                    }

                    boolean resultsUpdated =
                            intent.getBooleanExtra(
                                    WifiManager
                                            .EXTRA_RESULTS_UPDATED,
                                    false);

                    refreshWifiUi();

                    if (mManualScanInProgress) {

                        finishManualScan(
                                resultsUpdated);
                    }
                }
            };

    private final Runnable mManualScanTimeoutRunnable =
            () -> finishManualScan(false);

    private final Runnable mRestoreRefreshLabelRunnable =
            () -> {

                if (mWifiRefreshText != null
                        && !mManualScanInProgress) {

                    mWifiRefreshText.setText(
                            R.string
                                    .hypernova_wifi_refresh);
                }
            };

    private final WifiPasswordDialog.WifiDialogListener mPasswordListener =
            new WifiPasswordDialog.WifiDialogListener() {

                @Override
                public void onSubmit(WifiPasswordDialog dialog) {

                    WifiConfiguration config =
                            dialog.getConfig();

                    WifiEntry entry =
                            dialog.getWifiEntry();

                    if (config != null) {

                        mPendingManualSsid =
                                unquoteWifiValue(config.SSID);

                        connectManualNetwork(
                                config,
                                /* dialog= */ null,
                                /* connectButton= */ null);

                    } else {

                        connectEntry(
                                entry,
                                false);
                    }
                }
            };

    @Override
    protected int getLayoutId() {
        return R.layout.hypernova_wifi_fragment;
    }

    @Override
    @StringRes
    protected int getTitleId() {
        return R.string.hypernova_wifi;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCarWifiManager =
                new CarWifiManager(
                        requireContext(),
                        getLifecycle());

        mWifiManager =
                requireContext().getSystemService(
                        WifiManager.class);
    
        mConnectivityClient =
                new HyperNovaConnectivityClient(
                        requireContext(),
                        this::refreshWifiUi);
}

    @Override
    public void onViewCreated(
            @NonNull View view,
            Bundle savedInstanceState) {

        super.onViewCreated(
                view,
                savedInstanceState);

        HyperNovaSettingsActivities.configureHeader(
                this,
                R.string.hypernova_wifi,
                /* showBack= */ true);

        mWifiCard =
                view.findViewById(
                        R.id.hypernova_wifi_state_card);

        mWifiSwitch =
                view.findViewById(
                        R.id.hypernova_wifi_state_switch);

        mWifiSummary =
                view.findViewById(
                        R.id.hypernova_wifi_state_summary);

        mConnectedSection =
                view.findViewById(
                        R.id.hypernova_connected_section);

        mConnectedContainer =
                view.findViewById(
                        R.id.hypernova_connected_network_container);

        mAvailableSection =
                view.findViewById(
                        R.id.hypernova_available_section);

        mAvailableContainer =
                view.findViewById(
                        R.id.hypernova_available_networks_container);

        mWifiRefreshButton =
                view.findViewById(
                        R.id.hypernova_wifi_refresh_button);

        mWifiRefreshIcon =
                view.findViewById(
                        R.id.hypernova_wifi_refresh_icon);

        mWifiRefreshText =
                view.findViewById(
                        R.id.hypernova_wifi_refresh_text);

        mAvailableEmptyState =
                view.findViewById(
                        R.id.hypernova_wifi_available_empty_state);

        mWifiRefreshAnimator = null;

        mAddNetwork =
                view.findViewById(
                        R.id.hypernova_add_network);

        mWifiSwitch.setOnCheckedChangeListener(
                (button, checked) -> {

                    if (mUpdatingSwitch) {
                        return;
                    }

                    setWifiEnabled(checked);
                });

        mWifiCard.setOnClickListener(
                clicked -> {

                    if (!mWifiSwitch.isEnabled()) {
                        return;
                    }

                    mWifiSwitch.setChecked(
                            !mWifiSwitch.isChecked());
                });

        /*
         * Do NOT launch the stock AddWifiFragment.
         * Keep the complete add-network flow inside HyperNova UI.
         */
        mAddNetwork.setOnClickListener(
                clicked ->
                        showAddNetworkDialog());

        mWifiRefreshButton.setOnClickListener(
                clicked ->
                        requestManualWifiScan());

        updateRefreshButtonAvailability(
                mCarWifiManager.getWifiState());
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mConnectivityClient != null) {
            mConnectivityClient.bind();
        }
        /*
         * Register before the user presses Connect so WifiNetworkFactory
         * has an active Wi-Fi request when WifiEntry.connect() runs.
         */
        if (mWifiManager.isWifiEnabled()) {

            HyperNovaWifiTransportRequest.acquire(
                    requireContext());
        }


        mHasReceivedWifiEntriesCallback = false;

        mCarWifiManager.addListener(this);

        registerWifiScanReceiver();

        refreshWifiUi();
    }

    @Override
    public void onStop() {

        mCarWifiManager.removeListener(this);

        unregisterWifiScanReceiver();

        stopManualScanUi();

        mMainHandler.removeCallbacks(
                mAvailableNetworkCacheExpiryRunnable);

        mAvailableNetworkCache.clear();

        mMainHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        mPendingConnectionKey = null;
        mPendingManualSsid = null;

        clearPendingManualConnection();

                if (mConnectivityClient != null) {
            mConnectivityClient.unbind();
        }

super.onStop();
    }

    @Override
    public void onWifiEntriesChanged() {

        mHasReceivedWifiEntriesCallback = true;

        refreshWifiUi();
    }

    @Override
    public void onWifiStateChanged(int state) {

        if (state == WifiManager.WIFI_STATE_DISABLED
                || state == WifiManager.WIFI_STATE_DISABLING) {

            HyperNovaWifiTransportRequest.release();

            mMainHandler.removeCallbacks(
                    mConnectionTimeoutRunnable);

            mPendingConnectionKey = null;
            mPendingManualSsid = null;
        }

        refreshWifiUi();

        if (state == WifiManager.WIFI_STATE_ENABLED
                && mPendingManualConfiguration != null) {

            connectPendingManualNetwork();
        }
    }

    // ============================================================
    // Manual Wi-Fi refresh
    // ============================================================

    private void registerWifiScanReceiver() {

        if (mWifiScanReceiverRegistered
                || !isAdded()) {

            return;
        }

        IntentFilter filter =
                new IntentFilter(
                        WifiManager
                                .SCAN_RESULTS_AVAILABLE_ACTION);

        try {

            requireContext().registerReceiver(
                    mWifiScanReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED);

            mWifiScanReceiverRegistered = true;

        } catch (RuntimeException exception) {

            mWifiScanReceiverRegistered = false;
        }
    }

    private void unregisterWifiScanReceiver() {

        if (!mWifiScanReceiverRegistered
                || !isAdded()) {

            return;
        }

        try {

            requireContext().unregisterReceiver(
                    mWifiScanReceiver);

        } catch (RuntimeException ignored) {

            // Receiver teardown can race with Activity destruction.

        } finally {

            mWifiScanReceiverRegistered = false;
        }
    }

    private void requestManualWifiScan() {

        if (mManualScanInProgress
                || mWifiManager == null) {

            return;
        }

        int wifiState =
                mCarWifiManager.getWifiState();

        if (wifiState
                != WifiManager.WIFI_STATE_ENABLED) {

            updateRefreshButtonAvailability(
                    wifiState);

            return;
        }

        long now =
                SystemClock.elapsedRealtime();

        if (mLastManualScanElapsedRealtime
                != Long.MIN_VALUE
                && now
                - mLastManualScanElapsedRealtime
                < MANUAL_SCAN_MIN_INTERVAL_MS) {

            showTemporaryRefreshMessage(
                    R.string
                            .hypernova_wifi_scan_unavailable);

            return;
        }

        beginManualScanUi();

        boolean requestAccepted;

        try {

            requestAccepted =
                    mConnectivityClient.requestWifiScan();

        } catch (RuntimeException exception) {

            requestAccepted = false;
        }

        if (!requestAccepted) {

            finishManualScan(false);

            return;
        }

        mLastManualScanElapsedRealtime =
                now;
    }

    private void beginManualScanUi() {

        mManualScanInProgress = true;

        mMainHandler.removeCallbacks(
                mRestoreRefreshLabelRunnable);

        mMainHandler.removeCallbacks(
                mManualScanTimeoutRunnable);

        if (mWifiRefreshButton != null) {

            mWifiRefreshButton.setEnabled(false);
            mWifiRefreshButton.setAlpha(1f);
        }

        if (mWifiRefreshText != null) {

            mWifiRefreshText.setText(
                    R.string
                            .hypernova_wifi_scanning);
        }

        startRefreshAnimation();

        if (mAvailableContainer != null
                && mAvailableContainer.getChildCount() == 0
                && mAvailableEmptyState != null) {

            mAvailableSection.setVisibility(
                    View.VISIBLE);

            mAvailableEmptyState.setText(
                    R.string
                            .hypernova_wifi_scanning);

            mAvailableEmptyState.setVisibility(
                    View.VISIBLE);
        }

        mMainHandler.postDelayed(
                mManualScanTimeoutRunnable,
                MANUAL_SCAN_TIMEOUT_MS);
    }

    private void finishManualScan(
            boolean resultsUpdated) {

        boolean wasScanning =
                mManualScanInProgress;

        mManualScanInProgress = false;

        mMainHandler.removeCallbacks(
                mManualScanTimeoutRunnable);

        stopRefreshAnimation();

        updateRefreshButtonAvailability(
                mCarWifiManager.getWifiState());

        if (mAvailableContainer != null
                && mAvailableContainer.getChildCount() == 0
                && mAvailableEmptyState != null) {

            mAvailableSection.setVisibility(
                    View.VISIBLE);

            mAvailableEmptyState.setText(
                    R.string
                            .hypernova_wifi_no_networks);

            mAvailableEmptyState.setVisibility(
                    View.VISIBLE);
        }

        if (wasScanning
                && !resultsUpdated) {

            showTemporaryRefreshMessage(
                    R.string
                            .hypernova_wifi_scan_unavailable);

        } else if (mWifiRefreshText != null) {

            mWifiRefreshText.setText(
                    R.string
                            .hypernova_wifi_refresh);
        }
    }

    private void stopManualScanUi() {

        mManualScanInProgress = false;

        mMainHandler.removeCallbacks(
                mManualScanTimeoutRunnable);

        mMainHandler.removeCallbacks(
                mRestoreRefreshLabelRunnable);

        stopRefreshAnimation();

        if (mWifiRefreshText != null) {

            mWifiRefreshText.setText(
                    R.string
                            .hypernova_wifi_refresh);
        }
    }

    private void startRefreshAnimation() {

        if (mWifiRefreshIcon == null) {
            return;
        }

        stopRefreshAnimation();

        mWifiRefreshAnimator =
                ObjectAnimator.ofFloat(
                        mWifiRefreshIcon,
                        View.ROTATION,
                        0f,
                        360f);

        mWifiRefreshAnimator.setDuration(
                780L);

        mWifiRefreshAnimator.setRepeatCount(
                ValueAnimator.INFINITE);

        mWifiRefreshAnimator.setInterpolator(
                new LinearInterpolator());

        mWifiRefreshAnimator.start();
    }

    private void stopRefreshAnimation() {

        if (mWifiRefreshAnimator != null) {

            mWifiRefreshAnimator.cancel();
            mWifiRefreshAnimator = null;
        }

        if (mWifiRefreshIcon != null) {

            mWifiRefreshIcon.setRotation(
                    0f);
        }
    }

    private void showTemporaryRefreshMessage(
            @StringRes int message) {

        if (mWifiRefreshText == null) {
            return;
        }

        mMainHandler.removeCallbacks(
                mRestoreRefreshLabelRunnable);

        mWifiRefreshText.setText(
                message);

        mMainHandler.postDelayed(
                mRestoreRefreshLabelRunnable,
                REFRESH_MESSAGE_DURATION_MS);
    }

    private void updateRefreshButtonAvailability(
            int wifiState) {

        if (mWifiRefreshButton == null) {
            return;
        }

        boolean wifiEnabled =
                wifiState
                        == WifiManager
                        .WIFI_STATE_ENABLED;

        if (!wifiEnabled
                && mManualScanInProgress) {

            stopManualScanUi();
        }

        mWifiRefreshButton.setEnabled(
                wifiEnabled
                        && !mManualScanInProgress);

        mWifiRefreshButton.setAlpha(
                wifiEnabled
                        ? 1f
                        : 0.42f);
    }

    // ============================================================
    // Wi-Fi state
    // ============================================================

    private void setWifiEnabled(boolean enabled) {

        boolean accepted;

        if (!enabled) {

            HyperNovaWifiTransportRequest.release();

            mMainHandler.removeCallbacks(
                    mConnectionTimeoutRunnable);

            mPendingConnectionKey = null;
            mPendingManualSsid = null;
        }

        try {

            accepted =
                    mConnectivityClient.setWifiEnabled(
                            enabled);

        } catch (RuntimeException exception) {

            accepted = false;
        }

        if (!accepted) {

            Toast.makeText(
                    requireContext(),
                    R.string.hypernova_state_unavailable,
                    Toast.LENGTH_SHORT).show();

            refreshWifiUi();

            return;
        }

        mWifiSummary.setText(
                enabled
                        ? R.string.hypernova_state_turning_on
                        : R.string.hypernova_state_turning_off);

        mWifiSwitch.setEnabled(false);
    }

    private void refreshWifiUi() {

        if (!isAdded()
                || mWifiSwitch == null) {

            return;
        }

        int state =
                mCarWifiManager.getWifiState();

        mUpdatingSwitch = true;

        switch (state) {

            case WifiManager.WIFI_STATE_ENABLED:

                mWifiSwitch.setChecked(true);
                mWifiSwitch.setEnabled(true);

                refreshNetworks();

                mWifiSummary.setText(
                        TextUtils.isEmpty(mPendingManualSsid)
                                ? R.string.hypernova_state_on
                                : R.string.hypernova_connecting);

                break;


            case WifiManager.WIFI_STATE_ENABLING:

                mWifiSwitch.setChecked(true);
                mWifiSwitch.setEnabled(false);

                mWifiSummary.setText(
                        R.string.hypernova_state_turning_on);

                hideNetworks();

                break;


            case WifiManager.WIFI_STATE_DISABLING:

                mWifiSwitch.setChecked(false);
                mWifiSwitch.setEnabled(false);

                mWifiSummary.setText(
                        R.string.hypernova_state_turning_off);

                hideNetworks();

                break;


            case WifiManager.WIFI_STATE_DISABLED:

                mWifiSwitch.setChecked(false);
                mWifiSwitch.setEnabled(true);

                mWifiSummary.setText(
                        R.string.hypernova_state_off);

                hideNetworks();

                break;


            default:

                mWifiSwitch.setChecked(false);
                mWifiSwitch.setEnabled(true);

                mWifiSummary.setText(
                        R.string.hypernova_state_unavailable);

                hideNetworks();

                break;
        }

        updateRefreshButtonAvailability(
                state);

        mUpdatingSwitch = false;
    }

    // ============================================================
    // Connected / available networks
    // ============================================================

    private void refreshNetworks() {

        refreshConnectedNetwork();

        refreshAvailableNetworks();
    }

    private void refreshConnectedNetwork() {

        mConnectedContainer.removeAllViews();

        List<WifiEntry> connectedEntries =
                mCarWifiManager.getConnectedWifiEntries();

        WifiEntry entry =
                connectedEntries == null
                        || connectedEntries.isEmpty()
                        ? findConnectingWifiEntry()
                        : connectedEntries.get(0);

        if (entry == null) {

            mConnectedSection.setVisibility(
                    View.GONE);

            return;
        }

        View row =
                LayoutInflater.from(
                        requireContext())
                        .inflate(
                                R.layout.hypernova_wifi_connected_item,
                                mConnectedContainer,
                                false);

        TextView title =
                row.findViewById(
                        R.id.hypernova_wifi_item_title);

        TextView summary =
                row.findViewById(
                        R.id.hypernova_wifi_item_summary);

        title.setText(
                entry.getTitle());

        summary.setText(
                getConnectionSummary(entry));

        row.setOnClickListener(
                clicked -> showNetworkActionDialog(entry));

        mConnectedContainer.addView(
                row);

        mConnectedSection.setVisibility(
                View.VISIBLE);
    }

    private void refreshAvailableNetworks() {

        List<WifiEntry> entries =
                mCarWifiManager.getAllWifiEntries();

        List<WifiEntry> connected =
                mCarWifiManager.getConnectedWifiEntries();

        WifiEntry connectedEntry =
                connected == null
                        || connected.isEmpty()
                        ? null
                        : connected.get(0);

        String connectedKey =
                connectedEntry == null
                        ? null
                        : connectedEntry.getKey();

        long now =
                SystemClock.elapsedRealtime();

        Set<String> currentVisibleKeys =
                new HashSet<>();

        Set<String> suppressedKeys =
                new HashSet<>();

        if (entries != null) {

            for (WifiEntry entry : entries) {

                if (entry == null
                        || TextUtils.isEmpty(entry.getKey())) {

                    continue;
                }

                String key =
                        entry.getKey();

                boolean connectedNow =
                        connectedKey != null
                                && connectedKey.equals(key);

                boolean unavailableForList =
                        entry.getConnectedState()
                                != WifiEntry
                                .CONNECTED_STATE_DISCONNECTED
                                || isPendingEntry(entry);

                if (connectedNow
                        || unavailableForList) {

                    suppressedKeys.add(key);
                    mAvailableNetworkCache.remove(key);
                    continue;
                }

                if (!currentVisibleKeys.add(key)) {
                    continue;
                }

                mAvailableNetworkCache.put(
                        key,
                        new CachedAvailableWifiEntry(
                                entry,
                                now));
            }
        }

        if (connectedKey != null) {
            suppressedKeys.add(connectedKey);
            mAvailableNetworkCache.remove(connectedKey);
        }

        if (mPendingConnectionKey != null) {
            suppressedKeys.add(mPendingConnectionKey);
            mAvailableNetworkCache.remove(mPendingConnectionKey);
        }

        mMainHandler.removeCallbacks(
                mAvailableNetworkCacheExpiryRunnable);

        long nextExpiryDelayMs =
                Long.MAX_VALUE;

        Iterator<Map.Entry<String, CachedAvailableWifiEntry>> iterator =
                mAvailableNetworkCache.entrySet().iterator();

        while (iterator.hasNext()) {

            Map.Entry<String, CachedAvailableWifiEntry> item =
                    iterator.next();

            String key =
                    item.getKey();

            CachedAvailableWifiEntry cached =
                    item.getValue();

            if (suppressedKeys.contains(key)) {
                iterator.remove();
                continue;
            }

            if (currentVisibleKeys.contains(key)) {
                continue;
            }

            long ageMs =
                    Math.max(
                            0L,
                            now - cached.lastSeenElapsedRealtimeMs);

            if (ageMs >= AVAILABLE_NETWORK_CACHE_TTL_MS) {

                iterator.remove();
                continue;
            }

            nextExpiryDelayMs =
                    Math.min(
                            nextExpiryDelayMs,
                            AVAILABLE_NETWORK_CACHE_TTL_MS
                                    - ageMs
                                    + 50L);
        }

        if (nextExpiryDelayMs != Long.MAX_VALUE) {

            mMainHandler.postDelayed(
                    mAvailableNetworkCacheExpiryRunnable,
                    nextExpiryDelayMs);
        }

        List<WifiEntry> visibleEntries =
                new java.util.ArrayList<>();

        for (CachedAvailableWifiEntry cached
                : mAvailableNetworkCache.values()) {

            visibleEntries.add(
                    cached.entry);
        }

        /*
         * A scan can momentarily publish an empty list while WifiTrackerLib
         * replaces its previous snapshot. The per-entry cache above protects
         * partial scans; keep this whole-list guard as a second line of
         * defence for a completely empty transient callback.
         */
        if (visibleEntries.isEmpty()) {

            mConsecutiveEmptyAvailableRefreshes++;

            if (mAvailableContainer.getChildCount() > 0
                    && mConsecutiveEmptyAvailableRefreshes
                    <= MAX_TRANSIENT_EMPTY_WIFI_REFRESHES) {

                mAvailableSection.setVisibility(
                        View.VISIBLE);

                mAvailableEmptyState.setVisibility(
                        View.GONE);

                return;
            }

            mAvailableContainer.removeAllViews();

            mLastAvailableNetworksSignature = "";

            mAvailableSection.setVisibility(
                    View.VISIBLE);

            mAvailableEmptyState.setText(
                    mManualScanInProgress
                            || !mHasReceivedWifiEntriesCallback
                            ? R.string
                                    .hypernova_wifi_scanning
                            : R.string
                                    .hypernova_wifi_no_networks);

            mAvailableEmptyState.setVisibility(
                    View.VISIBLE);

            return;
        }

        mConsecutiveEmptyAvailableRefreshes = 0;

        mAvailableEmptyState.setVisibility(
                View.GONE);

        StringBuilder signatureBuilder =
                new StringBuilder();

        for (WifiEntry entry : visibleEntries) {

            CharSequence summary =
                    getConnectionSummary(entry);

            signatureBuilder
                    .append(entry.getKey())
                    .append('|')
                    .append(entry.getTitle())
                    .append('|')
                    .append(summary == null ? "" : summary)
                    .append(';');
        }

        String signature =
                signatureBuilder.toString();

        /*
         * Scan callbacks may arrive repeatedly with identical data. Avoid
         * removing and inflating every row again when nothing actually
         * changed.
         */
        if (signature.equals(mLastAvailableNetworksSignature)
                && mAvailableContainer.getChildCount() > 0) {

            mAvailableSection.setVisibility(
                    View.VISIBLE);

            return;
        }

        mAvailableContainer.removeAllViews();

        int added = 0;

        for (WifiEntry entry : visibleEntries) {

            if (added > 0) {
                addDivider();
            }

            addAvailableNetworkRow(
                    entry);

            added++;
        }

        mLastAvailableNetworksSignature =
                signature;

        mAvailableSection.setVisibility(
                View.VISIBLE);
    }

    private void addAvailableNetworkRow(
            WifiEntry entry) {

        View row =
                LayoutInflater.from(
                        requireContext())
                        .inflate(
                                R.layout.hypernova_wifi_network_item,
                                mAvailableContainer,
                                false);

        TextView title =
                row.findViewById(
                        R.id.hypernova_wifi_item_title);

        TextView summary =
                row.findViewById(
                        R.id.hypernova_wifi_item_summary);

        title.setText(
                entry.getTitle());

        CharSequence entrySummary =
                getConnectionSummary(entry);

        if (entrySummary == null
                || entrySummary.toString()
                .trim()
                .isEmpty()) {

            summary.setVisibility(
                    View.GONE);

        } else {

            summary.setVisibility(
                    View.VISIBLE);

            summary.setText(
                    entrySummary);
        }

        row.setOnClickListener(
                clicked ->
                        handleNetworkClick(entry));

        mAvailableContainer.addView(
                row);
    }

    private void handleNetworkClick(
            WifiEntry entry) {

        if (entry.isSaved()) {

            showNetworkActionDialog(entry);

            return;
        }

        if (entry.shouldEditBeforeConnect()) {

            showPasswordDialog(entry);

            return;
        }

        connectEntry(
                entry,
                true);
    }

    private void connectEntry(
            WifiEntry entry,
            boolean editIfNoConfig) {

        if (entry == null) {
            return;
        }

        if (!HyperNovaWifiTransportRequest.acquire(
                requireContext())) {

            recordConnectionFailure(entry);
            refreshWifiUi();

            return;
        }

        final String requestedKey =
                entry.getKey();

        mPendingConnectionKey =
                requestedKey;

        mConnectionFailureKey =
                null;

        beginConnectionTimeout();

        refreshWifiUi();

        /*
         * requestNetwork() is asynchronous. Calling WifiEntry.connect()
         * immediately can reach ClientModeImpl before WifiNetworkFactory
         * has counted the request, producing the old no-request failure.
         */
        mMainHandler.postDelayed(
                () -> {

                    if (!isAdded()
                            || mPendingConnectionKey == null
                            || !requestedKey.equals(
                                    mPendingConnectionKey)) {

                        return;
                    }

                    try {

                        mConnectivityClient.connect(entry, 
                                status -> {

                                    if (!isAdded()) {
                                        return;
                                    }

                                    if (status
                                            == WifiEntry.ConnectCallback
                                            .CONNECT_STATUS_SUCCESS) {

                                        /*
                                         * This means the command was
                                         * accepted. WifiTracker will clear
                                         * the pending state only after the
                                         * network becomes CONNECTED.
                                         */
                                        refreshWifiUi();

                                    } else if (status
                                            == WifiEntry.ConnectCallback
                                            .CONNECT_STATUS_FAILURE_NO_CONFIG) {

                                        mMainHandler.removeCallbacks(
                                                mConnectionTimeoutRunnable);

                                        clearPendingConnection(
                                                entry);

                                        HyperNovaWifiTransportRequest
                                                .release();

                                        if (editIfNoConfig) {

                                            showPasswordDialog(
                                                    entry);
                                        }

                                        refreshWifiUi();

                                    } else if (status
                                            == WifiEntry.ConnectCallback
                                            .CONNECT_STATUS_FAILURE_UNKNOWN
                                            || status
                                            == WifiEntry.ConnectCallback
                                            .CONNECT_STATUS_FAILURE_SIM_ABSENT) {

                                        recordConnectionFailure(
                                                entry);

                                        refreshWifiUi();
                                    }
                                });

                    } catch (RuntimeException exception) {

                        recordConnectionFailure(
                                entry);

                        refreshWifiUi();
                    }
                },
                WIFI_REQUEST_SETTLE_DELAY_MS);
    }

    private void showPasswordDialog(
            WifiEntry entry) {

        WifiPasswordDialog dialog =
                new WifiPasswordDialog(
                        entry,
                        mPasswordListener);

        dialog.show(
                getParentFragmentManager(),
                WifiPasswordDialog.TAG);
    }

    /**
     * Keeps connected and saved network actions in the cockpit UI instead of opening
     * CarSettings' stock WifiDetailsFragment.
     */
    private void showNetworkActionDialog(
            WifiEntry entry) {

        Dialog dialog =
                new Dialog(
                        requireContext(),
                        R.style.Theme_HyperNova_AlertDialog);

        dialog.requestWindowFeature(
                Window.FEATURE_NO_TITLE);

        dialog.setContentView(
                R.layout.hypernova_choice_dialog);

        TextView title =
                dialog.findViewById(
                        R.id.hypernova_choice_dialog_title);

        TextView subtitle =
                dialog.findViewById(
                        R.id.hypernova_choice_dialog_subtitle);

        title.setText(entry.getTitle());

        boolean connected =
                entry.getConnectedState()
                        == WifiEntry.CONNECTED_STATE_CONNECTED;

        subtitle.setText(
                connected
                        ? R.string.hypernova_wifi_connected_actions
                        : R.string.hypernova_wifi_saved_actions);

        configureActionOption(
                dialog.findViewById(
                        R.id.hypernova_choice_option_1),
                connected
                        ? R.string.hypernova_disconnect
                        : R.string.hypernova_connect,
                connected
                        ? R.string.hypernova_wifi_disconnect_summary
                        : R.string.hypernova_wifi_connect_summary,
                clicked -> {
                    if (connected) {
                        disconnectEntry(entry);
                    } else {
                        connectEntry(entry, /* editIfNoConfig= */ true);
                    }
                    dialog.dismiss();
                });

        configureActionOption(
                dialog.findViewById(
                        R.id.hypernova_choice_option_2),
                R.string.hypernova_forget,
                R.string.hypernova_wifi_forget_summary,
                clicked -> {
                    forgetEntry(entry, connected);
                    dialog.dismiss();
                });

        View unusedOption =
                dialog.findViewById(
                        R.id.hypernova_choice_option_3);

        unusedOption.setVisibility(View.GONE);

        TextView cancel =
                dialog.findViewById(
                        R.id.hypernova_choice_cancel);

        cancel.setText(R.string.hypernova_cancel);
        cancel.setOnClickListener(clicked -> dialog.dismiss());

        dialog.show();
        configureHyperNovaDialogWindow(dialog);
    }

    private void configureActionOption(
            View option,
            @StringRes int titleId,
            @StringRes int summaryId,
            View.OnClickListener listener) {

        option.setVisibility(View.VISIBLE);

        View indicator =
                option.findViewById(
                        R.id.hypernova_choice_indicator);

        if (indicator != null) {
            indicator.setVisibility(View.GONE);
        }

        TextView title =
                option.findViewById(
                        R.id.hypernova_choice_option_title);

        TextView summary =
                option.findViewById(
                        R.id.hypernova_choice_option_summary);

        title.setText(titleId);
        summary.setText(summaryId);
        option.setOnClickListener(listener);
    }

    private void disconnectEntry(
            WifiEntry entry) {

        if (!entry.canDisconnect()) {
            recordConnectionFailure(entry);
            refreshWifiUi();
            return;
        }

        HyperNovaWifiTransportRequest.release();

        mMainHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        mConnectivityClient.disconnect(
                status -> {
                    if (!isAdded()) {
                        return;
                    }

                    if (status
                            == WifiEntry.DisconnectCallback
                            .DISCONNECT_STATUS_FAILURE_UNKNOWN) {
                        recordConnectionFailure(entry);
                    } else {
                        clearPendingConnection(entry);
                    }

                    refreshWifiUi();
                });
    }

    private void forgetEntry(
            WifiEntry entry,
            boolean disconnectAfterForget) {

        if (!entry.canForget()) {
            recordConnectionFailure(entry);
            refreshWifiUi();
            return;
        }

        mConnectivityClient.forget(entry, 
                status -> {
                    if (!isAdded()) {
                        return;
                    }

                    if (status
                            == WifiEntry.ForgetCallback
                            .FORGET_STATUS_FAILURE_UNKNOWN) {
                        recordConnectionFailure(entry);
                    } else {
                        clearPendingConnection(entry);
                        if (disconnectAfterForget
                                && entry.canDisconnect()) {
                            mConnectivityClient.disconnect(/* callback= */ null);
                        }
                    }

                    refreshWifiUi();
                });
    }

    @Nullable
    private WifiEntry findConnectingWifiEntry() {

        List<WifiEntry> entries =
                mCarWifiManager.getAllWifiEntries();

        if (entries == null) {
            return null;
        }

        for (WifiEntry entry : entries) {
            if (entry != null
                    && (entry.getConnectedState()
                            == WifiEntry.CONNECTED_STATE_CONNECTING
                            || isPendingEntry(entry))) {
                return entry;
            }
        }

        return null;
    }

    private boolean isPendingEntry(
            WifiEntry entry) {

        return (mPendingConnectionKey != null
                        && mPendingConnectionKey.equals(entry.getKey()))
                || (mPendingManualSsid != null
                        && mPendingManualSsid.equals(entry.getTitle()));
    }

    private CharSequence getConnectionSummary(
            WifiEntry entry) {

        if (entry.getConnectedState()
                == WifiEntry.CONNECTED_STATE_CONNECTED) {
            clearPendingConnection(entry);
            return getString(R.string.hypernova_connected);
        }

        if (entry.getConnectedState()
                == WifiEntry.CONNECTED_STATE_CONNECTING
                || isPendingEntry(entry)) {
            return getString(R.string.hypernova_connecting);
        }

        if (mConnectionFailureKey != null
                && mConnectionFailureKey.equals(entry.getKey())) {
            return getString(R.string.hypernova_wifi_connection_failed);
        }

        if (entry.isSaved()) {
            return getString(R.string.hypernova_saved);
        }

        CharSequence frameworkSummary =
                entry.getSummary(false);

        if (!TextUtils.isEmpty(frameworkSummary)
                && frameworkSummary.toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("authentication")) {
            return getString(R.string.hypernova_wifi_authentication_failed);
        }

        return TextUtils.isEmpty(frameworkSummary)
                ? getString(R.string.hypernova_disconnected)
                : frameworkSummary;
    }

    private void clearPendingConnection(
            WifiEntry entry) {

        boolean completed =
                false;

        if (mPendingConnectionKey != null
                && mPendingConnectionKey.equals(entry.getKey())) {

            mPendingConnectionKey = null;
            completed = true;
        }

        if (mPendingManualSsid != null
                && mPendingManualSsid.equals(entry.getTitle())) {

            mPendingManualSsid = null;
            completed = true;
        }

        if (mConnectionFailureKey != null
                && mConnectionFailureKey.equals(entry.getKey())) {

            mConnectionFailureKey = null;
        }

        if (completed) {

            mMainHandler.removeCallbacks(
                    mConnectionTimeoutRunnable);
        }
    }

    private void recordConnectionFailure(
            WifiEntry entry) {

        mMainHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        mPendingConnectionKey = null;
        mPendingManualSsid = null;
        mConnectionFailureKey = entry.getKey();

        releaseWifiTransportIfDisconnected();

        Toast.makeText(
                requireContext(),
                R.string.hypernova_wifi_connection_failed,
                Toast.LENGTH_SHORT).show();
    }

    private void beginConnectionTimeout() {

        mMainHandler.removeCallbacks(
                mConnectionTimeoutRunnable);

        mMainHandler.postDelayed(
                mConnectionTimeoutRunnable,
                WIFI_CONNECTION_TIMEOUT_MS);
    }

    private void handlePendingConnectionTimeout() {

        if (mPendingConnectionKey == null
                && mPendingManualSsid == null) {

            return;
        }

        String failedKey =
                mPendingConnectionKey;

        mPendingConnectionKey = null;
        mPendingManualSsid = null;

        if (failedKey != null) {

            mConnectionFailureKey =
                    failedKey;
        }

        releaseWifiTransportIfDisconnected();

        if (isAdded()) {

            Toast.makeText(
                    requireContext(),
                    R.string.hypernova_wifi_connection_failed,
                    Toast.LENGTH_SHORT).show();

            refreshWifiUi();
        }
    }

    private void releaseWifiTransportIfDisconnected() {

        List<WifiEntry> connectedEntries =
                mCarWifiManager.getConnectedWifiEntries();

        boolean connected =
                connectedEntries != null
                        && !connectedEntries.isEmpty();

        if (!connected) {

            HyperNovaWifiTransportRequest.release();
        }
    }

    // ============================================================
    // HyperNova Add Network
    // ============================================================

    private void showAddNetworkDialog() {

        Dialog dialog =
                new Dialog(
                        requireContext(),
                        R.style.Theme_HyperNova_AlertDialog);

        dialog.requestWindowFeature(
                Window.FEATURE_NO_TITLE);

        dialog.setContentView(
                R.layout.hypernova_add_wifi_dialog);

        EditText ssidInput =
                dialog.findViewById(
                        R.id.hypernova_add_wifi_ssid);

        EditText passwordInput =
                dialog.findViewById(
                        R.id.hypernova_add_wifi_password);

        View passwordContainer =
                dialog.findViewById(
                        R.id.hypernova_add_wifi_password_container);

        TextView openOption =
                dialog.findViewById(
                        R.id.hypernova_security_open);

        TextView wpa2Option =
                dialog.findViewById(
                        R.id.hypernova_security_wpa2);

        TextView wpa3Option =
                dialog.findViewById(
                        R.id.hypernova_security_wpa3);

        TextView cancel =
                dialog.findViewById(
                        R.id.hypernova_add_wifi_cancel);

        TextView connect =
                dialog.findViewById(
                        R.id.hypernova_add_wifi_connect);

        final int[] selectedSecurity = {
                SECURITY_OPEN
        };

        updateSecurityUi(
                openOption,
                wpa2Option,
                wpa3Option,
                passwordContainer,
                selectedSecurity[0]);

        openOption.setOnClickListener(
                clicked -> {

                    selectedSecurity[0] =
                            SECURITY_OPEN;

                    updateSecurityUi(
                            openOption,
                            wpa2Option,
                            wpa3Option,
                            passwordContainer,
                            selectedSecurity[0]);
                });

        wpa2Option.setOnClickListener(
                clicked -> {

                    selectedSecurity[0] =
                            SECURITY_WPA2;

                    updateSecurityUi(
                            openOption,
                            wpa2Option,
                            wpa3Option,
                            passwordContainer,
                            selectedSecurity[0]);
                });

        wpa3Option.setOnClickListener(
                clicked -> {

                    selectedSecurity[0] =
                            SECURITY_WPA3;

                    updateSecurityUi(
                            openOption,
                            wpa2Option,
                            wpa3Option,
                            passwordContainer,
                            selectedSecurity[0]);
                });

        cancel.setOnClickListener(
                clicked -> {

                    clearPendingManualConnection();

                    dialog.dismiss();
                });

        connect.setOnClickListener(
                clicked -> {

                    String ssid =
                            ssidInput
                                    .getText()
                                    .toString()
                                    .trim();

                    String password =
                            passwordInput
                                    .getText()
                                    .toString();

                    if (TextUtils.isEmpty(ssid)) {

                        ssidInput.setError(
                                getString(
                                        R.string
                                                .hypernova_wifi_name_required));

                        ssidInput.requestFocus();

                        return;
                    }

                    if (selectedSecurity[0]
                            != SECURITY_OPEN
                            && password.length() < 8) {

                        passwordInput.setError(
                                getString(
                                        R.string
                                                .hypernova_wifi_password_required));

                        passwordInput.requestFocus();

                        return;
                    }

                    WifiConfiguration configuration =
                            buildManualConfiguration(
                                    ssid,
                                    password,
                                    selectedSecurity[0]);

                    if (configuration == null) {
                        return;
                    }

                    mPendingManualSsid = ssid;

                    int wifiState =
                            mCarWifiManager
                                    .getWifiState();

                    if (wifiState
                            == WifiManager.WIFI_STATE_ENABLED) {

                        connectManualNetwork(
                                configuration,
                                dialog,
                                connect);

                    } else {

                        boolean accepted;

                        try {

                            accepted =
                                    mCarWifiManager
                                            .setWifiEnabled(true);

                        } catch (RuntimeException exception) {

                            accepted = false;
                        }

                        if (!accepted) {

                            Toast.makeText(
                                    requireContext(),
                                    R.string
                                            .hypernova_state_unavailable,
                                    Toast.LENGTH_SHORT)
                                    .show();

                            return;
                        }

                        mPendingManualConfiguration =
                                configuration;

                        mPendingManualDialog =
                                dialog;

                        mPendingManualConnectButton =
                                connect;

                        connect.setEnabled(false);

                        connect.setText(
                                R.string
                                        .hypernova_wifi_turning_on_connect);

                        mWifiSummary.setText(
                                R.string
                                        .hypernova_state_turning_on);
                    }
                });

        dialog.setCanceledOnTouchOutside(
                true);

        dialog.setOnDismissListener(
                ignored -> {

                    if (mPendingManualDialog
                            == dialog) {

                        clearPendingManualConnection();
                    }
                });

        dialog.show();

        configureHyperNovaDialogWindow(
                dialog);
    }

    private void updateSecurityUi(
            TextView open,
            TextView wpa2,
            TextView wpa3,
            View passwordContainer,
            int selectedSecurity) {

        styleSecurityOption(
                open,
                selectedSecurity
                        == SECURITY_OPEN);

        styleSecurityOption(
                wpa2,
                selectedSecurity
                        == SECURITY_WPA2);

        styleSecurityOption(
                wpa3,
                selectedSecurity
                        == SECURITY_WPA3);

        passwordContainer.setVisibility(
                selectedSecurity
                        == SECURITY_OPEN
                        ? View.GONE
                        : View.VISIBLE);
    }

    private void styleSecurityOption(
            TextView option,
            boolean selected) {

        option.setBackgroundResource(
                selected
                        ? R.drawable
                        .hypernova_dialog_option_selected_background
                        : R.drawable
                        .hypernova_dialog_option_background);

        option.setTextColor(
                requireContext().getColor(
                        selected
                                ? R.color.hypernova_cyan
                                : R.color.hypernova_text_primary));
    }

    @Nullable
    private WifiConfiguration buildManualConfiguration(
            String ssid,
            String password,
            int security) {

        WifiConfiguration config =
                new WifiConfiguration();

        config.SSID =
                quoteWifiValue(ssid);

        /*
         * Manual Add Network is also the path used for networks
         * that are not currently visible in the scan results.
         */
        config.hiddenSSID = true;

        switch (security) {

            case SECURITY_OPEN:

                config.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.NONE);

                break;


            case SECURITY_WPA2:

                config.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.WPA_PSK);

                config.preSharedKey =
                        quoteWifiValue(password);

                break;


            case SECURITY_WPA3:

                config.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.SAE);

                config.preSharedKey =
                        quoteWifiValue(password);

                config.requirePmf = true;

                break;


            default:

                return null;
        }

        return config;
    }

    private String quoteWifiValue(
            String value) {

        return "\""
                + value.replace(
                        "\"",
                        "\\\"")
                + "\"";
    }

    private String unquoteWifiValue(
            String value) {

        if (value == null
                || value.length() < 2) {
            return value;
        }

        if (value.startsWith("\"")
                && value.endsWith("\"")) {
            return value.substring(
                    1,
                    value.length() - 1);
        }

        return value;
    }

    private void connectPendingManualNetwork() {

        WifiConfiguration configuration =
                mPendingManualConfiguration;

        Dialog dialog =
                mPendingManualDialog;

        TextView connectButton =
                mPendingManualConnectButton;

        if (configuration == null) {
            return;
        }

        mPendingManualConfiguration =
                null;

        mPendingManualDialog =
                null;

        mPendingManualConnectButton =
                null;

        connectManualNetwork(
                configuration,
                dialog,
                connectButton);
    }

    private void connectManualNetwork(
            WifiConfiguration configuration,
            @Nullable Dialog dialog,
            @Nullable TextView connectButton) {

        if (connectButton != null) {

            connectButton.setEnabled(false);

            connectButton.setText(
                    R.string.hypernova_connecting);
        }

        mPendingManualSsid =
                unquoteWifiValue(configuration.SSID);

        mConnectionFailureKey = null;

        if (!HyperNovaWifiTransportRequest.acquire(
                requireContext())) {

            mPendingManualSsid = null;

            Toast.makeText(
                    requireContext(),
                    R.string.hypernova_wifi_connection_failed,
                    Toast.LENGTH_SHORT).show();

            if (connectButton != null) {

                connectButton.setEnabled(true);

                connectButton.setText(
                        R.string.hypernova_connect);
            }

            refreshWifiUi();

            return;
        }

        beginConnectionTimeout();

        refreshWifiUi();

        try {

            mConnectivityClient.connect(
                    configuration,
                    new WifiManager.ActionListener() {

                        @Override
                        public void onSuccess() {

                            if (!isAdded()) {
                                return;
                            }

                            Toast.makeText(
                                    requireContext(),
                                    R.string
                                            .hypernova_wifi_connection_started,
                                    Toast.LENGTH_SHORT)
                                    .show();

                            if (dialog != null
                                    && dialog.isShowing()) {

                                dialog.dismiss();
                            }

                            refreshWifiUi();
                        }

                        @Override
                        public void onFailure(
                                int reason) {

                            if (!isAdded()) {
                                return;
                            }

                            mMainHandler.removeCallbacks(
                                    mConnectionTimeoutRunnable);

                            mPendingManualSsid = null;

                            releaseWifiTransportIfDisconnected();

                            Toast.makeText(
                                    requireContext(),
                                    R.string
                                            .hypernova_wifi_connection_failed,
                                    Toast.LENGTH_SHORT)
                                    .show();

                            if (connectButton != null) {

                                connectButton.setEnabled(true);

                                connectButton.setText(
                                        R.string.hypernova_connect);
                            }

                            refreshWifiUi();
                        }
                    });

        } catch (RuntimeException exception) {

            mMainHandler.removeCallbacks(
                    mConnectionTimeoutRunnable);

            mPendingManualSsid = null;

            releaseWifiTransportIfDisconnected();

            Toast.makeText(
                    requireContext(),
                    R.string
                            .hypernova_wifi_connection_failed,
                    Toast.LENGTH_SHORT)
                    .show();

            if (connectButton != null) {

                connectButton.setEnabled(true);

                connectButton.setText(
                        R.string.hypernova_connect);
            }

            refreshWifiUi();
        }
    }

    private void clearPendingManualConnection() {

        mPendingManualConfiguration =
                null;

        mPendingManualDialog =
                null;

        mPendingManualConnectButton =
                null;
    }

    private void configureHyperNovaDialogWindow(
            Dialog dialog) {

        Window window =
                dialog.getWindow();

        if (window == null) {
            return;
        }

        window.setWindowAnimations(
                R.style.HyperNovaDialogAnimation);

        window.setBackgroundDrawable(
                new ColorDrawable(
                        Color.TRANSPARENT));

        window.addFlags(
                WindowManager
                        .LayoutParams
                        .FLAG_DIM_BEHIND);

        window.setSoftInputMode(
                WindowManager
                        .LayoutParams
                        .SOFT_INPUT_ADJUST_RESIZE);

        WindowManager.LayoutParams params =
                window.getAttributes();

        params.dimAmount =
                0.68f;

        window.setAttributes(
                params);

        int parentWidth =
                requireActivity()
                        .getWindow()
                        .getDecorView()
                        .getWidth();

        if (parentWidth <= 0) {

            parentWidth =
                    getResources()
                            .getDisplayMetrics()
                            .widthPixels;
        }

        int dialogWidth =
                Math.max(
                        dpToPx(320),
                        parentWidth
                                - dpToPx(48));

        window.setLayout(
                dialogWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void hideNetworks() {

        mConnectedContainer.removeAllViews();

        mAvailableContainer.removeAllViews();

        if (mAvailableEmptyState != null) {

            mAvailableEmptyState.setVisibility(
                    View.GONE);
        }

        mConsecutiveEmptyAvailableRefreshes = 0;
        mLastAvailableNetworksSignature = "";

        mMainHandler.removeCallbacks(
                mAvailableNetworkCacheExpiryRunnable);

        mAvailableNetworkCache.clear();

        mConnectedSection.setVisibility(
                View.GONE);

        mAvailableSection.setVisibility(
                View.GONE);
    }

    private void addDivider() {

        View divider =
                new View(
                        requireContext());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(1));

        params.setMarginStart(
                dpToPx(54));

        divider.setLayoutParams(
                params);

        divider.setBackgroundColor(
                requireContext().getColor(
                        R.color.hypernova_divider));

        mAvailableContainer.addView(
                divider);
    }

    private int dpToPx(
            int dp) {

        return Math.round(
                dp
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }
    private static final class CachedAvailableWifiEntry {

        final WifiEntry entry;
        final long lastSeenElapsedRealtimeMs;

        CachedAvailableWifiEntry(
                WifiEntry entry,
                long lastSeenElapsedRealtimeMs) {

            this.entry =
                    entry;

            this.lastSeenElapsedRealtimeMs =
                    lastSeenElapsedRealtimeMs;
        }
    }

}
