#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-192.168.0.100:5555}"

SCRIPT_DIR="$(
    cd "$(dirname "${BASH_SOURCE[0]}")" &&
    pwd
)"

RUNTIME_DIR="/tmp/hypernova-wdt-supervisor-${USER}"
PID_FILE="$RUNTIME_DIR/supervisor.pid"
LOG_FILE="$RUNTIME_DIR/supervisor.log"

mkdir -p "$RUNTIME_DIR"

if [[ -f "$PID_FILE" ]]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null || true)"

    if [[ -n "$OLD_PID" ]] &&
       kill -0 "$OLD_PID" 2>/dev/null; then

        echo "HyperNova WDT supervisor already running."
        echo "PID: $OLD_PID"
        echo "LOG: $LOG_FILE"
        exit 0
    fi

    rm -f "$PID_FILE"
fi

nohup env \
    ADB_SERIAL="$ADB_SERIAL" \
    "$SCRIPT_DIR/watch_root_backend.sh" \
    >"$LOG_FILE" \
    2>&1 \
    </dev/null &

PID=$!

echo "$PID" > "$PID_FILE"

sleep 1

if ! kill -0 "$PID" 2>/dev/null; then
    echo "ERROR: supervisor failed to start."
    cat "$LOG_FILE" || true
    exit 1
fi

echo "========================================"
echo "HyperNova WDT supervisor started"
echo "========================================"
echo "PID: $PID"
echo "Device: $ADB_SERIAL"
echo "Log: $LOG_FILE"
echo
echo "View log:"
echo "  tail -f $LOG_FILE"
