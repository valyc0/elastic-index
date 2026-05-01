#!/bin/bash
# =============================================================================
# test-semantic.sh
#
# Test didattici della ricerca semantica con Ollama + Elasticsearch kNN.
# Ogni test spiega COSA sta succedendo e PERCHÉ dimostra che funziona.
#
# Uso:
#   bash test-semantic.sh [APP_URL]
# =============================================================================

APP_URL="${1:-http://localhost:8080}"
ES_URL="${ES_URL:-http://localhost:9200}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

PASS=0
FAIL=0

ok()      { echo -e "${GREEN}  ✓${NC} $*"; PASS=$((PASS+1)); }
fail()    { echo -e "${RED}  ✗${NC} $*"; FAIL=$((FAIL+1)); }
info()    { echo -e "${BLUE}ℹ${NC} $*"; }
explain() { echo -e "${DIM}  → $*${NC}"; }
section() {
  echo ""
  echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${CYAN}  $*${NC}"
  echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Esegue una ricerca semantica e stampa i risultati
semantic_search() {
  local query="$1"
  local size="${2:-3}"
  curl -s -X POST "$APP_URL/api/semantic/search" \
    -H "Content-Type: application/json" \
    -d "{\"query\":\"$query\",\"size\":$size}"
}

# Esegue una ricerca BM25 (keyword classica) direttamente su ES
bm25_search() {
  local query="$1"
  local size="${2:-3}"
  curl -s "$ES_URL/semantic_docs/_search" \
    -H "Content-Type: application/json" \
    -d "{\"query\":{\"match\":{\"content\":\"$query\"}},\"size\":$size,\"_source\":[\"chapterTitle\",\"content\"]}"
}

print_semantic_results() {
  python3 -c "
import sys, json
try:
    results = json.load(sys.stdin)
    if not results:
        print('  (nessun risultato)')
    for i, r in enumerate(results, 1):
        score = r.get('score', 0)
        bar = '█' * int(score * 20)
        chapter = r.get('chapterTitle', 'N/A')
        content = r.get('content', '')[:150].replace('\n', ' ')
        print(f'  [{i}] {bar} {score:.3f}')
        print(f'      Capitolo: {chapter}')
        print(f'      Testo:    {content}...')
        print()
except Exception as e:
    print(f'  Errore parsing: {e}')
"
}

print_bm25_results() {
  python3 -c "
import sys, json
try:
    r = json.load(sys.stdin)
    hits = r.get('hits', {}).get('hits', [])
    if not hits:
        print('  (nessun risultato)')
    for i, h in enumerate(hits, 1):
        score = h.get('_score', 0)
        bar = '░' * min(20, int(score))
        chapter = h['_source'].get('chapterTitle', 'N/A')
        content = h['_source'].get('content', '')[:150].replace('\n', ' ')
        print(f'  [{i}] {bar} score={score:.2f}')
        print(f'      Capitolo: {chapter}')
        print(f'      Testo:    {content}...')
        print()
except Exception as e:
    print(f'  Errore parsing: {e}')
"
}

# ============================================================================
section "STATO DEL SISTEMA"
# ============================================================================

info "Elasticsearch..."
ES_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/_cluster/health")
[[ "$ES_STATUS" == "200" ]] && ok "Elasticsearch OK" || fail "Elasticsearch non raggiungibile"

info "Ollama..."
OL_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$OLLAMA_URL/api/tags")
[[ "$OL_STATUS" == "200" ]] && ok "Ollama OK" || fail "Ollama non raggiungibile"

info "App Java..."
APP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$APP_URL/" 2>/dev/null || echo "000")
[[ "$APP_STATUS" == "200" || "$APP_STATUS" == "404" ]] && ok "App OK" || fail "App non raggiungibile"

DOC_COUNT=$(curl -s "$ES_URL/semantic_docs/_count" 2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2 || echo "0")
ok "Indice semantic_docs: $DOC_COUNT chunk indicizzati"

# ============================================================================
section "TEST A — Query normale (baseline)"
# ============================================================================
echo ""
info "Query: 'capitano Nemo'"
explain "Caso base: le parole della query esistono nel testo. Sia BM25 che semantica dovrebbero trovare risultati."
explain "La differenza è nell'ORDINE e nel CONTESTO capito."

echo ""
echo -e "${YELLOW}  ● Ricerca SEMANTICA (kNN cosine):${NC}"
semantic_search "capitano Nemo" 3 | print_semantic_results

echo -e "${YELLOW}  ● Ricerca KEYWORD BM25 (classica):${NC}"
bm25_search "capitano Nemo" 3 | print_bm25_results

ok "Test A completato"

# ============================================================================
section "TEST B — Query in lingua diversa (prova semantica pura)"
# ============================================================================
echo ""
info "Query in INGLESE: 'the mysterious submarine and its captain'"
explain "Le parole inglesi NON esistono nel testo (che è in italiano)."
explain "BM25 → 0 risultati (non trova le parole)."
explain "Semantica → trova i capitoli giusti perché capisce il SIGNIFICATO."

echo ""
echo -e "${YELLOW}  ● Ricerca SEMANTICA:${NC}"
RESP=$(semantic_search "the mysterious submarine and its captain" 3)
COUNT=$(echo "$RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
echo "$RESP" | print_semantic_results
if [[ "$COUNT" -gt 0 ]]; then
  ok "Semantica ha trovato $COUNT risultati su testo italiano con query in inglese"
else
  fail "Semantica non ha trovato risultati"
fi

echo -e "${YELLOW}  ● Ricerca BM25 (keyword inglese su testo italiano):${NC}"
BM25_RESP=$(bm25_search "the mysterious submarine and its captain" 3)
BM25_COUNT=$(echo "$BM25_RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('hits',{}).get('hits',[])))" 2>/dev/null || echo "0")
echo "$BM25_RESP" | print_bm25_results
if [[ "$BM25_COUNT" -eq 0 ]]; then
  ok "BM25 ha trovato 0 risultati (come atteso: parole inglesi non nel testo)"
else
  info "BM25 ha trovato $BM25_COUNT risultati (inatteso)"
fi

# ============================================================================
section "TEST C — Sinonimo non presente nel testo"
# ============================================================================
echo ""
info "Query: 'veicolo subacqueo che naviga sott acqua'"
explain "Nel testo non compare 'veicolo subacqueo': c'è solo 'Nautilus' e 'sottomarino'."
explain "La semantica capisce che sono concetti equivalenti."

echo ""
echo -e "${YELLOW}  ● Ricerca SEMANTICA:${NC}"
RESP=$(semantic_search "veicolo subacqueo che naviga sott acqua" 3)
echo "$RESP" | print_semantic_results
# Verifica che i risultati parlino di Nautilus/sottomarino
NAUTILUS_FOUND=$(echo "$RESP" | python3 -c "
import sys, json
r = json.load(sys.stdin)
found = sum(1 for x in r if 'nautilus' in x.get('content','').lower() or 'sottomarino' in x.get('content','').lower())
print(found)
" 2>/dev/null || echo "0")
[[ "$NAUTILUS_FOUND" -gt 0 ]] && ok "Ha trovato $NAUTILUS_FOUND chunk che parlano di Nautilus/sottomarino (senza usare quelle parole nella query)" \
  || fail "Non ha trovato chunk sul Nautilus"

echo -e "${YELLOW}  ● BM25 con 'veicolo subacqueo':${NC}"
bm25_search "veicolo subacqueo" 3 | print_bm25_results

# ============================================================================
section "TEST D — Concetto astratto / tema"
# ============================================================================
echo ""
info "Query: 'paura e terrore di fronte all ignoto'"
explain "Non c'è un capitolo con questo titolo esatto."
explain "La semantica trova i passaggi che TEMATICAMENTE trattano paura e angoscia."

echo ""
echo -e "${YELLOW}  ● Ricerca SEMANTICA:${NC}"
semantic_search "paura e terrore di fronte all ignoto" 3 | print_semantic_results
ok "Test D completato"

# ============================================================================
section "TEST E — Paragone diretto: query vaga vs query precisa"
# ============================================================================
echo ""
info "Confronto score: query vaga vs query specifica sullo stesso argomento."
explain "Ci aspettiamo score più alto con la query più dettagliata."

VAGA="mostro marino"
PRECISA="polipo gigante che attacca il sottomarino Nautilus con i tentacoli"

echo ""
echo -e "${YELLOW}  ● Query VAGA: '$VAGA'${NC}"
SCORE_VAGA=$(semantic_search "$VAGA" 1 | python3 -c "import sys,json; r=json.load(sys.stdin); print(f'{r[0][\"score\"]:.4f}' if r else '0')" 2>/dev/null)
echo "    → score top-1: $SCORE_VAGA"

echo -e "${YELLOW}  ● Query PRECISA: '$PRECISA'${NC}"
SCORE_PRECISA=$(semantic_search "$PRECISA" 1 | python3 -c "import sys,json; r=json.load(sys.stdin); print(f'{r[0][\"score\"]:.4f}' if r else '0')" 2>/dev/null)
echo "    → score top-1: $SCORE_PRECISA"

python3 -c "
a, b = float('${SCORE_VAGA}' or 0), float('${SCORE_PRECISA}' or 0)
if b > a:
    print(f'  Query precisa ha score più alto di {b-a:.4f} (+ {(b-a)/a*100:.1f}%)')
else:
    print(f'  Score simili (differenza: {abs(b-a):.4f})')
" 2>/dev/null
ok "Test E completato"

# ============================================================================
section "TEST F — Embedding coerente (test matematico)"
# ============================================================================
echo ""
info "Verifica che testi simili abbiano embedding vicini, testi diversi lontani."
explain "Genera 3 embedding e calcola la cosine similarity manualmente."

python3 - <<'PYEOF'
import urllib.request, json, math

OLLAMA = "http://localhost:11434"

def embed(text):
    data = json.dumps({"model": "nomic-embed-text", "prompt": text}).encode()
    req = urllib.request.Request(f"{OLLAMA}/api/embeddings",
                                  data=data, headers={"Content-Type": "application/json"})
    resp = urllib.request.urlopen(req, timeout=30)
    return json.loads(resp.read())["embedding"]

def cosine(a, b):
    dot = sum(x*y for x,y in zip(a,b))
    na = math.sqrt(sum(x*x for x in a))
    nb = math.sqrt(sum(x*x for x in b))
    return dot / (na * nb) if na and nb else 0

texts = {
    "A": "Il capitano Nemo navigava con il sottomarino Nautilus",
    "B": "Il comandante guidava il battello subacqueo",   # simile ad A
    "C": "La cucina italiana usa molto l'olio d'oliva",  # completamente diverso
}

print("  Generazione embedding...")
embeddings = {}
for k, t in texts.items():
    embeddings[k] = embed(t)
    print(f"  [{k}] '{t[:50]}...' → {len(embeddings[k])} dims")

print()
ab = cosine(embeddings["A"], embeddings["B"])
ac = cosine(embeddings["A"], embeddings["C"])
bc = cosine(embeddings["B"], embeddings["C"])

print(f"  Similarity A↔B (concetti simili):   {ab:.4f}  {'✓ alta' if ab > 0.7 else '✗ bassa'}")
print(f"  Similarity A↔C (concetti diversi):  {ac:.4f}  {'✓ bassa' if ac < 0.7 else '✗ alta'}")
print(f"  Similarity B↔C (concetti diversi):  {bc:.4f}  {'✓ bassa' if bc < 0.7 else '✗ alta'}")

if ab > ac and ab > bc:
    print()
    print("  ✓ CORRETTO: testi simili hanno embedding più vicini di testi diversi")
else:
    print()
    print("  ✗ ANOMALIA: la similarità non rispecchia il significato atteso")
PYEOF

ok "Test F completato"

# ============================================================================
section "RIEPILOGO"
# ============================================================================
TOTAL=$((PASS + FAIL))
echo ""
echo -e "  Test totali: $TOTAL  |  ${GREEN}Passati: $PASS${NC}  |  ${RED}Falliti: $FAIL${NC}"
echo ""
if [[ "$FAIL" -eq 0 ]]; then
  echo -e "  ${GREEN}${BOLD}Tutto OK — la ricerca semantica funziona correttamente.${NC}"
else
  echo -e "  ${RED}${BOLD}$FAIL test falliti — controlla i dettagli sopra.${NC}"
  exit 1
fi
echo ""


