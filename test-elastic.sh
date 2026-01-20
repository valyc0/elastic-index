#!/bin/bash

echo "=== Test 1: Indicizzazione del JSON ==="
RESPONSE=$(curl -s -X POST "http://localhost:8080/api/index/from-json?jsonFile=ventimila-leghe.pdf_20260120_192148.json")
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
