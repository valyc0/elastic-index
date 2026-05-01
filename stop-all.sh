#!/usr/bin/env bash
# stop-all.sh — Ferma Spring Boot + tutti i container Docker

set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "» === STOP SPRING BOOT ==="
# Cerca prima per pattern (spring-boot:run o jar), poi per porta 8080
PIDS=$(pgrep -f "my-app.*\.jar\|spring-boot:run" 2>/dev/null || true)
PIDS_PORT=$(lsof -ti tcp:8080 2>/dev/null || true)
ALL_PIDS=$(echo "$PIDS $PIDS_PORT" | tr ' ' '\n' | sort -u | tr '\n' ' ' | xargs)

if [[ -n "$ALL_PIDS" ]]; then
    echo "  Stopping Spring Boot (PID: $ALL_PIDS)..."
    kill $ALL_PIDS 2>/dev/null || true
    sleep 2
    # forza se ancora attivo
    REMAINING=$(lsof -ti tcp:8080 2>/dev/null || true)
    if [[ -n "$REMAINING" ]]; then
        echo "  Forzo kill (PID: $REMAINING)..."
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
