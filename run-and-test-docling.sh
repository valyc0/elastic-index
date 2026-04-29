#!/usr/bin/env bash
# run-and-test-docling.sh — Avvia l'intera stack Docling e verifica la pipeline end-to-end
#
# Flusso:
#   1. Avvia Docker (ES + Ollama + Docling service)
#   2. Avvia Spring Boot
#   3. Esegue test: health → parse diretto Docling → index PDF → /api/docling/ask (RAG)
#
# Uso:
#   ./run-and-test-docling.sh [percorso/al/documento.pdf]
#
# Se non si passa un file, usa "ventimila-leghe.pdf" nella stessa cartella.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DOCLING_URL="${DOCLING_URL:-http://localhost:8001}"
ES_URL="${ES_URL:-http://localhost:9200}"
QUERY="${QUERY:-Di cosa parla il libro?}"
TOP_K="${TOP_K:-3}"
TEST_FILE="${1:-$SCRIPT_DIR/ventimila-leghe.pdf}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✔ $*${NC}"; }
fail() { echo -e "${RED}✘ $*${NC}"; }
info() { echo -e "${YELLOW}» $*${NC}"; }
step() { echo -e "${CYAN}━━ $* ━━${NC}"; }

# ── Utilità ──────────────────────────────────────────────────────────────────

wait_for_http() {
  local label="$1" url="$2" timeout="${3:-60}"
  local elapsed=0
  echo -n "  Attendo $label"
  while ! curl -s --max-time 3 "$url" > /dev/null 2>&1; do
    sleep 2; elapsed=$((elapsed + 2))
    echo -n "."
    if [[ $elapsed -ge $timeout ]]; then
      echo ""
      fail "$label non risponde dopo ${timeout}s"
      return 1
    fi
  done
  echo ""
  ok "$label disponibile"
}

check_json_field() {
  local label="$1" resp="$2" field="$3"
  if echo "$resp" | python3 -c "import sys,json; v=json.load(sys.stdin).get('$field'); assert v not in (None,'',0)" 2>/dev/null; then
    ok "$label"
    return 0
  else
    fail "$label — risposta: ${resp:0:300}"
    return 1
  fi
}

# ── 0. Prerequisiti ──────────────────────────────────────────────────────────
echo ""
step "0. PREREQUISITI"

if [[ ! -f "$TEST_FILE" ]]; then
  fail "File di test non trovato: $TEST_FILE"
  echo "   Uso: $0 /percorso/al/documento.pdf"
  exit 1
fi
ok "File di test: $TEST_FILE"

# ── 1. Docker (ES + Ollama + Docling) ────────────────────────────────────────
echo ""
step "1. AVVIO DOCKER (Elasticsearch, Ollama, Docling service)"

cd "$SCRIPT_DIR"
docker compose up -d elasticsearch ollama docling-service
info "Container avviati (o già in esecuzione)"

info "Attendo Elasticsearch..."
wait_for_http "Elasticsearch" "$ES_URL/_cluster/health" 90

info "Attendo Docling service..."
wait_for_http "Docling service" "$DOCLING_URL/health" 120

# ── 2. Spring Boot ───────────────────────────────────────────────────────────
echo ""
step "2. AVVIO SPRING BOOT"

pkill -9 -f "spring-boot:run" 2>/dev/null || true
# Attendi che la porta 8080 si liberi (max 15s)
for i in {1..15}; do
  ss -tlnp 2>/dev/null | grep -q ':8080' || break
  sleep 1
done

cd "$SCRIPT_DIR/my-app"
mvn spring-boot:run > /tmp/spring-docling-test.log 2>&1 &
APP_PID=$!
info "Spring Boot avviato (PID: $APP_PID) — log: /tmp/spring-docling-test.log"

wait_for_http "Spring Boot" "$BASE_URL/" 60

# ── 3. Health check ──────────────────────────────────────────────────────────
echo ""
cd "$SCRIPT_DIR"
step "3. HEALTH CHECK"

# Docling service diretto
DHEALTH=$(curl -s --max-time 5 "$DOCLING_URL/health" 2>/dev/null || echo "{}")
if echo "$DHEALTH" | python3 -c "import sys,json; assert json.load(sys.stdin).get('status')=='UP'" 2>/dev/null; then
  ok "Docling service /health → UP"
else
  fail "Docling service /health — risposta: $DHEALTH"
fi

# Docling health via Spring Boot proxy
SB_HEALTH=$(curl -s --max-time 5 "$BASE_URL/api/docling/health" 2>/dev/null || echo "{}")
if echo "$SB_HEALTH" | python3 -c "import sys,json; assert json.load(sys.stdin).get('status')=='UP'" 2>/dev/null; then
  ok "Spring Boot /api/docling/health → UP"
else
  fail "Spring Boot /api/docling/health — risposta: $SB_HEALTH"
fi

# Elasticsearch
ES_STATUS=$(curl -s --max-time 5 "$ES_URL/_cluster/health" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
if [[ "$ES_STATUS" == "green" || "$ES_STATUS" == "yellow" ]]; then
  ok "Elasticsearch ($ES_STATUS)"
else
  fail "Elasticsearch — status=$ES_STATUS"
fi

# ── 4. Verifica API Docling (OpenAPI schema) ─────────────────────────────────
echo ""
step "4. VERIFICA API DOCLING"

OPENAPI=$(curl -s --max-time 5 "$DOCLING_URL/openapi.json" 2>/dev/null || echo "{}")
PARSE_PATH=$(echo "$OPENAPI" | python3 -c "import sys,json; print('/parse' in json.load(sys.stdin).get('paths',{}))" 2>/dev/null || echo "False")
if [[ "$PARSE_PATH" == "True" ]]; then
  DOCLING_TITLE=$(echo "$OPENAPI" | python3 -c "import sys,json; print(json.load(sys.stdin)['info']['title'])" 2>/dev/null || echo "?")
  DOCLING_VER=$(echo "$OPENAPI" | python3 -c "import sys,json; print(json.load(sys.stdin)['info']['version'])" 2>/dev/null || echo "?")
  ok "API Docling disponibile — $DOCLING_TITLE v$DOCLING_VER (endpoint /parse presente)"
else
  fail "OpenAPI schema non disponibile — risposta: ${OPENAPI:0:200}"
fi

# ── 5. Indicizzazione via Spring Boot ────────────────────────────────────────
echo ""
step "5. INDICIZZAZIONE (POST $BASE_URL/api/docling/index)"
info "File: $(basename "$TEST_FILE")"

INDEX_RESP=$(curl -s --max-time 600 -X POST "$BASE_URL/api/docling/index" \
  -F "file=@$TEST_FILE" 2>/dev/null || echo "{}")

DOC_ID=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('documentId',''))" 2>/dev/null || echo "")
CHUNKS=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunks',0))" 2>/dev/null || echo "0")
IDX_SECTIONS=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sections',0))" 2>/dev/null || echo "0")

if [[ -n "$DOC_ID" && "$CHUNKS" -gt 0 ]]; then
  ok "Indicizzazione OK — documentId=$DOC_ID, sezioni=$IDX_SECTIONS, chunks=$CHUNKS"
else
  fail "Indicizzazione fallita — risposta: ${INDEX_RESP:0:400}"
  echo "Log Spring Boot (ultime 30 righe):"
  tail -30 /tmp/spring-docling-test.log
  exit 1
fi

# ── 6. Verifica chunk in Elasticsearch ──────────────────────────────────────
echo ""
step "6. CHUNK IN ELASTICSEARCH"

sleep 2  # lascia tempo al refresh ES
ES_COUNT=$(curl -s --max-time 5 "$ES_URL/semantic_docs/_count" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
if [[ "$ES_COUNT" -gt 0 ]]; then
  ok "semantic_docs: $ES_COUNT chunk totali in Elasticsearch"
else
  fail "Nessun chunk trovato nell'indice semantic_docs"
fi

# ── 7. RAG con /api/docling/ask ──────────────────────────────────────────────
echo ""
step "7. RAG (POST $BASE_URL/api/docling/ask)"
info "query: \"$QUERY\""

RAG_RESP=$(curl -s --max-time 120 -X POST "$BASE_URL/api/docling/ask" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\", \"topK\": $TOP_K}" 2>/dev/null || echo "{}")

if echo "$RAG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('answer')" 2>/dev/null; then
  ok "RAG pipeline OK"
  echo "$RAG_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
answer  = d.get('answer','')
llm     = d.get('llmModel','?')
emb     = d.get('embeddingModel','?')
ms      = d.get('processingTimeMs','?')
sources = d.get('sources', [])

print()
print('  RISPOSTA:')
print('  ' + answer[:500] + ('...' if len(answer) > 500 else ''))
print()
print(f'  LLM: {llm} | Embedding: {emb} | Tempo: {ms}ms | Fonti: {len(sources)}')
if sources:
    print()
    print('  FONTI:')
    for s in sources[:$TOP_K]:
        fname = s.get('fileName','?')
        title = (s.get('chapterTitle') or '(no title)')[:55]
        score = s.get('relevanceScore', 0)
        print(f'    • {fname} — {title} (score={score:.4f})')
"
else
  fail "RAG fallito — risposta: ${RAG_RESP:0:400}"
  echo "Log Spring Boot (ultime 30 righe):"
  tail -30 /tmp/spring-docling-test.log
fi

# ── Riepilogo ────────────────────────────────────────────────────────────────
echo ""
step "COMPLETATO"
ok "Pipeline Docling testata con: $(basename "$TEST_FILE")"
echo ""
echo "  Log applicazione : /tmp/spring-docling-test.log"
echo "  Kibana           : http://localhost:5601"
echo "  Docling service  : $DOCLING_URL/docs"
echo ""
