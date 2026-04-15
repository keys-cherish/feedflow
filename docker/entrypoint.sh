#!/bin/sh
# FeedFlow Docker Entrypoint
# Handles graceful shutdown and automatic restart on crash

set -e

# Trap SIGTERM/SIGINT for graceful shutdown
FEEDFLOW_PID=""
cleanup() {
    echo "[entrypoint] Received shutdown signal, stopping FeedFlow..."
    if [ -n "$FEEDFLOW_PID" ] && kill -0 "$FEEDFLOW_PID" 2>/dev/null; then
        kill -TERM "$FEEDFLOW_PID"
        wait "$FEEDFLOW_PID" 2>/dev/null || true
    fi
    echo "[entrypoint] FeedFlow stopped."
    exit 0
}
trap cleanup TERM INT QUIT

echo "============================================"
echo "  FeedFlow RSS Reader"
echo "  Timezone: ${TZ:-UTC}"
echo "  Database: ${FEEDFLOW_DB_PATH:-/data/feedflow.db}"
echo "  Port:     ${FEEDFLOW_PORT:-3200}"
echo "  Log:      ${FEEDFLOW_LOG_LEVEL:-info}"
echo "  Refresh:  ${FEEDFLOW_REFRESH_INTERVAL:-900}s"
echo "============================================"

# Ensure data directory exists
mkdir -p "$(dirname "${FEEDFLOW_DB_PATH:-/data/feedflow.db}")"

# Auto-restart loop: restart on crash, stop on signal
MAX_RESTARTS=5
RESTART_COUNT=0
RESTART_WINDOW=60

while true; do
    RESTART_TIME=$(date +%s)

    echo "[entrypoint] Starting FeedFlow..."
    /app/feedflow &
    FEEDFLOW_PID=$!
    wait "$FEEDFLOW_PID"
    EXIT_CODE=$?
    FEEDFLOW_PID=""

    # Exit code 0 means clean shutdown (e.g. from SIGTERM)
    if [ "$EXIT_CODE" -eq 0 ]; then
        echo "[entrypoint] FeedFlow exited cleanly."
        break
    fi

    # Check restart window — reset counter if enough time passed
    NOW=$(date +%s)
    ELAPSED=$((NOW - RESTART_TIME))
    if [ "$ELAPSED" -gt "$RESTART_WINDOW" ]; then
        RESTART_COUNT=0
    fi

    RESTART_COUNT=$((RESTART_COUNT + 1))
    if [ "$RESTART_COUNT" -ge "$MAX_RESTARTS" ]; then
        echo "[entrypoint] FeedFlow crashed $MAX_RESTARTS times within ${RESTART_WINDOW}s. Giving up."
        exit 1
    fi

    echo "[entrypoint] FeedFlow exited with code $EXIT_CODE. Restarting in 3s... ($RESTART_COUNT/$MAX_RESTARTS)"
    sleep 3
done
