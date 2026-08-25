#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys
from datetime import datetime

WDT_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = WDT_ROOT.parent
LAUNCHER = REPO_ROOT / "HyperNova_Launcher_Task_01"

layout = LAUNCHER / "app/src/main/res/layout/activity_main.xml"
activity = LAUNCHER / "app/src/main/java/com/hypernova/launcher/MainActivity.kt"
manifest = LAUNCHER / "app/src/main/AndroidManifest.xml"
strings = LAUNCHER / "app/src/main/res/values/strings.xml"
icon_src = WDT_ROOT / "app/src/main/res/drawable-nodpi/ic_power.png"
icon_dst = LAUNCHER / "app/src/main/res/drawable-nodpi/ic_nav_power.png"

required = [layout, activity, manifest, strings, icon_src]
missing = [str(p) for p in required if not p.exists()]
if missing:
    print("ERROR: missing expected file(s):")
    for p in missing:
        print("  " + p)
    sys.exit(1)

timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
backup_dir = WDT_ROOT / "backups" / f"launcher-power-nav-{timestamp}"
backup_dir.mkdir(parents=True, exist_ok=True)
for src in [layout, activity, manifest, strings]:
    shutil.copy2(src, backup_dir / src.name)

layout_text = layout.read_text(encoding="utf-8")
if "@+id/navPower" not in layout_text:
    pattern = re.compile(r'<FrameLayout android:id="@\+id/navSettings".*?</FrameLayout>', re.DOTALL)
    replacement = (
        '<FrameLayout android:id="@+id/navPower" '
        'android:layout_width="0dp" '
        'android:layout_height="match_parent" '
        'android:layout_weight="1" '
        'android:clickable="true" '
        'android:focusable="true" '
        'android:foreground="?attr/selectableItemBackgroundBorderless">'
        '<ImageView android:layout_width="40dp" '
        'android:layout_height="40dp" '
        'android:layout_gravity="center" '
        'android:alpha="0.88" '
        'android:contentDescription="@string/bottom_nav_power" '
        'android:scaleType="centerInside" '
        'android:src="@drawable/ic_nav_power" '
        'app:tint="@color/hypernova_text_secondary" />'
        '</FrameLayout>'
    )
    layout_text, count = pattern.subn(replacement, layout_text, count=1)
    if count != 1:
        print("ERROR: could not find current navSettings block in activity_main.xml")
        sys.exit(1)
    layout.write_text(layout_text, encoding="utf-8")

activity_text = activity.read_text(encoding="utf-8")
if "binding.navPower.setOnClickListener" not in activity_text:
    pattern = re.compile(
        r'\s*configureDestinationClick\(\s*'
        r'view\s*=\s*binding\.navSettings,\s*'
        r'destination\s*=\s*AppDestination\.SETTINGS\s*'
        r'\)\s*',
        re.DOTALL,
    )
    replacement = '''
        binding.navPower.isClickable = true
        binding.navPower.isFocusable = true
        binding.navPower.setOnClickListener {
            openSystemControl()
        }

'''
    activity_text, count = pattern.subn(replacement, activity_text, count=1)
    if count != 1:
        print("ERROR: could not find current navSettings click binding in MainActivity.kt")
        sys.exit(1)

if "private fun openSystemControl()" not in activity_text:
    anchor = "    private fun configureDestinationClick("
    method = '''    /**
     * Open HyperNova System Control from the power button in the fixed bottom bar.
     */
    private fun openSystemControl() {
        val openIntent =
            Intent("com.hypernova.wdt.action.OPEN").apply {
                setPackage("com.hypernova.wdt")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        runCatching {
            startActivity(openIntent)
        }.onFailure { primaryFailure ->
            Log.w(
                TAG,
                "System Control OPEN action failed; trying package launch intent",
                primaryFailure,
            )

            val fallback = packageManager.getLaunchIntentForPackage("com.hypernova.wdt")
            if (fallback != null) {
                fallback.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                runCatching {
                    startActivity(fallback)
                }.onFailure { fallbackFailure ->
                    Log.e(TAG, "Could not open HyperNova System Control", fallbackFailure)
                    Toast.makeText(
                        this,
                        getString(R.string.system_control_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.system_control_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

'''
    if anchor not in activity_text:
        print("ERROR: insertion anchor not found in MainActivity.kt")
        sys.exit(1)
    activity_text = activity_text.replace(anchor, method + anchor, 1)
activity.write_text(activity_text, encoding="utf-8")

manifest_text = manifest.read_text(encoding="utf-8")
if 'android:name="com.hypernova.wdt"' not in manifest_text:
    query_block = '''        <!-- HyperNova System Control -->
        <package android:name="com.hypernova.wdt" />

        <intent>
            <action android:name="com.hypernova.wdt.action.OPEN" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent>

'''
    marker = "        <!-- HyperNova Settings -->"
    if marker in manifest_text:
        manifest_text = manifest_text.replace(marker, query_block + marker, 1)
    else:
        manifest_text = manifest_text.replace("</queries>", query_block + "    </queries>", 1)
    manifest.write_text(manifest_text, encoding="utf-8")

strings_text = strings.read_text(encoding="utf-8")
additions = []
if 'name="bottom_nav_power"' not in strings_text:
    additions.append('    <string name="bottom_nav_power">System control</string>')
if 'name="system_control_unavailable"' not in strings_text:
    additions.append('    <string name="system_control_unavailable">System Control is not installed</string>')
if additions:
    if "</resources>" not in strings_text:
        print("ERROR: strings.xml has no </resources> closing tag")
        sys.exit(1)
    strings_text = strings_text.replace(
        "</resources>",
        "\n" + "\n".join(additions) + "\n</resources>",
        1,
    )
    strings.write_text(strings_text, encoding="utf-8")

icon_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(icon_src, icon_dst)

print("PASS: Settings bottom-bar icon -> Power.")
print("PASS: Power opens com.hypernova.wdt.action.OPEN.")
print("Backup:")
print(f"  {backup_dir}")
