package com.hypernova.visuals;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Applies the cockpit-wide day/night choice to whichever app this receiver was merged into.
 *
 * <p>Declared once in the nova-visuals manifest, so every app that already draws the shared
 * navigation bar follows the launcher without its own manifest entry.
 */
public final class CockpitAppearanceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !CockpitAppearance.ACTION_SET_NIGHT_MODE.equals(intent.getAction())) {
            return;
        }
        int mode = intent.getIntExtra(
                CockpitAppearance.EXTRA_NIGHT_MODE,
                CockpitAppearance.MODE_UNSET);
        CockpitAppearance.apply(context, mode);
    }
}
