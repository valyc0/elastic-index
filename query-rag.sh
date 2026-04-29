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

echo "$RESPONSE" | python3 -c "
import sys, json

d = json.load(sys.stdin)
needs_clarification = d.get('needsClarification', False)
raw_answer          = d.get('answer')
answer              = raw_answer if raw_answer and str(raw_answer).strip().lower() != 'null' else None
model               = d.get('llmModel', '?')
embed               = d.get('embeddingModel', '?')
ms                  = d.get('processingTimeMs', '?')
sources             = d.get('sources', [])
session_id          = d.get('sessionId', '')

if needs_clarification or not answer:
    print('Non ho trovato informazioni pertinenti nel documento.')
else:
    print(answer)

print()
print(f'LLM: {model} | Embedding: {embed} | Tempo: {ms}ms')
if session_id:
    print(f'Session: {session_id}')

if sources:
    print()
    print('FONTI:')
    for s in sources:
        print(f\"  • {s.get('fileName','?')} — {s.get('chapterTitle','?')} (score={s.get('relevanceScore',0):.4f})\")
" 2>/dev/null
