#!/usr/bin/env bash
set -euo pipefail

RUNTIME_DIR="/tmp/hypernova-wdt-supervisor-${USER}"
PID_FILE="$RUNTIME_DIR/supervisor.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "HyperNova WDT supervisor is not running."
    exit 0
fi

PID="$(cat "$PID_FILE" 2>/dev/null || true)"

if [[ -n "$PID" ]] &&
   kill -0 "$PID" 2>/dev/null; then

    kill "$PID"

    echo "Stopped HyperNova WDT supervisor."
    echo "PID: $PID"
else
    echo "Supervisor process is already stopped."
fi

rm -f "$PID_FILE"
