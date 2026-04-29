#!/bin/bash
# Esegue solo la query RAG (presuppone Spring Boot già avviato)
# Uso: ./query-rag.sh "domanda"   oppure   QUERY="domanda" ./query-rag.sh

QUERY="${1:-${QUERY:-Chi è il capitano Nemo e cosa guida?}}"
URL="http://localhost:8080/api/docling/ask"

echo "» query: \"$QUERY\""
echo ""

RESPONSE=$(curl -s --max-time 120 -X POST "$URL" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\"}")

if [ $? -ne 0 ] || [ -z "$RESPONSE" ]; then
  echo "✗ Errore: nessuna risposta da $URL"
  exit 1
fi

ANSWER=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('answer','(vuoto)'))" 2>/dev/null)
MODEL=$(echo "$RESPONSE"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('llmModel','?'))" 2>/dev/null)
EMBED=$(echo "$RESPONSE"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('embeddingModel','?'))" 2>/dev/null)
TIME=$(echo "$RESPONSE"   | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('processingTimeMs','?'))" 2>/dev/null)

echo "RISPOSTA:"
echo "$ANSWER"
echo ""
echo "LLM: $MODEL | Embedding: $EMBED | Tempo: ${TIME}ms"
echo ""
echo "FONTI:"
echo "$RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for s in d.get('sources', []):
    print(f\"  • {s.get('fileName','?')} — {s.get('chapterTitle','?')} (score={s.get('relevanceScore',0):.4f})\")
" 2>/dev/null
