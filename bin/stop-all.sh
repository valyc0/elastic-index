#!/usr/bin/env bash
# stop-all.sh — Ferma Spring Boot + tutti i container Docker

set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "» === STOP SPRING BOOT ==="
PIDS=$(pgrep -f "my-app.*\.jar\|spring-boot:run" 2>/dev/null || true)
if [[ -n "$PIDS" ]]; then
    echo "  Stopping Spring Boot (PID: $PIDS)..."
    kill $PIDS
    sleep 2
    # forza se ancora attivo
    REMAINING=$(pgrep -f "my-app.*\.jar\|spring-boot:run" 2>/dev/null || true)
    if [[ -n "$REMAINING" ]]; then
        kill -9 $REMAINING 2>/dev/null || true
    fi
    echo "  ✔ Spring Boot fermato"
else
    echo "  – Spring Boot non in esecuzione"
fi

echo ""
echo "» === STOP DOCKER CONTAINERS ==="
cd "$BASE_DIR"
if docker compose ps --quiet 2>/dev/null | grep -q .; then
    docker compose down
    echo "  ✔ Container fermati"
else
    echo "  – Nessun container attivo"
fi

echo ""
echo "✔ === TUTTO FERMATO ==="
