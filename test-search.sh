#!/bin/bash

# Script per testare la ricerca su Elasticsearch

QUERY="${1:-nautilus}"
LANG="${2:-it}"
SIZE="${3:-3}"

echo "Ricerca di: '$QUERY' (lingua: $LANG, dimensione: $SIZE)"
echo "Endpoint: http://localhost:8080/api/search/quick"
echo ""

curl -s "http://localhost:8080/api/search/quick?q=$QUERY&lang=$LANG&size=$SIZE" | jq '.[0:2]' 2>/dev/null || curl -s "http://localhost:8080/api/search/quick?q=$QUERY&lang=$LANG&size=$SIZE"

echo ""
