package com.hypernova.visuals;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;

/**
 * One day/night appearance for the whole cockpit.
 *
 * <p>Every HyperNova app already ships a complete {@code values} and {@code values-night} palette,
 * so each one can render either appearance on its own. What was missing was agreement: the launcher
 * changed only itself, and the other apps kept whatever the platform happened to be in.
 *
 * <p>The platform-wide switch ({@code UiModeManager.setNightMode}) needs MODIFY_DAY_NIGHT_MODE,
 * which is signature|privileged. These are normal APKs signed with the team key, not privileged
 * system apps, so that call is rejected on the NXP guest. Instead the launcher tells each cockpit
 * app directly and every app applies the mode to itself through {@code setApplicationNightMode},
 * which needs no permission at all. The visible result is the same, and it survives the eventual
 * move to a privileged launcher build: there the system path succeeds first and these per-app
 * overrides simply agree with it.
 *
 * <p>This class lives in nova-visuals because all six cockpit apps already depend on that module
 * for the shared navigation bar, so the receiver and the boot-time restore below merge into every
 * app without touching six manifests.
 */
public final class CockpitAppearance {

    private static final String TAG = "CockpitAppearance";

    /** Explicit broadcast the launcher sends to every cockpit app. */
    public static final String ACTION_SET_NIGHT_MODE =
            "com.hypernova.action.SET_NIGHT_MODE";

    /** Int extra: {@link #MODE_LIGHT} or {@link #MODE_DARK}. */
    public static final String EXTRA_NIGHT_MODE =
            "com.hypernova.extra.NIGHT_MODE";

    /**
     * Signature permission the sender must hold, enforced by each app's receiver. It is declared
     * by the launcher, which owns the appearance control, so nothing outside the cockpit can
     * repaint the vehicle's screens.
     */
    public static final String PERMISSION_SYNC_APPEARANCE =
            "com.hypernova.permission.SYNC_APPEARANCE";

    public static final int MODE_LIGHT = UiModeManager.MODE_NIGHT_NO;
    public static final int MODE_DARK = UiModeManager.MODE_NIGHT_YES;

    /** Nothing has been chosen yet; follow the platform. */
    public static final int MODE_UNSET = UiModeManager.MODE_NIGHT_AUTO;

    /**
     * Every cockpit surface that can follow the launcher.
     *
     * <p>com.hypernova.settings is deliberately absent: no source survives for it, so it cannot be
     * rebuilt with the receiver and would only be a broadcast into nothing.
     */
    public static final String[] COCKPIT_PACKAGES = {
            "com.hypernova.launcher",
            "com.hypernova.launcher.dev",
            "com.hypernova.ai",
            "com.hypernova.navigation",
            "com.hypernova.media",
            "com.hypernova.climate",
            "com.hypernova.phone",
    };

    private static final String PREFERENCES = "hypernova_appearance";
    private static final String KEY_NIGHT_MODE = "night_mode";

    private CockpitAppearance() {
    }

    /** True when the resources currently loaded in this process are the night set. */
    public static boolean isNight(Context context) {
        int flags = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return flags == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The mode this app should be in, or {@link #MODE_UNSET} when the driver has never chosen. */
    public static int stored(Context context) {
        return preferences(context).getInt(KEY_NIGHT_MODE, MODE_UNSET);
    }

    /**
     * Apply a mode to this application and remember it.
     *
     * <p>{@code setApplicationNightMode} is not documented to survive a process restart, so the
     * choice is also written to preferences and replayed by {@link CockpitAppearanceInitializer}
     * the next time the app starts.
     */
    public static void apply(Context context, int mode) {
        if (mode != MODE_LIGHT && mode != MODE_DARK) {
            return;
        }
        preferences(context).edit().putInt(KEY_NIGHT_MODE, mode).apply();

        boolean wantsNight = mode == MODE_DARK;
        if (isNight(context) == wantsNight) {
            // Already correct. Re-applying would recreate every visible activity for nothing.
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "Per-application night mode needs API 31; leaving the platform appearance");
            return;
        }
        UiModeManager manager = context.getSystemService(UiModeManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.setApplicationNightMode(mode);
        } catch (RuntimeException error) {
            // Appearance is a preference, never a reason to take a cockpit app down.
            Log.w(TAG, "Could not apply the cockpit night mode", error);
        }
    }

    /** Replay the remembered mode. Called once per process before the first activity is built. */
    public static void restore(Context context) {
        apply(context, stored(context));
    }

    /**
     * Tell every other cockpit app to use {@code mode}.
     *
     * <p>The broadcasts are explicit, one per package. Implicit broadcasts have not reached
     * manifest-declared receivers since Android 8, and an explicit one also starts an app that is
     * not currently running, so an app opened later is already in the right appearance.
     */
    public static void broadcast(Context context, int mode) {
        if (mode != MODE_LIGHT && mode != MODE_DARK) {
            return;
        }
        String self = context.getPackageName();
        for (String target : COCKPIT_PACKAGES) {
            if (target.equals(self)) {
                continue;
            }
            Intent intent = new Intent(ACTION_SET_NIGHT_MODE)
                    .setPackage(target)
                    .putExtra(EXTRA_NIGHT_MODE, mode);
            try {
                // Each receiver enforces PERMISSION_SYNC_APPEARANCE on the sender; no matching
                // permission is demanded of the receivers here, so a cockpit app still follows the
                // launcher even if its signature grant has not been re-evaluated after an install.
                context.sendBroadcast(intent);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not reach " + target + " with the appearance change", error);
            }
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
