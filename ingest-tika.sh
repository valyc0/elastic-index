#!/usr/bin/env bash
# ingest-tika.sh — Indicizza un documento via Apache Tika e testa la pipeline RAG
#
# Uso:
#   ./ingest-tika.sh [percorso/al/documento.pdf]
#
# Se non si passa un file, usa "esempi/ventimila-leghe.pdf".
# Presuppone che la stack sia già avviata (./start-all.sh).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
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

# Verifica che Spring Boot sia up (Tika è embedded, non ha un servizio separato)
curl -s --max-time 5 "$BASE_URL/" > /dev/null 2>&1 \
  && ok "SpringBoot raggiungibile" \
  || { fail "SpringBoot non risponde — avvia prima la stack con ./start-all.sh"; exit 1; }

# ── 1. Indicizzazione ─────────────────────────────────────────────────────────
echo ""
step "1. INDICIZZAZIONE (POST $BASE_URL/api/tika/index)"
info "File: $(basename "$TEST_FILE")"
info "Parsing in corso con Apache Tika (embedded, veloce)..."

_T0=$(date +%s)
INDEX_RESP=$(curl -s --max-time 600 -X POST "$BASE_URL/api/tika/index" \
  -F "file=@$TEST_FILE" 2>/dev/null || echo "{}")
_T1=$(date +%s)
_ELAPSED=$(( _T1 - _T0 ))
_MIN=$(( _ELAPSED / 60 ))
_SEC=$(( _ELAPSED % 60 ))

DOC_ID=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('documentId',''))" 2>/dev/null || echo "")
CHUNKS=$(echo "$INDEX_RESP"  | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunks',0))" 2>/dev/null || echo "0")
SECTIONS=$(echo "$INDEX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sections',0))" 2>/dev/null || echo "0")

if [[ -n "$DOC_ID" && "$CHUNKS" -gt 0 ]]; then
  ok "Indicizzazione OK — documentId=$DOC_ID, sezioni=$SECTIONS, chunks=$CHUNKS, tempo=${_MIN}m${_SEC}s"
else
  fail "Indicizzazione fallita — risposta: ${INDEX_RESP:0:400}"
  echo "  Log Spring Boot (ultime 30 righe):"
  tail -30 /tmp/spring-*.log 2>/dev/null || true
  exit 1
fi

# ── 2. Verifica chunk in Elasticsearch ───────────────────────────────────────
echo ""
step "2. CHUNK IN ELASTICSEARCH"

sleep 2
ES_COUNT=$(curl -s --max-time 5 "$ES_URL/semantic_docs/_count" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
[[ "$ES_COUNT" -gt 0 ]] \
  && ok "semantic_docs: $ES_COUNT chunk totali" \
  || fail "Nessun chunk trovato nell'indice semantic_docs"

# ── 3. RAG ────────────────────────────────────────────────────────────────────
echo ""
step "3. RAG (POST $BASE_URL/api/rag/ask)"
info "query: \"$QUERY\""

RAG_RESP=$(curl -s --max-time 120 -X POST "$BASE_URL/api/rag/ask" \
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
  tail -30 /tmp/spring-*.log 2>/dev/null || true
fi

# ── Fine ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}━━ INGESTION COMPLETATA ━━${NC}"
