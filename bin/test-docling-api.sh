#!/usr/bin/env bash
# test-docling-api.sh — Chiama direttamente il servizio Python Docling (porta 8001)
#
# Uso:
#   ./test-docling-api.sh <file.pdf>           # parsa e mostra struttura completa
#   ./test-docling-api.sh <file.pdf> --summary # mostra solo il riepilogo
#
# Presuppone che il container docling-service sia avviato.

set -euo pipefail

DOCLING_URL="${DOCLING_URL:-http://localhost:8001}"
SUMMARY_ONLY=false
[[ "${2:-}" == "--summary" ]] && SUMMARY_ONLY=true

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✔ $*${NC}"; }
fail() { echo -e "${RED}  ✘ $*${NC}"; exit 1; }
info() { echo -e "${YELLOW}  » $*${NC}"; }
step() { echo -e "\n${CYAN}━━ $* ━━${NC}"; }

# ── 0. Argomento file ─────────────────────────────────────────────────────────
if [[ -z "${1:-}" ]]; then
  echo "Uso: $0 <file.pdf|docx|html|pptx> [--summary]"
  exit 1
fi
FILE="$1"
[[ -f "$FILE" ]] || fail "File non trovato: $FILE"

# ── 1. Health check ───────────────────────────────────────────────────────────
step "1. HEALTH CHECK  $DOCLING_URL/health"
HEALTH=$(curl -sf --max-time 5 "$DOCLING_URL/health" 2>/dev/null || echo "{}")
STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
[[ "$STATUS" == "UP" ]] && ok "Docling UP" || fail "Docling non risponde: $HEALTH"

# ── 2. Info OpenAPI ───────────────────────────────────────────────────────────
step "2. INFO SERVIZIO  $DOCLING_URL/openapi.json"
OPENAPI=$(curl -sf --max-time 5 "$DOCLING_URL/openapi.json" 2>/dev/null || echo "{}")
TITLE=$(echo "$OPENAPI" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['info']['title'])" 2>/dev/null || echo "?")
VER=$(echo "$OPENAPI"   | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['info']['version'])" 2>/dev/null || echo "?")
ok "$TITLE  v$VER"
ENDPOINTS=$(echo "$OPENAPI" | python3 -c "
import sys,json
paths = json.load(sys.stdin).get('paths',{})
for p,v in paths.items():
    for m in v: print(f'    {m.upper():<8} {p}')
" 2>/dev/null || echo "    (n/d)")
echo "$ENDPOINTS"

# ── 3. POST /parse ────────────────────────────────────────────────────────────
step "3. POST /parse  $(basename "$FILE")  ($(du -h "$FILE" | cut -f1))"
info "Parsing in corso — potrebbe richiedere minuti su CPU..."

T0=$(date +%s)
RESPONSE=$(curl -sf --max-time 600 \
  -X POST "$DOCLING_URL/parse" \
  -F "file=@$FILE" \
  2>/dev/null) || fail "POST /parse fallito (timeout o errore di rete)"
T1=$(date +%s)
ELAPSED=$(( T1 - T0 ))

ok "Risposta ricevuta in ${ELAPSED}s"

# ── 4. Riepilogo ──────────────────────────────────────────────────────────────
step "4. RIEPILOGO"
echo "$RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)

fname      = d.get('file_name', '?')
page_count = d.get('page_count', '?')
sections   = d.get('sections', [])
tables     = d.get('tables', [])
full_text  = d.get('full_text', '')
metadata   = d.get('metadata', {})

print(f'  file_name  : {fname}')
print(f'  page_count : {page_count}')
print(f'  sections   : {len(sections)}')
print(f'  tables     : {len(tables)}')
print(f'  full_text  : {len(full_text)} caratteri')
if metadata:
    print()
    print('  METADATA:')
    for k,v in metadata.items():
        if k != 'fileName':
            print(f'    {k}: {v}')
"

# ── 5. Sezioni ────────────────────────────────────────────────────────────────
step "5. SEZIONI (prime 20)"
echo "$RESPONSE" | python3 -c "
import sys, json
sections = json.load(sys.stdin).get('sections', [])
for i, s in enumerate(sections[:20]):
    lvl   = s.get('level', 0)
    title = s.get('title', '(no title)')
    text  = s.get('text', '')
    page  = s.get('page_number', '?')
    parent = s.get('parent_chapter_title') or ''
    indent = '  ' * max(0, lvl - 1)
    print(f'  [{i:02d}] {indent}H{lvl} pag.{page}  \"{title[:60]}\"')
    print(f'       {indent}testo: {len(text)} car  |  parent: \"{parent[:40]}\"')
if len(sections) > 20:
    print(f'  ... e altre {len(sections)-20} sezioni')
"

if $SUMMARY_ONLY; then
  echo ""
  exit 0
fi

# ── 6. Tabelle ────────────────────────────────────────────────────────────────
step "6. TABELLE"
echo "$RESPONSE" | python3 -c "
import sys, json
tables = json.load(sys.stdin).get('tables', [])
if not tables:
    print('  (nessuna tabella trovata)')
for i, t in enumerate(tables[:5]):
    caption = t.get('caption') or '(no caption)'
    page    = t.get('page_number', '?')
    preview = t.get('text_representation','')[:200].replace('\n',' | ')
    print(f'  [{i}] pag.{page}  caption: {caption[:60]}')
    print(f'       {preview}')
    print()
if len(tables) > 5:
    print(f'  ... e altre {len(tables)-5} tabelle')
"

# ── 7. Full text (preview) ────────────────────────────────────────────────────
step "7. FULL TEXT (primi 800 caratteri)"
echo "$RESPONSE" | python3 -c "
import sys, json
text = json.load(sys.stdin).get('full_text','')
print(text[:800])
if len(text) > 800: print('...')
"

# ── 8. JSON raw (opzionale) ───────────────────────────────────────────────────
OUTPUT_FILE="/tmp/docling-parse-$(basename "$FILE" .pdf).json"
echo "$RESPONSE" | python3 -m json.tool > "$OUTPUT_FILE"
echo ""
ok "JSON completo salvato in: $OUTPUT_FILE"
echo ""
