#!/usr/bin/env bash
set -u

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"

SCRIPT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")" &&
    pwd
)"

START_BACKEND="$SCRIPT_DIR/start_root_backend.sh"

CONNECT_DELAY="${CONNECT_DELAY:-2}"
MONITOR_DELAY="${MONITOR_DELAY:-2}"

log()
{
    printf '[%s] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" \
        "$*"
}

device_ready()
{
    local state

    state="$(
        adb -s "$ADB_SERIAL" get-state \
            2>/dev/null || true
    )"

    [[ "$state" == "device" ]]
}

connect_device()
{
    while true; do
        adb connect "$ADB_SERIAL" \
            >/dev/null 2>&1 || true

        if device_ready; then
            log "ADB device connected: $ADB_SERIAL"
            return 0
        fi

        log "Waiting for Android ADB..."
        sleep "$CONNECT_DELAY"
    done
}

wait_for_boot()
{
    while true; do
        if ! device_ready; then
            log "ADB disconnected while waiting for boot."
            return 1
        fi

        local boot_completed

        boot_completed="$(
            adb -s "$ADB_SERIAL" shell \
                getprop sys.boot_completed \
                2>/dev/null |
            tr -d '\r'
        )"

        if [[ "$boot_completed" == "1" ]]; then
            log "Android boot completed."
            return 0
        fi

        log "Waiting for sys.boot_completed=1..."
        sleep "$CONNECT_DELAY"
    done
}

backend_running()
{
    local output

    output="$(
        adb -s "$ADB_SERIAL" shell \
            "su 0 sh -c '/system/bin/toybox ps -A -o ARGS 2>/dev/null | grep \"[R]ootBackendServer\"'" \
            2>/dev/null || true
    )"

    [[ -n "$output" ]]
}

start_backend()
{
    log "Starting HyperNova WDT root backend..."

    if ADB_SERIAL="$ADB_SERIAL" \
        "$START_BACKEND"; then

        sleep 1

        if backend_running; then
            log "ROOT BACKEND READY"
            return 0
        fi

        log "Backend start command returned but process is missing."
        return 1
    fi

    log "Backend start failed."
    return 1
}

log "========================================"
log "HyperNova WDT Backend Supervisor"
log "Device: $ADB_SERIAL"
log "========================================"

while true; do

    connect_device

    if ! wait_for_boot; then
        sleep "$CONNECT_DELAY"
        continue
    fi

    if ! start_backend; then
        sleep "$CONNECT_DELAY"
        continue
    fi

    log "Monitoring backend and Android..."

    while true; do

        if ! device_ready; then
            log "Android disconnected."
            log "Waiting for reboot / recovery..."
            break
        fi

        if ! backend_running; then
            log "Root backend disappeared while Android is alive."

            if start_backend; then
                log "Backend recovered."
            else
                log "Backend recovery failed."
            fi
        fi

        sleep "$MONITOR_DELAY"
    done

    sleep "$CONNECT_DELAY"
done
