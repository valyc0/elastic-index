#!/usr/bin/env bash
# reset-all.sh — Reset completo: ferma tutto, rimuove volumi e file temporanei
#
# Uso:
#   ./reset-all.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✔ $*${NC}"; }
info() { echo -e "${YELLOW}  » $*${NC}"; }
step() { echo -e "${CYAN}━━ $* ━━${NC}"; }

# ── 1. Spring Boot ────────────────────────────────────────────────────────────
echo ""
step "1. STOP SPRING BOOT"

PIDS=$(pgrep -f "my-app.*\.jar\|spring-boot:run" 2>/dev/null || true)
if [[ -n "$PIDS" ]]; then
    info "Stopping Spring Boot (PID: $PIDS)..."
    kill $PIDS 2>/dev/null || true
    sleep 2
    REMAINING=$(pgrep -f "my-app.*\.jar\|spring-boot:run" 2>/dev/null || true)
    [[ -n "$REMAINING" ]] && kill -9 $REMAINING 2>/dev/null || true
    ok "Spring Boot fermato"
else
    ok "Spring Boot non in esecuzione"
fi

# Porta 8080 residua
PIDS_8080=$(lsof -ti tcp:8080 2>/dev/null || true)
if [[ -n "$PIDS_8080" ]]; then
    info "Processo residuo su porta 8080 (PID: $PIDS_8080) — kill forzato"
    kill -9 $PIDS_8080 2>/dev/null || true
    ok "Porta 8080 liberata"
fi

# ── 2. Docker: container + volumi + reti ─────────────────────────────────────
echo ""
step "2. STOP DOCKER CONTAINERS + VOLUMI"

cd "$SCRIPT_DIR"
docker compose down -v --remove-orphans 2>&1 | sed 's/^/  /'
ok "Container, volumi e reti rimossi"

# ── 3. File temporanei ────────────────────────────────────────────────────────
echo ""
step "3. FILE TEMPORANEI"

LOG_FILES=(/tmp/spring-*.log /tmp/docling-*.log)
REMOVED=0
for f in "${LOG_FILES[@]}"; do
    if [[ -f "$f" ]]; then
        rm -f "$f"
        REMOVED=$((REMOVED + 1))
    fi
done
[[ $REMOVED -gt 0 ]] && ok "$REMOVED file di log temporanei rimossi" || ok "Nessun log temporaneo"

# ── Fine ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}━━ RESET COMPLETATO ━━${NC}"
echo ""
echo "  Per riavviare tutto:  ./run-and-test-docling.sh"
echo "  Per rebuild immagini: docker compose build --no-cache"
echo ""
