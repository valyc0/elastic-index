#!/bin/bash
# Mostra la lista dei documenti indicizzati e permette di eliminarne uno

APP_URL="${APP_URL:-http://localhost:8080}"

RESP=$(curl -s "${APP_URL}/api/rag/documents")
if [[ -z "$RESP" || "$RESP" == "null" ]]; then
    echo "Nessun documento trovato o SpringBoot non raggiungibile"
    exit 1
fi

# Costruisce l'array dei nomi documento e li mostra
mapfile -t DOCS < <(echo "$RESP" | python3 -c "
import sys, json
docs = json.load(sys.stdin)
for d in docs:
    print(d)
")

COUNT=${#DOCS[@]}

if [[ $COUNT -eq 0 ]]; then
    echo "Nessun documento indicizzato."
    exit 0
fi

echo "Documenti indicizzati:"
for i in "${!DOCS[@]}"; do
    echo "  $((i+1)). ${DOCS[$i]}"
done
echo ""
echo "Totale: $COUNT documento/i"
echo ""
echo "Premi il numero del documento da eliminare (Invio per uscire):"
read -r SCELTA

# Uscita se vuoto
[[ -z "$SCELTA" ]] && echo "Annullato." && exit 0

# Valida che sia un numero nell'intervallo
if ! [[ "$SCELTA" =~ ^[0-9]+$ ]] || (( SCELTA < 1 || SCELTA > COUNT )); then
    echo "Scelta non valida."
    exit 1
fi

FILE="${DOCS[$((SCELTA-1))]}"
echo ""
echo "Elimino: \"$FILE\""
read -r -p "Confermi? [s/N] " CONFIRM
if [[ "$CONFIRM" != "s" && "$CONFIRM" != "S" ]]; then
    echo "Annullato."
    exit 0
fi

ENCODED=$(python3 -c "import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1]))" "$FILE")
DEL_RESP=$(curl -s -X DELETE "${APP_URL}/api/semantic/document?fileName=${ENCODED}")

echo "$DEL_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
if 'error' in d:
    print(f'✗ Errore: {d[\"error\"]}')
else:
    print(f'✔ {d[\"message\"]} ({d[\"deleted\"]} chunk rimossi)')
"
