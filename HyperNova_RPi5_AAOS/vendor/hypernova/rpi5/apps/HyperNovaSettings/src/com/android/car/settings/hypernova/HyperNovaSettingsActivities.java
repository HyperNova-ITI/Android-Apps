/*
 * Copyright (C) 2026 HyperNova
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.settings.hypernova;

import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import java.util.TimeZone;

import com.hypernova.settings.R;

import com.android.car.settings.common.BaseCarSettingsActivity;
import com.android.car.settings.wifi.AddWifiFragment;

/** Activity entry points for the six product-approved HyperNova Settings surfaces. */
public final class HyperNovaSettingsActivities {
    private static final String TAG = "HyperNovaSettings";

    private static final String PORTRAIT_ROOT_TAG =
            "CarUiPortraitBaseLayoutToolbar";

    private static final String PORTRAIT_TOP_INSET_TAG =
            "car_ui_portrait_top_inset";

    private static final String PORTRAIT_LEFT_INSET_TAG =
            "car_ui_portrait_left_inset";

    private static final String EXTRA_AUDIO_GROUP_ID =
            "com.hypernova.settings.extra.AUDIO_GROUP_ID";

    private static final String DEFAULT_TIME_ZONE =
            "Africa/Cairo";

    private static final String TIME_ZONE_INIT_MARKER =
            "hypernova_initial_timezone_configured";

    private HyperNovaSettingsActivities() {
    }

    /**
     * Binds the app-local HyperNova header without replacing the
     * CarSettings activity, UXR, or fragment infrastructure underneath it.
     */
    static void configureHeader(
            Fragment fragment,
            @StringRes int titleId,
            boolean showBack) {

        configureHeader(
                fragment,
                fragment.getString(titleId),
                showBack);
    }

    static void configureHeader(
            Fragment fragment,
            CharSequence titleText,
            boolean showBack) {

        Activity activity = fragment.requireActivity();

        TextView title =
                activity.findViewById(R.id.hypernova_header_title);

        View back =
                activity.findViewById(R.id.hypernova_header_back);

        if (title == null || back == null) {
            return;
        }

        title.setText(titleText);

        /*
         * HyperNova hides the stock navigation bar, so the in-app control is
         * always visible. The boolean now controls hierarchy instead of
         * visibility: false means Settings Home -> Launcher; true means a
         * child Settings surface -> its Settings parent.
         */
        back.setVisibility(View.VISIBLE);
        back.setFocusable(true);
        back.setClickable(true);
        back.setOnClickListener(view ->
                navigateBack(activity, showBack));

        if (activity instanceof HyperNovaBaseActivity) {
            ((HyperNovaBaseActivity) activity)
                    .schedulePreferenceNormalization();
        }
    }

    private static void navigateBack(
            Activity activity,
            boolean childSurface) {

        if (!childSurface || activity instanceof HomeActivity) {
            returnToLauncher(activity);
            return;
        }

        if (activity instanceof AudioGroupActivity) {
            openParent(activity, SoundActivity.class);
            return;
        }

        if (activity instanceof AddWifiActivity) {
            openParent(activity, WifiActivity.class);
            return;
        }

        openParent(activity, HomeActivity.class);
    }

    private static void openParent(
            Activity activity,
            Class<? extends Activity> parentClass) {

        Intent parentIntent =
                new Intent(activity, parentClass);

        parentIntent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        activity.startActivity(parentIntent);
        activity.finish();
        activity.overridePendingTransition(
                R.anim.hypernova_activity_enter_reverse,
                R.anim.hypernova_activity_exit_reverse);
    }

    private static void returnToLauncher(Activity activity) {
        Intent homeIntent =
                new Intent(Intent.ACTION_MAIN);

        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        activity.startActivity(homeIntent);
        activity.finish();
        activity.overridePendingTransition(
                R.anim.hypernova_activity_enter_reverse,
                R.anim.hypernova_activity_exit_reverse);
    }

    /**
     * RPi5 has working network time but the audited build reports that automatic
     * time-zone detection is not supported (no telephony or geo provider). Seed
     * Cairo once per Android user, keep network time enabled, and leave automatic
     * time-zone detection off so the UI never claims a detector is working when it
     * is not. The user can still select another zone manually later.
     */
    private static void ensureInitialEgyptTimeConfiguration(Context context) {
        int userId = UserHandle.myUserId();

        int configured = Settings.Secure.getIntForUser(
                context.getContentResolver(),
                TIME_ZONE_INIT_MARKER,
                0,
                userId);

        if (configured != 0) {
            return;
        }

        Settings.Global.putInt(
                context.getContentResolver(),
                Settings.Global.AUTO_TIME,
                1);

        Settings.Global.putInt(
                context.getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE,
                0);

        String currentZone = TimeZone.getDefault().getID();

        if (TextUtils.isEmpty(currentZone)
                || "GMT".equals(currentZone)
                || "UTC".equals(currentZone)
                || "Etc/UTC".equals(currentZone)) {

            AlarmManager alarmManager =
                    context.getSystemService(AlarmManager.class);

            if (alarmManager != null) {
                alarmManager.setTimeZone(DEFAULT_TIME_ZONE);
            }
        }

        Settings.Secure.putIntForUser(
                context.getContentResolver(),
                TIME_ZONE_INIT_MARKER,
                1,
                userId);
    }

    static void startAudioGroupActivity(
            Context context,
            int groupId) {

        Intent intent =
                new Intent(context, AudioGroupActivity.class);

        intent.putExtra(
                EXTRA_AUDIO_GROUP_ID,
                groupId);

        context.startActivity(intent);
    }

    /**
     * Keeps BaseCarSettingsActivity as the functional host, but replaces
     * the injected portrait app toolbar/rail with the compact HyperNova
     * header for the six visible product surfaces.
     *
     * Automotive system bars are outside this hierarchy and remain
     * untouched.
     */
    private abstract static class HyperNovaBaseActivity
            extends BaseCarSettingsActivity {

        @Nullable
        private ViewTreeObserver.OnGlobalLayoutListener mPaddingGuard;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            ensureInitialEgyptTimeConfiguration(this);

            Intent dimService =
                    new Intent(this, HyperNovaSoftwareBrightnessService.class);
            startService(dimService);

            overridePendingTransition(
                    R.anim.hypernova_activity_enter,
                    R.anim.hypernova_activity_exit);

            applyHyperNovaFullscreen();
            installHyperNovaChrome();
        }

        @Override
        public void onBackPressed() {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                super.onBackPressed();
                return;
            }

            navigateBack(this, !(this instanceof HomeActivity));
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);

            if (hasFocus) {
                applyHyperNovaFullscreen();
            }
        }

        /**
         * Requests the complete display area for the HyperNova cockpit.
         *
         * The product-level CarSystemUI overlay will separately disable
         * the persistent AAOS top and bottom bars.
         */
        private void applyHyperNovaFullscreen() {
            getWindow().setDecorFitsSystemWindows(false);

            WindowInsetsController controller =
                    getWindow().getInsetsController();

            if (controller != null) {
                controller.hide(
                        WindowInsets.Type.statusBars()
                                | WindowInsets.Type.navigationBars());

                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                return;
            }

            // Compatibility fallback for a platform window without an InsetsController.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        @Override
        public void onDestroy() {
            View decor = getWindow().getDecorView();

            if (mPaddingGuard != null
                    && decor.getViewTreeObserver().isAlive()) {

                decor.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(mPaddingGuard);
            }

            mPaddingGuard = null;

            super.onDestroy();
        }

        private void installHyperNovaChrome() {
            ViewGroup wrapper =
                    findViewById(R.id.fragment_container_wrapper);

            if (wrapper == null) {
                return;
            }

            View header =
                    LayoutInflater.from(this).inflate(
                            R.layout.hypernova_screen_header,
                            wrapper,
                            false);

            wrapper.addView(
                    header,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            getResources().getDimensionPixelSize(
                                    R.dimen.hypernova_header_height)));

            addHeaderInset(
                    findViewById(R.id.fragment_container));

            addHeaderInset(
                    findViewById(R.id.restricted_message));

            hidePortraitAppChrome();

            View decor =
                    getWindow().getDecorView();

            mPaddingGuard = () -> {
                if (wrapper.getPaddingLeft() != 0
                        || wrapper.getPaddingTop() != 0
                        || wrapper.getPaddingRight() != 0
                        || wrapper.getPaddingBottom() != 0) {

                    wrapper.setPadding(0, 0, 0, 0);
                }

                expandPreferenceContent();
            };

            decor.getViewTreeObserver()
                    .addOnGlobalLayoutListener(mPaddingGuard);

            wrapper.post(() -> {
                wrapper.setPadding(0, 0, 0, 0);
                expandPreferenceContent();
            });
        }

        private void schedulePreferenceNormalization() {
            View decor =
                    getWindow().getDecorView();

            decor.post(() -> {
                expandPreferenceContent();

                decor.postOnAnimation(
                        this::expandPreferenceContent);
            });
        }

        private void addHeaderInset(@Nullable View view) {
            if (view == null) {
                return;
            }

            ViewGroup.LayoutParams rawParams =
                    view.getLayoutParams();

            if (!(rawParams
                    instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }

            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) rawParams;

            params.topMargin =
                    getResources().getDimensionPixelSize(
                            R.dimen.hypernova_header_height);

            view.setLayoutParams(params);
        }

        private void hidePortraitAppChrome() {
            /*
             * The portrait plugin's toolbar is a sibling overlay above
             * the app content. Some plugin revisions do not carry the
             * portrait root tags, so resolve the stable CarUi toolbar
             * background as the primary path and hide its enclosing
             * FocusArea.
             */
            View toolbarBackground =
                    findViewById(R.id.car_ui_toolbar_background);

            if (toolbarBackground != null
                    && toolbarBackground.getParent()
                    instanceof View) {

                ((View) toolbarBackground.getParent())
                        .setVisibility(View.GONE);
            }

            View root =
                    getWindow()
                            .getDecorView()
                            .findViewWithTag(PORTRAIT_ROOT_TAG);

            if (!(root instanceof ViewGroup)) {
                return;
            }

            View topInset =
                    root.findViewWithTag(
                            PORTRAIT_TOP_INSET_TAG);

            if (topInset != null
                    && topInset.getParent() instanceof View) {

                ((View) topInset.getParent())
                        .setVisibility(View.GONE);
            }

            View leftInset =
                    root.findViewWithTag(
                            PORTRAIT_LEFT_INSET_TAG);

            if (leftInset == null
                    || !(leftInset.getParent()
                    instanceof ViewGroup)) {
                return;
            }

            ViewGroup parent =
                    (ViewGroup) leftInset.getParent();

            int dividerIndex =
                    parent.indexOfChild(leftInset);

            if (dividerIndex > 0) {
                parent.getChildAt(dividerIndex - 1)
                        .setVisibility(View.GONE);
            }

            leftInset.setVisibility(View.GONE);
        }

        /**
         * The portrait CarUi plugin constrains the internal RecyclerView
         * to a narrow width even when the app-owned preference host spans
         * the full content area.
         *
         * Keep the CarUi wrapper, adapter, rotary container, and UXR
         * behavior, but let its internal list and attached rows consume
         * the width bounded by HyperNova's page margins.
         */
        private void expandPreferenceContent() {
            View recycler =
                    findInternalRecycler(
                            getWindow().getDecorView());

            if (!(recycler instanceof ViewGroup)
                    || !(recycler.getParent()
                    instanceof ViewGroup)) {
                return;
            }

            ViewGroup recyclerContainer =
                    (ViewGroup) recycler.getParent();

            if (recyclerContainer.getPaddingLeft() != 0
                    || recyclerContainer.getPaddingRight() != 0) {

                Log.i(
                        TAG,
                        "Expanding preference list from container padding "
                                + recyclerContainer.getPaddingLeft()
                                + "/"
                                + recyclerContainer.getPaddingRight()
                                + " at width "
                                + recyclerContainer.getWidth());

                recyclerContainer.setPadding(
                        0,
                        recyclerContainer.getPaddingTop(),
                        0,
                        recyclerContainer.getPaddingBottom());
            }

            setMatchParentWidth(recycler);

            int availableWidth =
                    recyclerContainer.getWidth();

            if (availableWidth > 0
                    && recycler.getMinimumWidth()
                    != availableWidth) {

                recycler.setMinimumWidth(availableWidth);
            }

            ViewGroup recyclerGroup =
                    (ViewGroup) recycler;

            for (int index = 0;
                    index < recyclerGroup.getChildCount();
                    index++) {

                View child =
                        recyclerGroup.getChildAt(index);

                setMatchParentWidth(child);

                if (availableWidth > 0
                        && child.getMinimumWidth()
                        != availableWidth) {

                    child.setMinimumWidth(availableWidth);
                }
            }
        }

        @Nullable
        private View findInternalRecycler(View view) {
            if ("androidx.recyclerview.widget.RecyclerView"
                    .equals(view.getClass().getName())) {
                return view;
            }

            if (!(view instanceof ViewGroup)) {
                return null;
            }

            ViewGroup group =
                    (ViewGroup) view;

            for (int index = 0;
                    index < group.getChildCount();
                    index++) {

                View result =
                        findInternalRecycler(
                                group.getChildAt(index));

                if (result != null) {
                    return result;
                }
            }

            return null;
        }

        private void setMatchParentWidth(View view) {
            ViewGroup.LayoutParams params =
                    view.getLayoutParams();

            if (params == null) {
                return;
            }

            boolean changed =
                    params.width
                            != ViewGroup.LayoutParams.MATCH_PARENT;

            params.width =
                    ViewGroup.LayoutParams.MATCH_PARENT;

            if (params
                    instanceof ViewGroup.MarginLayoutParams) {

                ViewGroup.MarginLayoutParams marginParams =
                        (ViewGroup.MarginLayoutParams) params;

                if (marginParams.leftMargin != 0
                        || marginParams.rightMargin != 0) {

                    marginParams.leftMargin = 0;
                    marginParams.rightMargin = 0;
                    changed = true;
                }
            }

            if (changed) {
                view.setLayoutParams(params);
            }
        }
    }

    public static final class HomeActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaHomepageFragment();
        }

        /*
         * This method exists in some CarSettings branches but not in
         * others. It intentionally has no @Override annotation so the
         * same HyperNova source remains source-compatible with both.
         */
        protected boolean shouldFocusContentOnLaunch() {
            return true;
        }
    }

    public static final class DisplayActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaDisplaySettingsFragment();
        }
    }

    public static final class SoundActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaPortableSoundFragment();
        }
    }

    public static final class WifiActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaWifiSettingsFragment();
        }
    }

    public static final class AddWifiActivity
            extends BaseCarSettingsActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new AddWifiFragment();
        }
    }

    public static final class BluetoothActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaBluetoothSettingsFragment();
        }
    }

    public static final class DateTimeActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return new HyperNovaDateTimeSettingsFragment();
        }
    }

    /**
     * Non-exported detail flow for a real audio group selected
     * on the Sound page.
     */
    public static final class AudioGroupActivity
            extends HyperNovaBaseActivity {

        @Nullable
        @Override
        protected Fragment getInitialFragment() {
            return HyperNovaAudioGroupDetailFragment.newInstance(
                    getIntent().getIntExtra(
                            EXTRA_AUDIO_GROUP_ID,
                            HyperNovaAudioUtils.INVALID_GROUP_ID));
        }
    }
}
