#!/usr/bin/env bash
# start-all.sh — Avvia l'intera stack: Docker (ES + Ollama + Docling) + Spring Boot
#
# Uso:
#   ./start-all.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DOCLING_URL="${DOCLING_URL:-http://localhost:8001}"
ES_URL="${ES_URL:-http://localhost:9200}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✔ $*${NC}"; }
fail() { echo -e "${RED}  ✘ $*${NC}"; exit 1; }
info() { echo -e "${YELLOW}  » $*${NC}"; }
step() { echo -e "${CYAN}━━ $* ━━${NC}"; }

wait_for_http() {
  local label="$1" url="$2" timeout="${3:-90}"
  local elapsed=0
  echo -n "  Attendo $label"
  while ! curl -s --max-time 3 "$url" > /dev/null 2>&1; do
    sleep 2; elapsed=$((elapsed + 2))
    echo -n "."
    if [[ $elapsed -ge $timeout ]]; then
      echo ""
      fail "$label non risponde dopo ${timeout}s"
    fi
  done
  echo ""
  ok "$label disponibile"
}

# ── 1. Docker ─────────────────────────────────────────────────────────────────
echo ""
step "1. AVVIO DOCKER (Elasticsearch, Ollama, Docling service)"

cd "$SCRIPT_DIR"
docker compose up -d elasticsearch ollama docling-service
info "Container avviati (o già in esecuzione)"

wait_for_http "Elasticsearch"  "$ES_URL/_cluster/health" 90
wait_for_http "Docling service" "$DOCLING_URL/health"    120

# ── Verifica modelli Ollama richiesti dal profilo attivo ──────────────────────
echo ""
step "1b. MODELLI OLLAMA"

APP_PROPS="$SCRIPT_DIR/my-app/src/main/resources/application.properties"
ACTIVE_PROFILE=$(grep -E '^spring\.profiles\.active=' "$APP_PROPS" 2>/dev/null \
  | cut -d= -f2 | tr -d '[:space:]' || echo "openrouter")
info "Profilo attivo: $ACTIVE_PROFILE"

# Legge i modelli Ollama richiesti dal profilo
PROFILE_PROPS="$SCRIPT_DIR/my-app/src/main/resources/application-${ACTIVE_PROFILE}.properties"
REQUIRED_MODELS=()

if [[ -f "$PROFILE_PROPS" ]]; then
  EMBED_PROVIDER=$(grep -E '^embedding\.provider=' "$PROFILE_PROPS" | cut -d= -f2 | tr -d '[:space:]')
  LLM_PROVIDER=$(grep -E '^llm\.provider='       "$PROFILE_PROPS" | cut -d= -f2 | tr -d '[:space:]')

  [[ "$EMBED_PROVIDER" == "ollama" ]] && {
    MODEL=$(grep -E '^ollama\.embed\.model=' "$PROFILE_PROPS" | cut -d= -f2 | tr -d '[:space:]')
    [[ -n "$MODEL" ]] && REQUIRED_MODELS+=("$MODEL")
  }
  [[ "$LLM_PROVIDER" == "ollama" ]] && {
    MODEL=$(grep -E '^ollama\.chat\.model=' "$PROFILE_PROPS" | cut -d= -f2 | tr -d '[:space:]')
    [[ -n "$MODEL" ]] && REQUIRED_MODELS+=("$MODEL")
  }
fi

if [[ ${#REQUIRED_MODELS[@]} -eq 0 ]]; then
  ok "Profilo '$ACTIVE_PROFILE' non usa modelli Ollama locali — skip"
else
  OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

  for MODEL in "${REQUIRED_MODELS[@]}"; do
    info "Verifico modello: $MODEL"
    PRESENT=$(curl -s --max-time 5 "$OLLAMA_URL/api/tags" 2>/dev/null \
      | python3 -c "import sys,json; models=[m['name'] for m in json.load(sys.stdin).get('models',[])]; print('yes' if any('$MODEL' in m for m in models) else 'no')" 2>/dev/null || echo "no")

    if [[ "$PRESENT" == "yes" ]]; then
      ok "Modello '$MODEL' già disponibile"
    else
      info "Modello '$MODEL' non trovato — pull in corso (potrebbe richiedere alcuni minuti)..."
      docker exec ollama ollama pull "$MODEL" 2>&1 | tail -5 | sed 's/^/  /'
      # Verifica post-pull
      PRESENT2=$(curl -s --max-time 5 "$OLLAMA_URL/api/tags" 2>/dev/null \
        | python3 -c "import sys,json; models=[m['name'] for m in json.load(sys.stdin).get('models',[])]; print('yes' if any('$MODEL' in m for m in models) else 'no')" 2>/dev/null || echo "no")
      [[ "$PRESENT2" == "yes" ]] && ok "Modello '$MODEL' scaricato con successo" \
        || { fail "Pull fallito per '$MODEL' — verifica la connessione"; exit 1; }
    fi
  done
fi

# ── 2. Spring Boot ────────────────────────────────────────────────────────────
echo ""
step "2. AVVIO SPRING BOOT"

pkill -9 -f "spring-boot:run" 2>/dev/null || true
for i in {1..15}; do
  ss -tlnp 2>/dev/null | grep -q ':8080' || break
  sleep 1
done

cd "$SCRIPT_DIR/my-app"
mvn spring-boot:run > /tmp/spring-docling-test.log 2>&1 &
APP_PID=$!
info "Spring Boot avviato (PID: $APP_PID) — log: /tmp/spring-docling-test.log"

wait_for_http "Spring Boot" "$BASE_URL/" 60

# ── 3. Health check ───────────────────────────────────────────────────────────
echo ""
step "3. HEALTH CHECK"
cd "$SCRIPT_DIR"

DHEALTH=$(curl -s --max-time 5 "$DOCLING_URL/health" 2>/dev/null || echo "{}")
echo "$DHEALTH" | python3 -c "import sys,json; assert json.load(sys.stdin).get('status')=='UP'" 2>/dev/null \
  && ok "Docling service → UP" || fail "Docling service non risponde: $DHEALTH"

SB_HEALTH=$(curl -s --max-time 5 "$BASE_URL/api/docling/health" 2>/dev/null || echo "{}")
echo "$SB_HEALTH" | python3 -c "import sys,json; assert json.load(sys.stdin).get('status')=='UP'" 2>/dev/null \
  && ok "Spring Boot /api/docling/health → UP" || fail "Spring Boot non risponde: $SB_HEALTH"

ES_STATUS=$(curl -s --max-time 5 "$ES_URL/_cluster/health" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
[[ "$ES_STATUS" == "green" || "$ES_STATUS" == "yellow" ]] \
  && ok "Elasticsearch ($ES_STATUS)" || fail "Elasticsearch — status=$ES_STATUS"

# ── Fine ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}━━ STACK AVVIATA ━━${NC}"
echo ""
echo "  Spring Boot     : $BASE_URL"
echo "  Docling service : $DOCLING_URL/docs"
echo "  Elasticsearch   : $ES_URL"
echo "  Kibana          : http://localhost:5601"
echo "  Log Spring Boot : /tmp/spring-docling-test.log"
echo ""
echo "  Per indicizzare un documento:  ./ingest-docling.sh [file.pdf]"
echo ""
