#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="$project_root/artifacts/ui-review/screenshots"
mkdir -p "$output_dir"
stamp="$(date +%Y%m%d-%H%M%S)"
device_file="/sdcard/hypernova-phone-$stamp.png"
local_file="$output_dir/hypernova-phone-$stamp.png"

adb shell am start -a com.hypernova.phone.action.OPEN -p com.hypernova.phone >/dev/null
adb shell screencap -p "$device_file"
adb pull "$device_file" "$local_file"
adb shell rm -f "$device_file"
printf 'Captured %s\n' "$local_file"
