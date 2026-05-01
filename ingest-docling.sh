#!/usr/bin/env bash
# ingest-docling.sh — Indicizza un documento via Docling e testa la pipeline RAG
#
# Uso:
#   ./ingest-docling.sh [percorso/al/documento.pdf]
#
# Se non si passa un file, usa "esempi/ventimila-leghe.pdf".
# Presuppone che la stack sia già avviata (./start-all.sh).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DOCLING_URL="${DOCLING_URL:-http://localhost:8001}"
ES_URL="${ES_URL:-http://localhost:9200}"
QUERY="${QUERY:-Chi è il capitano Nemo e cosa guida?}"
TOP_K="${TOP_K:-3}"
TEST_FILE="${1:-$SCRIPT_DIR/esempi/ventimila-leghe.pdf}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✔ $*${NC}"; }
fail() { echo -e "${RED}  ✘ $*${NC}"; }
info() { echo -e "${YELLOW}  » $*${NC}"; }
step() { echo -e "${CYAN}━━ $* ━━${NC}"; }

# ── 0. Prerequisiti ───────────────────────────────────────────────────────────
echo ""
step "0. PREREQUISITI"

if [[ ! -f "$TEST_FILE" ]]; then
  fail "File non trovato: $TEST_FILE"
  echo "     Uso: $0 /percorso/al/documento.pdf"
  exit 1
fi
ok "File: $TEST_FILE ($(du -h "$TEST_FILE" | cut -f1))"

# Verifica che la stack sia up
for svc in "Docling:$DOCLING_URL/health" "SpringBoot:$BASE_URL/"; do
  label="${svc%%:*}"; url="${svc#*:}"
  curl -s --max-time 5 "$url" > /dev/null 2>&1 \
    && ok "$label raggiungibile" \
    || { fail "$label non risponde — avvia prima la stack con ./start-all.sh"; exit 1; }
done

# ── 1. Verifica API Docling ───────────────────────────────────────────────────
echo ""
step "1. VERIFICA API DOCLING"

OPENAPI=$(curl -s --max-time 5 "$DOCLING_URL/openapi.json" 2>/dev/null || echo "{}")
PARSE_PATH=$(echo "$OPENAPI" | python3 -c "import sys,json; print('/parse' in json.load(sys.stdin).get('paths',{}))" 2>/dev/null || echo "False")
if [[ "$PARSE_PATH" == "True" ]]; then
  DOCLING_TITLE=$(echo "$OPENAPI" | python3 -c "import sys,json; print(json.load(sys.stdin)['info']['title'])" 2>/dev/null || echo "?")
  DOCLING_VER=$(echo "$OPENAPI"   | python3 -c "import sys,json; print(json.load(sys.stdin)['info']['version'])" 2>/dev/null || echo "?")
  ok "$DOCLING_TITLE v$DOCLING_VER — endpoint /parse disponibile"
else
  fail "OpenAPI schema non disponibile: ${OPENAPI:0:200}"
  exit 1
fi

# ── 2. Indicizzazione ─────────────────────────────────────────────────────────
echo ""
step "2. INDICIZZAZIONE (POST $BASE_URL/api/docling/index)"
info "File: $(basename "$TEST_FILE")"
info "Parsing in corso — può richiedere diversi minuti su CPU..."

INDEX_RESP=$(curl -s --max-time 600 -X POST "$BASE_URL/api/docling/index" \
  -F "file=@$TEST_FILE" 2>/dev/null || echo "{}")

DOC_ID=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('documentId',''))" 2>/dev/null || echo "")
CHUNKS=$(echo "$INDEX_RESP"  | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunks',0))" 2>/dev/null || echo "0")
SECTIONS=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sections',0))" 2>/dev/null || echo "0")

if [[ -n "$DOC_ID" && "$CHUNKS" -gt 0 ]]; then
  ok "Indicizzazione OK — documentId=$DOC_ID, sezioni=$SECTIONS, chunks=$CHUNKS"
else
  fail "Indicizzazione fallita — risposta: ${INDEX_RESP:0:400}"
  echo "  Log Spring Boot (ultime 30 righe):"
  tail -30 /tmp/spring-docling-test.log 2>/dev/null || true
  exit 1
fi

# ── 3. Verifica chunk in Elasticsearch ───────────────────────────────────────
echo ""
step "3. CHUNK IN ELASTICSEARCH"

sleep 2
ES_COUNT=$(curl -s --max-time 5 "$ES_URL/semantic_docs/_count" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
[[ "$ES_COUNT" -gt 0 ]] \
  && ok "semantic_docs: $ES_COUNT chunk totali" \
  || fail "Nessun chunk trovato nell'indice semantic_docs"

# ── 4. RAG ────────────────────────────────────────────────────────────────────
echo ""
step "4. RAG (POST $BASE_URL/api/docling/ask)"
info "query: \"$QUERY\""

RAG_RESP=$(curl -s --max-time 120 -X POST "$BASE_URL/api/docling/ask" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\", \"topK\": $TOP_K}" 2>/dev/null || echo "{}")

if echo "$RAG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('answer')" 2>/dev/null; then
  ok "RAG pipeline OK"
  echo "$RAG_RESP" | python3 -c "
import sys, json
d       = json.load(sys.stdin)
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
  echo "  Log Spring Boot (ultime 30 righe):"
  tail -30 /tmp/spring-docling-test.log 2>/dev/null || true
fi

# ── Fine ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}━━ INGESTION COMPLETATA ━━${NC}"
echo ""
echo "  Documento : $(basename "$TEST_FILE")"
echo "  Chunks    : $CHUNKS"
echo "  Query RAG : $QUERY"
echo ""
