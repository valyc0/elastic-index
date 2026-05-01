#!/bin/bash

echo "=== Test 1: Indicizzazione del JSON ==="
# Cerca il primo JSON disponibile nella directory extracted-documents
JSON_FILE=$(ls my-app/extracted-documents/*.json 2>/dev/null | head -1 | xargs basename 2>/dev/null)
if [[ -z "$JSON_FILE" ]]; then
    echo "✗ Nessun file JSON trovato in my-app/extracted-documents/"
    echo "  Prima esegui: ./test-upload.sh <file.pdf>"
    exit 1
fi
echo "Utilizzo file JSON: $JSON_FILE"
RESPONSE=$(curl -s -X POST "http://localhost:8080/api/index/from-json?jsonFile=$JSON_FILE")
echo "$RESPONSE"
echo ""

if echo "$RESPONSE" | grep -q "indicizzato con ID"; then
    echo "✓ Indicizzazione completata"
    
    echo ""
    echo "=== Test 2: Verifica indici Elasticsearch ==="
    curl -s "http://localhost:9200/_cat/indices?v"
    echo ""
    
    echo "=== Test 3: Ricerca semplice ==="
    curl -s -X GET "http://localhost:8080/api/search/quick?q=mare&size=3" | python3 -m json.tool 2>/dev/null || cat
    echo ""
    
    echo "=== Test 4: Ricerca avanzata ==="
    curl -s -X POST "http://localhost:8080/api/search/advanced" \
      -H "Content-Type: application/json" \
      -d '{"query": "capitano", "size": 2}' | python3 -m json.tool 2>/dev/null || cat
    echo ""
else
    echo "✗ Errore durante l'indicizzazione"
fi
