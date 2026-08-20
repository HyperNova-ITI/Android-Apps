#!/usr/bin/env bash

set -euo pipefail

APP="$(pwd)"

MAIN="$APP/app/src/main/java/com/hypernova/launcher/MainActivity.kt"
ORDER="$APP/app/src/main/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrder.kt"
TEST="$APP/app/src/test/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrderTest.kt"

STAMP="$(date +%Y%m%d_%H%M%S)"
ARTIFACTS="$APP/launcher_map_focus_artifacts/$STAMP"
BACKUP="$ARTIFACTS/backups"
LOGS="$ARTIFACTS/logs"

mkdir -p "$BACKUP" "$LOGS"

echo
echo "============================================================"
echo " HYPERNOVA LAUNCHER - MAP FOCUS HOME"
echo "============================================================"
echo

echo "Project:"
echo "$APP"

echo
echo "===== 1. CURRENT STATE ====="

git status --short --branch || true
git log -1 --oneline || true

echo
echo "===== 2. BACKUP ====="

cp "$MAIN"  "$BACKUP/MainActivity.kt"
cp "$ORDER" "$BACKUP/DashboardLayoutOrder.kt"
cp "$TEST"  "$BACKUP/DashboardLayoutOrderTest.kt"

echo "[OK] Backup saved:"
echo "$BACKUP"

echo
echo "===== 3. UPDATE DASHBOARD ORDER ====="

cat > "$ORDER" <<'KOTLIN'
package com.hypernova.launcher.core.dashboard

enum class DashboardCard {
    CLIMATE,
    MEDIA,
    SETTINGS,
    PHONE,
    NAVIGATION,
}

/**
 * Product-approved HOME hierarchy.
 *
 * Phone and Settings remain available from the fixed bottom navigation bar
 * and are intentionally not rendered as HOME dashboard widgets.
 */
object DashboardLayoutOrder {

    val firstRow = listOf(
        DashboardCard.CLIMATE,
        DashboardCard.MEDIA,
    )

    val dominantRow = listOf(
        DashboardCard.NAVIGATION,
    )
}
KOTLIN

echo "[OK] Dashboard order updated"

echo
echo "===== 4. UPDATE DASHBOARD TEST ====="

cat > "$TEST" <<'KOTLIN'
package com.hypernova.launcher.core.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutOrderTest {

    @Test
    fun `dashboard keeps only driving priority widgets on home`() {
        assertEquals(
            listOf(
                DashboardCard.CLIMATE,
                DashboardCard.MEDIA,
            ),
            DashboardLayoutOrder.firstRow,
        )

        assertEquals(
            listOf(DashboardCard.NAVIGATION),
            DashboardLayoutOrder.dominantRow,
        )
    }
}
KOTLIN

echo "[OK] Dashboard test updated"

echo
echo "===== 5. UPDATE MAIN ACTIVITY ====="

python3 <<'PY'
from pathlib import Path

path = Path(
    "/home/ayman/ITI/Android-Apps/HyperNova_Launcher_Task_01/"
    "app/src/main/java/com/hypernova/launcher/MainActivity.kt"
)

text = path.read_text()

def replace_once(text, old, new, name):
    count = text.count(old)

    if count == 0:
        # Allow safe re-run when the new content is already present.
        if new in text:
            print(f"[SKIP] {name} already updated")
            return text

        raise SystemExit(
            f"\n[ERROR] Could not find expected block: {name}\n"
            "No unsafe replacement was performed."
        )

    if count != 1:
        raise SystemExit(
            f"\n[ERROR] Found {count} copies of block: {name}\n"
            "Stopping to avoid an unsafe edit."
        )

    print(f"[OK] Updating {name}")
    return text.replace(old, new, 1)


# ------------------------------------------------------------
# A. onCreate:
# remove Phone widget + Settings widget action configuration.
# Keep Climate widget configuration.
# ------------------------------------------------------------

old = '''        configureNavigationCard()
        configureMediaCard()
        configurePhoneAndClimateActions()
        configureSettingsCard()
        configureBottomNavigation()
'''

new = '''        configureNavigationCard()
        configureMediaCard()
        configureClimateActions()
        configureBottomNavigation()
'''

text = replace_once(
    text,
    old,
    new,
    "onCreate dashboard actions",
)


# ------------------------------------------------------------
# B. Dashboard layout:
# Climate + Media
# Large Navigation
#
# Phone + Settings row becomes GONE.
# ------------------------------------------------------------

old = '''    /**
     * Reuse the approved card implementations while placing them in the final
     * hierarchy. The weighted Navigation row consumes the remaining viewport;
     * the surrounding NestedScrollView provides controlled overflow only when
     * a smaller portrait display cannot satisfy the Navigation minimum height.
     */
    private fun configureResponsiveDashboardLayout() {
        val cards = mapOf(
            DashboardCard.CLIMATE to binding.climateCard,
            DashboardCard.MEDIA to binding.mediaCard,
            DashboardCard.SETTINGS to binding.settingsCard,
            DashboardCard.PHONE to binding.phoneCard,
            DashboardCard.NAVIGATION to binding.navigationCard,
        )

        binding.climateMediaRow.removeAllViews()
        binding.settingsPhoneRow.removeAllViews()
        binding.navigationDashboardRow.removeAllViews()

        addHalfWidthRow(binding.climateMediaRow, DashboardLayoutOrder.firstRow, cards)
        addHalfWidthRow(binding.settingsPhoneRow, DashboardLayoutOrder.secondRow, cards)
        binding.navigationDashboardRow.addView(
            requireNotNull(cards[DashboardLayoutOrder.dominantRow.single()]),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
'''

new = '''    /**
     * Keep only driving-priority widgets on HOME:
     *
     *   Climate | Media
     *   Navigation
     *
     * Phone and Settings remain available from the fixed bottom navigation bar.
     * Their previous dashboard row is completely collapsed so Navigation can
     * consume the released vertical space.
     */
    private fun configureResponsiveDashboardLayout() {
        val cards = mapOf(
            DashboardCard.CLIMATE to binding.climateCard,
            DashboardCard.MEDIA to binding.mediaCard,
            DashboardCard.NAVIGATION to binding.navigationCard,
        )

        binding.climateMediaRow.removeAllViews()

        // Phone and Settings are controlled from the bottom bar only.
        binding.settingsPhoneRow.removeAllViews()
        binding.settingsPhoneRow.visibility = View.GONE

        binding.navigationDashboardRow.removeAllViews()

        addHalfWidthRow(
            binding.climateMediaRow,
            DashboardLayoutOrder.firstRow,
            cards,
        )

        binding.navigationDashboardRow.addView(
            requireNotNull(cards[DashboardLayoutOrder.dominantRow.single()]),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
'''

text = replace_once(
    text,
    old,
    new,
    "responsive dashboard layout",
)


# ------------------------------------------------------------
# C. Remove Phone widget actions + Settings widget action.
# Keep Climate HOME widget actions.
# ------------------------------------------------------------

old = '''    /**
     * Configure Phone and Climate cards.
     */
    private fun configurePhoneAndClimateActions() {
        configureDestinationClick(
            view = binding.phoneCard,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.buttonOpenPhone,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.buttonPhoneContacts,
            destination = AppDestination.PHONE
        )

        configureDestinationClick(
            view = binding.climateCard,
            destination = AppDestination.CLIMATE
        )

        configureDestinationClick(
            view = binding.buttonOpenClimate,
            destination = AppDestination.CLIMATE
        )
    }

    /** Configure the Settings dashboard card. */
    private fun configureSettingsCard() {
        configureDestinationClick(
            view = binding.settingsCard,
            destination = AppDestination.SETTINGS
        )
    }
'''

new = '''    /**
     * Configure the Climate HOME widget.
     *
     * Phone and Settings are intentionally bottom-bar-only destinations.
     */
    private fun configureClimateActions() {
        configureDestinationClick(
            view = binding.climateCard,
            destination = AppDestination.CLIMATE
        )

        configureDestinationClick(
            view = binding.buttonOpenClimate,
            destination = AppDestination.CLIMATE
        )
    }
'''

text = replace_once(
    text,
    old,
    new,
    "Phone / Climate / Settings dashboard actions",
)

path.write_text(text)

print("[OK] MainActivity.kt updated")
PY

echo
echo "===== 6. VERIFY OLD DASHBOARD LOGIC ====="

if grep -R -n \
    -E \
    'DashboardLayoutOrder\.secondRow|configurePhoneAndClimateActions|configureSettingsCard' \
    "$MAIN" "$ORDER" "$TEST"
then
    echo
    echo "[ERROR] Old Phone/Settings HOME logic still exists."
    exit 1
else
    echo "[OK] Old Phone/Settings HOME logic removed"
fi

echo
echo "===== 7. VERIFY BOTTOM BAR ====="

PHONE_BOTTOM="$(grep -c 'view = binding.navPhone' "$MAIN" || true)"
SETTINGS_BOTTOM="$(grep -c 'view = binding.navSettings' "$MAIN" || true)"

if [ "$PHONE_BOTTOM" -lt 1 ]; then
    echo "[ERROR] navPhone missing from bottom bar"
    exit 1
fi

if [ "$SETTINGS_BOTTOM" -lt 1 ]; then
    echo "[ERROR] navSettings missing from bottom bar"
    exit 1
fi

echo "[OK] Phone bottom-bar control preserved"
echo "[OK] Settings bottom-bar control preserved"

echo
echo "===== 8. VERIFY PHONE BACKEND ====="

if grep -q 'PhoneStatusClient' "$MAIN"; then
    echo "[OK] PhoneStatusClient preserved for NOVA/state integration"
else
    echo "[ERROR] PhoneStatusClient unexpectedly missing"
    exit 1
fi

echo
echo "===== 9. VERIFY MAP EXPANSION ====="

if grep -q 'binding.settingsPhoneRow.visibility = View.GONE' "$MAIN"; then
    echo "[OK] Phone/Settings row collapses completely"
else
    echo "[ERROR] settingsPhoneRow is not GONE"
    exit 1
fi

echo
echo "===== 10. GIT DIFF CHECK ====="

cd "$APP"

git diff --check -- \
    app/src/main/java/com/hypernova/launcher/MainActivity.kt \
    app/src/main/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrder.kt \
    app/src/test/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrderTest.kt

echo "[OK] git diff --check passed"

git diff -- \
    app/src/main/java/com/hypernova/launcher/MainActivity.kt \
    app/src/main/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrder.kt \
    app/src/test/java/com/hypernova/launcher/core/dashboard/DashboardLayoutOrderTest.kt \
    > "$LOGS/launcher_map_focus.diff"

echo
echo "===== 11. BUILD + UNIT TEST ====="

./gradlew \
    testDebugUnitTest \
    assembleDebug \
    --no-daemon \
    --max-workers=10 \
    2>&1 | tee "$LOGS/build.log"

echo
echo "===== 12. APK ====="

APK="$APP/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
    echo "[ERROR] APK was not generated"
    exit 1
fi

ls -lh "$APK"

echo
echo "===== 13. FINAL SOURCE STATUS ====="

git status --short

echo
echo "============================================================"
echo " SUCCESS"
echo "============================================================"
echo
echo "HOME:"
echo
echo "  NOVA"
echo
echo "  [ CLIMATE ] [ MEDIA ]"
echo "  [                    ]"
echo "  [   NAVIGATION MAP   ]"
echo "  [                    ]"
echo
echo "Phone widget:       REMOVED"
echo "Settings widget:    REMOVED"
echo "Phone bottom bar:   PRESERVED"
echo "Settings bottom bar:PRESERVED"
echo "Phone backend:      PRESERVED"
echo "Navigation map:     EXPANDED"
echo
echo "APK:"
echo "$APK"
echo
echo "Artifacts:"
echo "$ARTIFACTS"
echo
echo "NO commit"
echo "NO push"
echo "NO adb install"
echo "NO reboot"
echo
