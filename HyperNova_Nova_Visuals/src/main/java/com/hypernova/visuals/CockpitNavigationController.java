package com.hypernova.visuals;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * Shared, app-side controller for the fixed HyperNova cockpit navigation bar.
 * The action/package registry lives here so every cockpit app launches the
 * same explicit public contract with a bounded task stack.
 */
public final class CockpitNavigationController {
    private static final String TAG = "CockpitNavigation";

    public enum Destination {
        HOME("com.hypernova.launcher", null),
        NAVIGATION("com.hypernova.navigation", "com.hypernova.navigation.action.OPEN"),
        MEDIA("com.hypernova.media", "com.hypernova.media.action.OPEN"),
        CLIMATE("com.hypernova.climate", "com.hypernova.climate.action.OPEN"),
        NOVA("com.hypernova.ai", "com.hypernova.ai.action.OPEN"),
        PHONE("com.hypernova.phone", "com.hypernova.phone.action.OPEN"),
        SETTINGS("com.hypernova.settings", "com.hypernova.settings.action.OPEN");

        final String packageName;
        final String openAction;

        Destination(String packageName, String openAction) {
            this.packageName = packageName;
            this.openAction = openAction;
        }
    }

    private CockpitNavigationController() {}

    public static void bind(View bar, Destination activeDestination) {
        if (bar == null) {
            Log.w(TAG, "Cockpit navigation view is unavailable");
            return;
        }
        bindItem(bar, R.id.hnCockpitNavHome, R.id.hnCockpitNavHomeIcon,
                Destination.HOME, activeDestination, 32, false);
        bindItem(bar, R.id.hnCockpitNavNavigation, R.id.hnCockpitNavNavigationIcon,
                Destination.NAVIGATION, activeDestination, 39, false);
        bindItem(bar, R.id.hnCockpitNavMedia, R.id.hnCockpitNavMediaIcon,
                Destination.MEDIA, activeDestination, 39, false);
        bindItem(bar, R.id.hnCockpitNavClimate, R.id.hnCockpitNavClimateIcon,
                Destination.CLIMATE, activeDestination, 33, false);
        bindItem(bar, R.id.hnCockpitNavNova, R.id.hnCockpitNavNovaIcon,
                Destination.NOVA, activeDestination, 28, true);
        bindItem(bar, R.id.hnCockpitNavPhone, R.id.hnCockpitNavPhoneIcon,
                Destination.PHONE, activeDestination, 38, false);
        bindItem(bar, R.id.hnCockpitNavSettings, R.id.hnCockpitNavSettingsIcon,
                Destination.SETTINGS, activeDestination, 40, false);
    }

    private static void bindItem(
            View bar,
            int itemId,
            int iconId,
            Destination destination,
            Destination activeDestination,
            int inactiveSizeDp,
            boolean preserveArtworkColor) {
        View item = bar.findViewById(itemId);
        ImageView icon = bar.findViewById(iconId);
        if (item == null || icon == null) return;

        Context context = bar.getContext();
        boolean active = destination == activeDestination;
        int size = dp(context, active ? 55 : inactiveSizeDp);
        ViewGroup.LayoutParams layoutParams = icon.getLayoutParams();
        layoutParams.width = size;
        layoutParams.height = size;
        icon.setLayoutParams(layoutParams);
        icon.setBackgroundResource(active ? R.drawable.hn_bg_cockpit_nav_selected : 0);
        int padding = active ? dp(context, 12) : 0;
        icon.setPadding(padding, padding, padding, padding);
        icon.setAlpha(active ? 1f : 0.88f);
        if (!preserveArtworkColor) {
            icon.setImageTintList(ColorStateList.valueOf(context.getColor(
                    active ? R.color.hn_cockpit_text_dark : R.color.hn_cockpit_text_secondary)));
        } else {
            icon.setImageTintList(null);
        }
        item.setSelected(active);
        item.setOnClickListener(view -> {
            if (!active) open(context, destination);
        });
    }

    private static void open(Context context, Destination destination) {
        Intent intent;
        if (destination == Destination.HOME) {
            intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(destination.packageName);
        } else {
            intent = new Intent(destination.openAction)
                    .setPackage(destination.packageName)
                    .addCategory(Intent.CATEGORY_DEFAULT);
        }
        /*
         * A cockpit bar lives in an Activity. Keep cross-app transitions in that cockpit task;
         * NEW_TASK/CLEAR_TOP used to resurrect a stale Navigation task whose detached WebView
         * rendered an empty map. Only non-Activity callers need NEW_TASK.
         */
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to open " + destination.packageName, exception);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
