#!/usr/bin/env bash
#
# NXP Phone Runtime Setup Script
#
# This script checks various phone-related configurations and permissions
# without making any mutations except for the optional set-system-dialer.
# It verifies:
# - Required permissions are granted (parsed from pm output)
# - Exact system dialer (InCallService component) is set
# - HfpClientConnectionService is registered (full class name)
# - ContactsContract Phone rows, CallLog rows, and PBAP/account state
#
# Usage: ./nxp_phone_runtime_setup.sh [--set-system-dialer]
#

set -euo pipefail

# Exact system dialer component to enforce.
EXPECTED_DIALER="com.hypernova.phone/com.hypernova.phone.telecom.HyperNovaInCallService"

# Parse arguments
SET_SYSTEM_DIALER=false
for arg in "$@"; do
    case $arg in
        --set-system-dialer)
            SET_SYSTEM_DIALER=true
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Log functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if running on Android (requires adb)
if ! command -v adb &> /dev/null; then
    log_error "adb is not available. This script requires Android Debug Bridge."
    exit 1
fi

# Check Android connectivity
if ! adb get-state &> /dev/null; then
    log_error "No Android device connected via adb."
    exit 1
fi

# Check a single permission by parsing pm check-permission output.
check_permission() {
    local perm="$1"

    local output
    output=$(adb shell pm check-permission "$perm" com.hypernova.phone 2>/dev/null | tr -d '\r')

    if echo "$output" | grep -qi "granted"; then
        log_info "✓ $perm permission granted"
    else
        log_warn "⚠ $perm permission not granted"
    fi
}

# Check permissions
check_permissions() {
    log_info "Checking permissions..."

    check_permission "android.permission.READ_CONTACTS"
    check_permission "android.permission.READ_CALL_LOG"
    check_permission "android.permission.CALL_PHONE"
    check_permission "android.permission.BLUETOOTH_CONNECT"
    check_permission "android.permission.READ_PHONE_STATE"
    check_permission "android.permission.POST_NOTIFICATIONS"
}

# Check/optionally restore the exact system dialer component.
check_system_dialer() {
    log_info "Checking system dialer..."

    local current_dialer
    current_dialer=$(adb shell cmd telecom get-system-dialer 2>/dev/null | tr -d '\r\n')

    if [ "$current_dialer" = "$EXPECTED_DIALER" ]; then
        log_info "✓ HyperNova Phone is set as the exact system dialer"
    else
        log_warn "⚠ HyperNova Phone is not set as the exact system dialer (current: ${current_dialer:-none})"
        if [ "$SET_SYSTEM_DIALER" = true ]; then
            log_info "Setting HyperNova Phone as the exact system dialer..."
            adb shell cmd telecom set-system-dialer "$EXPECTED_DIALER"
            log_info "✓ Set HyperNova Phone as system dialer"
        fi
    fi
}

# Check HfpClientConnectionService registration by full class name.
check_hfp_phoneaccount() {
    log_info "Checking HfpClientConnectionService..."

    local hfp_status
    hfp_status=$(adb shell dumpsys telecom 2>/dev/null | tr -d '\r' | grep -c "com.android.bluetooth.hfpclient.HfpClientConnectionService" || true)

    if [ "$hfp_status" -gt 0 ]; then
        log_info "✓ HfpClientConnectionService is available"
    else
        log_warn "⚠ HfpClientConnectionService not available or not registered"
    fi
}

# Check ContactsContract Phone rows and PBAP/account state.
check_pbap_contacts() {
    log_info "Checking Contacts, CallLog, and PBAP state..."

    # Count real ContactsContract Phone rows.
    local contacts_output
    local contacts_count
    contacts_output=$(adb shell content query --uri content://com.android.contacts/data/phones --projection _id:display_name 2>/dev/null | tr -d '\r' || true)
    contacts_count=$(echo "$contacts_output" | grep -c '^Row:' || true)

    if [ "$contacts_count" -gt 0 ]; then
        log_info "✓ Found $contacts_count phone rows in ContactsContract"
    else
        log_warn "⚠ No phone rows found in ContactsContract"
    fi

    # Count real CallLog rows without printing personal call data.
    local call_log_output
    local call_log_count
    call_log_output=$(adb shell content query --uri content://call_log/calls --projection _id 2>/dev/null | tr -d '\r' || true)
    call_log_count=$(echo "$call_log_output" | grep -c '^Row:' || true)

    if [ "$call_log_count" -gt 0 ]; then
        log_info "✓ Found $call_log_count rows in CallLog"
    else
        log_warn "⚠ No rows found in CallLog"
    fi

    # Inspect PBAP/account state from the Bluetooth stack dumpsys.
    local bluetooth_dump
    bluetooth_dump=$( (adb shell dumpsys bluetooth_manager 2>/dev/null || adb shell dumpsys bluetooth 2>/dev/null) | tr -d '\r' )

    local pbap_state
    pbap_state=$(echo "$bluetooth_dump" | grep -ci "PBAP" || true)

    if [ "$pbap_state" -gt 0 ]; then
        log_info "✓ PBAP/account state present in Bluetooth dumpsys"
    else
        log_warn "⚠ No PBAP/account state found in Bluetooth dumpsys"
    fi
}

# Main execution
main() {
    log_info "Starting NXP Phone Runtime Setup Check..."

    check_permissions
    check_system_dialer
    check_hfp_phoneaccount
    check_pbap_contacts

    log_info "NXP Phone Runtime Setup Check completed."
}

# Run main function
main
