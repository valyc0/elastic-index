#!/bin/bash
# Mostra la lista dei documenti indicizzati in Elasticsearch

APP_URL="${APP_URL:-http://localhost:8080}"

RESP=$(curl -s "${APP_URL}/api/rag/documents")
if [[ -z "$RESP" || "$RESP" == "null" ]]; then
    echo "Nessun documento trovato o SpringBoot non raggiungibile"
    exit 1
fi

echo "Documenti indicizzati:"
echo "$RESP" | python3 -c "
import sys, json
docs = json.load(sys.stdin)
if not docs:
    print('  (nessuno)')
else:
    for i, d in enumerate(docs, 1):
        print(f'  {i}. {d}')
print(f'\nTotale: {len(docs)} documento/i')
"
