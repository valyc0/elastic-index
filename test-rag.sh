#!/usr/bin/env bash
# test-rag.sh — verifica end-to-end della pipeline RAG (Docling + Hybrid Search + LLM)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DOCLING_URL="${DOCLING_URL:-http://localhost:8001}"
ES_URL="${ES_URL:-http://localhost:9200}"
QUERY="${QUERY:-Chi sono i protagonisti del romanzo?}"
TOP_K="${TOP_K:-3}"
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'

ok()   { echo -e "${GREEN}✔ $*${NC}"; }
fail() { echo -e "${RED}✘ $*${NC}"; }
info() { echo -e "${YELLOW}» $*${NC}"; }

check_http() {
  local label="$1" url="$2" expected="$3"
  local resp
  resp=$(curl -s --max-time 5 "$url" 2>/dev/null || true)
  if echo "$resp" | grep -q "$expected"; then
    ok "$label"
  else
    fail "$label — risposta: $resp"
    return 1
  fi
}

# ── 1. Health check ────────────────────────────────────────────────────────────
echo ""
info "=== 1. HEALTH CHECK ==="
check_http "Docling service"        "$DOCLING_URL/health"             '"UP"'
check_http "Spring Boot / RAG"      "$BASE_URL/api/rag/health"        '"UP"'
check_http "Spring Boot / Docling"  "$BASE_URL/api/docling/health"    '"UP"'
ES_STATUS=$(curl -s --max-time 5 "$ES_URL/_cluster/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
if [[ "$ES_STATUS" == "green" || "$ES_STATUS" == "yellow" ]]; then
  ok "Elasticsearch ($ES_STATUS)"
else
  fail "Elasticsearch — status=$ES_STATUS"
fi

# ── 2. Conta chunk indicizzati ─────────────────────────────────────────────────
echo ""
info "=== 2. DOCUMENTI IN ELASTICSEARCH ==="
COUNT=$(curl -s --max-time 5 "$ES_URL/semantic_docs/_count" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
if [[ "$COUNT" -gt 0 ]]; then
  ok "semantic_docs: $COUNT chunk indicizzati"
else
  fail "Nessun chunk trovato in semantic_docs. Indicizzare prima un documento:"
  echo "   curl -X POST $BASE_URL/api/docling/index -F 'file=@documento.pdf'"
  exit 1
fi

# ── 3. Ricerca ibrida (BM25 + kNN) senza LLM ──────────────────────────────────
echo ""
info "=== 3. RICERCA IBRIDA (senza LLM) ==="
info "query: \"$QUERY\""
SEARCH_RESP=$(curl -s --max-time 30 -X POST "$BASE_URL/api/rag/search" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\", \"topK\": $TOP_K}" 2>/dev/null || true)

if echo "$SEARCH_RESP" | python3 -c "import sys,json; r=json.load(sys.stdin); assert len(r)>0" 2>/dev/null; then
  ok "Hybrid search OK — $(echo "$SEARCH_RESP" | python3 -c "import sys,json; r=json.load(sys.stdin); print(f'{len(r)} risultati')")"
  echo "$SEARCH_RESP" | python3 -c "
import sys, json
for i, r in enumerate(json.load(sys.stdin), 1):
    fname = r.get('fileName','?')
    title = (r.get('chapterTitle') or '(no title)')[:55]
    score = r.get('relevanceScore', 0)
    print(f'  [{i}] score={score:.6f} | {fname} — {title}')
"
else
  fail "Ricerca ibrida fallita — risposta: ${SEARCH_RESP:0:200}"
fi

# ── 4. Pipeline RAG completa ───────────────────────────────────────────────────
echo ""
info "=== 4. RAG COMPLETO (retrieval + LLM) ==="
info "query: \"$QUERY\""
RAG_RESP=$(curl -s --max-time 120 -X POST "$BASE_URL/api/rag/ask" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\", \"topK\": $TOP_K}" 2>/dev/null || true)

if echo "$RAG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('answer')" 2>/dev/null; then
  ok "RAG pipeline OK"
  echo "$RAG_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
answer = d.get('answer','')
llm    = d.get('llmModel','?')
emb    = d.get('embeddingModel','?')
ms     = d.get('processingTimeMs','?')
sources = d.get('sources', [])

print()
print('  RISPOSTA:')
# stampa max 400 caratteri
print('  ' + answer[:400] + ('...' if len(answer) > 400 else ''))
print()
print(f'  LLM: {llm} | Embedding: {emb} | Tempo: {ms}ms | Fonti: {len(sources)}')
print()
if sources:
    print('  FONTI:')
    for s in sources[:$TOP_K]:
        fname  = s.get('fileName','?')
        title  = s.get('chapterTitle','?')[:50]
        score  = s.get('relevanceScore', 0)
        print(f'    • {fname} — {title} (score={score:.4f})')
"
else
  fail "RAG fallito — risposta: ${RAG_RESP:0:300}"
fi

echo ""
ok "=== TEST COMPLETATI ==="
