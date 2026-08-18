# HyperNova IVI Design System

This document is the final visual contract for every HyperNova Android Automotive application.

The goal is that Launcher, Navigation, Media, Phone, Climate, Weather, Driver Profile, Settings, and NOVA AI look like one IVI system in both Light and Dark mode.

---

## 1. Single source of truth

Every application must use the same semantic resource names.

```text
res/values/colors.xml         -> Light colors
res/values-night/colors.xml   -> Dark colors
res/values/themes.xml         -> Light window/system-bar behavior
res/values-night/themes.xml   -> Dark window/system-bar behavior
```

Android selects `values/` or `values-night/` automatically from the current `uiMode`.

Do not create separate Light and Dark layouts. Use one layout and semantic colors.

```text
Wrong:
layout/activity_main.xml
layout-night/activity_main.xml

Correct:
layout/activity_main.xml
values/colors.xml
values-night/colors.xml
```

---

## 2. Final Light palette

| Role | Resource | Value |
|---|---|---|
| Main background | `hypernova_background` | `#EEF4F8` |
| Secondary background | `hypernova_background_secondary` | `#E4EDF3` |
| Card | `hypernova_card` | `#FFFFFF` |
| Elevated card | `hypernova_card_elevated` | `#F7FBFD` |
| Selected surface | `hypernova_selected` | `#D9F1F4` |
| Primary text | `hypernova_text_primary` | `#071722` |
| Secondary text | `hypernova_text_secondary` | `#4E616E` |
| Disabled text | `hypernova_text_disabled` | `#82919B` |
| Primary cyan | `hypernova_cyan` | `#087F91` |
| Bright cyan | `hypernova_cyan_bright` | `#0AA7B8` |
| Pressed cyan | `hypernova_cyan_pressed` | `#066D7D` |
| Border | `hypernova_border` | `#9BAFBD` |
| Soft border | `hypernova_border_soft` | `#C3D1DB` |
| Divider | `hypernova_divider` | `#D4DFE6` |
| Success | `hypernova_success` | `#087B34` |
| Warning | `hypernova_warning` | `#9A5900` |
| Error | `hypernova_error` | `#C62B42` |

Full copy-ready file: `templates/res/values/colors.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>

<resources>

    <!-- Main backgrounds -->
    <color name="hypernova_background">#EEF4F8</color>
    <color name="hypernova_background_secondary">#E4EDF3</color>
    <color name="hypernova_background_glow">#D5EEF2</color>

    <!-- Cards and selected surfaces -->
    <color name="hypernova_card">#FFFFFF</color>
    <color name="hypernova_card_elevated">#F7FBFD</color>
    <color name="hypernova_selected">#D9F1F4</color>

    <!-- Borders and dividers -->
    <color name="hypernova_border">#9BAFBD</color>
    <color name="hypernova_border_soft">#C3D1DB</color>
    <color name="hypernova_divider">#D4DFE6</color>

    <!-- Primary HyperNova accent -->
    <color name="hypernova_cyan">#087F91</color>
    <color name="hypernova_cyan_bright">#0AA7B8</color>
    <color name="hypernova_cyan_pressed">#066D7D</color>
    <color name="hypernova_cyan_dark">#055563</color>

    <!-- AI accent -->
    <color name="hypernova_purple">#7140BA</color>

    <!-- Text and icons -->
    <color name="hypernova_text_primary">#071722</color>
    <color name="hypernova_text_secondary">#4E616E</color>
    <color name="hypernova_text_disabled">#82919B</color>
    <color name="hypernova_text_dark">#021118</color>
    <color name="hypernova_icon_primary">#071722</color>

    <!-- Filled controls -->
    <color name="hypernova_control_filled_background">#0A2635</color>
    <color name="hypernova_control_filled_border">#284A5A</color>
    <color name="hypernova_control_filled_icon">#FFFFFF</color>

    <!-- System status -->
    <color name="hypernova_success">#087B34</color>
    <color name="hypernova_warning">#9A5900</color>
    <color name="hypernova_error">#C62B42</color>

    <!-- Launcher/background gradients -->
    <color name="hypernova_launcher_glow_start">#8AD6E4EE</color>
    <color name="hypernova_launcher_glow_center">#50DCEAF1</color>
    <color name="hypernova_launcher_glow_end">#00EEF4F8</color>

    <!-- Hero gradient -->
    <color name="hypernova_hero_start">#FFFFFF</color>
    <color name="hypernova_hero_center">#F5FAFC</color>
    <color name="hypernova_hero_end">#EAF3F7</color>

    <!-- Dashboard cards -->
    <color name="hypernova_dashboard_start">#F2FFFFFF</color>
    <color name="hypernova_dashboard_end">#F2F5FAFC</color>

    <!-- Route information overlay -->
    <color name="hypernova_route_overlay_start">#FAFFFFFF</color>
    <color name="hypernova_route_overlay_end">#F2EAF3F7</color>

    <!-- Bottom navigation -->
    <color name="hypernova_bottom_nav_start">#FCFFFFFF</color>
    <color name="hypernova_bottom_nav_center">#FFF7FBFD</color>
    <color name="hypernova_bottom_nav_end">#FCFFFFFF</color>

    <!-- Selected bottom navigation item -->
    <color name="hypernova_nav_selected_outer_fill">#26087F91</color>
    <color name="hypernova_nav_selected_outer_stroke">#78087F91</color>
    <color name="hypernova_nav_selected_inner_start">#31DCE7</color>
    <color name="hypernova_nav_selected_inner_end">#11AABD</color>
    <color name="hypernova_nav_selected_inner_stroke">#6EEAF0</color>

    <!-- NOVA artwork glow -->
    <color name="hypernova_orb_cyan_center">#AA25D9E8</color>
    <color name="hypernova_orb_cyan_edge">#0025D9E8</color>
    <color name="hypernova_orb_purple_center">#AAA855F7</color>
    <color name="hypernova_orb_purple_edge">#00102737</color>
    <color name="hypernova_orb_center_highlight">#FFFFFF</color>

    <!-- Common -->
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>

</resources>
```

---

## 3. Final Dark palette

| Role | Resource | Value |
|---|---|---|
| Main background | `hypernova_background` | `#020812` |
| Secondary background | `hypernova_background_secondary` | `#061321` |
| Card | `hypernova_card` | `#071524` |
| Elevated card | `hypernova_card_elevated` | `#0B1B2C` |
| Selected surface | `hypernova_selected` | `#102337` |
| Primary text | `hypernova_text_primary` | `#F5F7FA` |
| Secondary text | `hypernova_text_secondary` | `#A7B0BE` |
| Disabled text | `hypernova_text_disabled` | `#687486` |
| Primary cyan | `hypernova_cyan` | `#25D9E8` |
| Bright cyan | `hypernova_cyan_bright` | `#43F2F4` |
| Pressed cyan | `hypernova_cyan_pressed` | `#1FC2D0` |
| Border | `hypernova_border` | `#506174` |
| Soft border | `hypernova_border_soft` | `#30465B` |
| Divider | `hypernova_divider` | `#293847` |
| Success | `hypernova_success` | `#39EA4B` |
| Warning | `hypernova_warning` | `#F5A623` |
| Error | `hypernova_error` | `#FF5E68` |

Full copy-ready file: `templates/res/values-night/colors.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>

<resources>

    <!-- Main backgrounds -->
    <color name="hypernova_background">#020812</color>
    <color name="hypernova_background_secondary">#061321</color>
    <color name="hypernova_background_glow">#102A46</color>

    <!-- Cards and selected surfaces -->
    <color name="hypernova_card">#071524</color>
    <color name="hypernova_card_elevated">#0B1B2C</color>
    <color name="hypernova_selected">#102337</color>

    <!-- Borders and dividers -->
    <color name="hypernova_border">#506174</color>
    <color name="hypernova_border_soft">#30465B</color>
    <color name="hypernova_divider">#293847</color>

    <!-- Primary HyperNova accent -->
    <color name="hypernova_cyan">#25D9E8</color>
    <color name="hypernova_cyan_bright">#43F2F4</color>
    <color name="hypernova_cyan_pressed">#1FC2D0</color>
    <color name="hypernova_cyan_dark">#0B8493</color>

    <!-- AI accent -->
    <color name="hypernova_purple">#A855F7</color>

    <!-- Text and icons -->
    <color name="hypernova_text_primary">#F5F7FA</color>
    <color name="hypernova_text_secondary">#A7B0BE</color>
    <color name="hypernova_text_disabled">#687486</color>
    <color name="hypernova_text_dark">#020A13</color>
    <color name="hypernova_icon_primary">#F5F7FA</color>

    <!-- Filled controls -->
    <color name="hypernova_control_filled_background">#F5F7FA</color>
    <color name="hypernova_control_filled_border">#DDE8F0</color>
    <color name="hypernova_control_filled_icon">#020A13</color>

    <!-- System status -->
    <color name="hypernova_success">#39EA4B</color>
    <color name="hypernova_warning">#F5A623</color>
    <color name="hypernova_error">#FF5E68</color>

    <!-- Launcher/background gradients -->
    <color name="hypernova_launcher_glow_start">#2430557A</color>
    <color name="hypernova_launcher_glow_center">#140A1D31</color>
    <color name="hypernova_launcher_glow_end">#00020812</color>

    <!-- Hero gradient -->
    <color name="hypernova_hero_start">#101B32</color>
    <color name="hypernova_hero_center">#071524</color>
    <color name="hypernova_hero_end">#06121F</color>

    <!-- Dashboard cards -->
    <color name="hypernova_dashboard_start">#E60B1B2C</color>
    <color name="hypernova_dashboard_end">#E6071524</color>

    <!-- Route information overlay -->
    <color name="hypernova_route_overlay_start">#F0061729</color>
    <color name="hypernova_route_overlay_end">#E60B2138</color>

    <!-- Bottom navigation -->
    <color name="hypernova_bottom_nav_start">#071320</color>
    <color name="hypernova_bottom_nav_center">#081827</color>
    <color name="hypernova_bottom_nav_end">#071320</color>

    <!-- Selected bottom navigation item -->
    <color name="hypernova_nav_selected_outer_fill">#3025D9E8</color>
    <color name="hypernova_nav_selected_outer_stroke">#9025D9E8</color>
    <color name="hypernova_nav_selected_inner_start">#35EBF3</color>
    <color name="hypernova_nav_selected_inner_end">#1CCAD9</color>
    <color name="hypernova_nav_selected_inner_stroke">#8AFAFF</color>

    <!-- NOVA artwork glow -->
    <color name="hypernova_orb_cyan_center">#AA25D9E8</color>
    <color name="hypernova_orb_cyan_edge">#0025D9E8</color>
    <color name="hypernova_orb_purple_center">#AAA855F7</color>
    <color name="hypernova_orb_purple_edge">#00102737</color>
    <color name="hypernova_orb_center_highlight">#FFFFFF</color>

    <!-- Common -->
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>

</resources>
```

---

## 4. Final theme files

Every application must use the shared style name `Theme.HyperNova`.

### `res/values/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>

<resources>

    <style
        name="Base.Theme.HyperNova"
        parent="Theme.Material3.DayNight.NoActionBar">

        <item name="colorPrimary">@color/hypernova_cyan</item>
        <item name="colorOnPrimary">@color/hypernova_text_dark</item>
        <item name="colorSecondary">@color/hypernova_purple</item>
        <item name="colorOnSecondary">@color/white</item>
        <item name="colorSurface">@color/hypernova_card</item>
        <item name="colorOnSurface">@color/hypernova_text_primary</item>
        <item name="colorSurfaceVariant">@color/hypernova_card_elevated</item>
        <item name="colorOnSurfaceVariant">@color/hypernova_text_secondary</item>
        <item name="colorOutline">@color/hypernova_border</item>
        <item name="colorError">@color/hypernova_error</item>
        <item name="colorOnError">@color/white</item>

        <item name="android:windowBackground">@color/hypernova_background</item>
        <item name="android:colorAccent">@color/hypernova_cyan</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:forceDarkAllowed">false</item>

    </style>

    <style
        name="Theme.HyperNova"
        parent="Base.Theme.HyperNova" />

</resources>
```

### `res/values-night/themes.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>

<resources>

    <style
        name="Base.Theme.HyperNova"
        parent="Theme.Material3.DayNight.NoActionBar">

        <item name="colorPrimary">@color/hypernova_cyan</item>
        <item name="colorOnPrimary">@color/hypernova_text_dark</item>
        <item name="colorSecondary">@color/hypernova_purple</item>
        <item name="colorOnSecondary">@color/white</item>
        <item name="colorSurface">@color/hypernova_card</item>
        <item name="colorOnSurface">@color/hypernova_text_primary</item>
        <item name="colorSurfaceVariant">@color/hypernova_card_elevated</item>
        <item name="colorOnSurfaceVariant">@color/hypernova_text_secondary</item>
        <item name="colorOutline">@color/hypernova_border</item>
        <item name="colorError">@color/hypernova_error</item>
        <item name="colorOnError">@color/white</item>

        <item name="android:windowBackground">@color/hypernova_background</item>
        <item name="android:colorAccent">@color/hypernova_cyan</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:forceDarkAllowed">false</item>

    </style>

    <style
        name="Theme.HyperNova"
        parent="Base.Theme.HyperNova" />

</resources>
```

Apply it in the application manifest:

```xml
<application
    android:theme="@style/Theme.HyperNova">
```

---

## 5. Resource usage rules

### Never hardcode a color

Wrong:

```xml
android:textColor="#FFFFFF"
android:background="#071524"
```

Correct:

```xml
android:textColor="@color/hypernova_text_primary"
android:background="@color/hypernova_card"
```

### Use semantic roles

| UI element | Required resource |
|---|---|
| Full-screen background | `hypernova_background` |
| Secondary background area | `hypernova_background_secondary` |
| Main card | `hypernova_card` or a drawable using dashboard gradient resources |
| Elevated card/dialog | `hypernova_card_elevated` |
| Main text | `hypernova_text_primary` |
| Description/status text | `hypernova_text_secondary` |
| Disabled text/icon | `hypernova_text_disabled` |
| Main action | `hypernova_cyan` |
| Selected item | `hypernova_selected` |
| Normal border | `hypernova_border` |
| Soft divider | `hypernova_divider` |
| Healthy/connected | `hypernova_success` |
| Attention | `hypernova_warning` |
| Failure/destructive | `hypernova_error` |

### Vector icons

Use a white path in the vector and tint it from the layout:

```xml
<ImageView
    ...
    android:src="@drawable/ic_example"
    app:tint="@color/hypernova_icon_primary" />
```

Do not make separate black and white icon files.

### Drawables

A shape drawable must reference resources:

```xml
<solid android:color="@color/hypernova_card" />

<stroke
    android:width="1dp"
    android:color="@color/hypernova_border" />
```

Do not put hex colors inside a drawable.

---

## 6. Light/Dark synchronization rules

1. All apps use `Theme.Material3.DayNight.NoActionBar`.
2. All apps provide the exact same semantic resource names in `values` and `values-night`.
3. Apps follow the system mode. They must not store independent Light/Dark preferences.
4. HyperNova Settings is the final owner of the system appearance control.
5. The Launcher may contain a development fallback toggle, but the final source of truth remains Android `uiMode`.
6. Do not call `AppCompatDelegate.setDefaultNightMode()` in each feature app.
7. Do not use `android:forceDarkAllowed="true"`; the UI has explicit colors for both modes.

Expected flow:

```text
HyperNova Settings changes Android night mode
                 |
                 v
Android sends a configuration update
                 |
                 v
Every HyperNova app reloads values/ or values-night/
```

---

## 7. Layout and automotive rules

- Target display: `1080 × 1920`, portrait.
- Use an 8dp spacing grid.
- Use at least 48dp touch targets for full application controls.
- Use large, glanceable labels.
- Do not use dense phone-style lists while driving.
- Primary controls must be reachable and visually distinct.
- Use one strong cyan primary action per section.
- Keep critical text at high contrast in both modes.
- Do not depend on color alone for error/success state.
- Avoid decorative animations that distract the driver.
- Keep screen geometry identical between Light and Dark modes.

Recommended shared dimensions are included in `templates/res/values/dimens.xml`.

---

## 8. Typography

Use the system Roboto family unless the team approves one bundled font for all apps.

| Role | Recommended size |
|---|---|
| Main screen title | 28–36sp |
| Section title | 18–24sp |
| Card title | 14–18sp |
| Main value/readout | 32–48sp |
| Body/status | 12–16sp |
| Supporting metadata | 10–13sp |

Rules:

- Primary text: `hypernova_text_primary`.
- Supporting text: `hypernova_text_secondary`.
- Disabled/unavailable: `hypernova_text_disabled`.
- Do not shrink essential text to fit. Use ellipsis and a detailed screen.

---

## 9. Required theme test

Build and install the app, then test both modes after Android finishes booting:

```bash
adb shell cmd uimode night no
adb shell am force-stop <PACKAGE_NAME>
adb shell monkey -p <PACKAGE_NAME> 1
```

```bash
adb shell cmd uimode night yes
adb shell am force-stop <PACKAGE_NAME>
adb shell monkey -p <PACKAGE_NAME> 1
```

Check:

- Background changes.
- Cards change.
- Text stays readable.
- Icons remain visible.
- Borders remain visible.
- No hardcoded dark surface remains in Light mode.
- No hardcoded light surface remains in Dark mode.
- Geometry does not move.

---

## 10. Design-system Definition of Done

- [ ] Uses `Theme.HyperNova`.
- [ ] Has `values/colors.xml`.
- [ ] Has `values-night/colors.xml`.
- [ ] Has `values/themes.xml`.
- [ ] Has `values-night/themes.xml`.
- [ ] No hardcoded UI colors.
- [ ] No duplicated Light/Dark layouts.
- [ ] Vector icons use resource tint.
- [ ] Light mode reviewed at 1080 × 1920.
- [ ] Dark mode reviewed at 1080 × 1920.
- [ ] All interactive targets are safe for IVI use.
