# HyperNova Launcher — Production Light and Dark Theme

## Theme Policy

HyperNova Launcher does not keep an application-local theme preference.
It follows the Android system day/night configuration.

This is intentional:

```text
HyperNova Settings
        ↓
Android system night mode
        ↓
Launcher + Navigation + Media + Phone + Climate + Weather + NOVA AI
```

The launcher must not use a temporary long-press menu, hidden debug toggle,
or private `SharedPreferences` value for selecting Light or Dark mode.

## Resource Structure

```text
app/src/main/res/values/colors.xml
    Light scheme

app/src/main/res/values-night/colors.xml
    Dark scheme

app/src/main/res/values/themes.xml
    Light window attributes

app/src/main/res/values-night/themes.xml
    Dark window attributes
```

The same layout and drawable files are reused in both modes. Drawables use
semantic color resources instead of fixed hexadecimal colors.

## System Bar Behavior

The launcher is full screen, but transient system bars can appear after a
swipe. `MainActivity` reads the current `uiMode` configuration and selects
light or dark system-bar icons so they remain visible in both themes.

## Testing on Cuttlefish

Use the system UI mode command to test the same path that the future
HyperNova Settings application will control.

### Light

```bash
adb shell cmd uimode night no
adb shell input keyevent KEYCODE_HOME
```

### Dark

```bash
adb shell cmd uimode night yes
adb shell input keyevent KEYCODE_HOME
```

### Automatic/System Policy

```bash
adb shell cmd uimode night auto
adb shell input keyevent KEYCODE_HOME
```

## Requirement for Other HyperNova Applications

Every HyperNova application must:

1. Use a `DayNight` application theme.
2. Define its light semantic colors under `values/`.
3. Define matching dark semantic colors under `values-night/`.
4. Avoid fixed surface and text colors inside layouts and drawables.
5. Follow the system mode rather than saving a separate local mode.

A shared HyperNova design-token Android library can be introduced later so
all applications consume one approved color and component system.

## Launcher status-bar toggle

The launcher includes a Light/Dark toggle in the top status bar.

- In Light mode, the button shows a Moon and switches Android to Dark mode.
- In Dark mode, the button shows a Sun and switches Android to Light mode.
- The controller uses `UiModeManager.setNightMode()` so the configuration change is system-wide.
- Every HyperNova application must use a DayNight theme and provide matching `values/` and `values-night/` resources to follow the same system mode.
