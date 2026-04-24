#!/bin/bash
# =============================================================================
# setup-semantic-elastic.sh
#
# Configura Elasticsearch per la ricerca semantica con dense vector.
# Gli embedding vengono generati da Ollama (nomic-embed-text) - 100% free,
# nessuna licenza trial richiesta.
#
# Stack:
#   - Elasticsearch OSS/free su localhost:9200
#   - Ollama su localhost:11434 (container Docker già in esecuzione)
#   - Modello embedding: nomic-embed-text (768 dims, Apache-2.0)
#
# Uso:
#   chmod +x setup-semantic-elastic.sh
#   ./setup-semantic-elastic.sh
# =============================================================================

set -e

ES_URL="${ES_URL:-http://localhost:9200}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
EMBED_MODEL="${EMBED_MODEL:-nomic-embed-text}"
EMBED_DIMS=768
INDEX_NAME="semantic_docs"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------------------------------------------------------------------------
# 1. Attende che Elasticsearch sia raggiungibile
# ---------------------------------------------------------------------------
info "Verifica connessione a Elasticsearch: $ES_URL"
for i in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/_cluster/health" 2>/dev/null || echo "000")
  if [[ "$STATUS" == "200" ]]; then
    info "Elasticsearch raggiungibile."
    break
  fi
  warn "Attesa ($i/30) – Elasticsearch non ancora disponibile (HTTP $STATUS)..."
  sleep 3
done
[[ "$STATUS" == "200" ]] || error "Elasticsearch non raggiungibile dopo 90 secondi."

# ---------------------------------------------------------------------------
# 2. Attende che Ollama sia raggiungibile
# ---------------------------------------------------------------------------
info "Verifica connessione a Ollama: $OLLAMA_URL"
for i in $(seq 1 20); do
  STATUS_OL=$(curl -s -o /dev/null -w "%{http_code}" "$OLLAMA_URL/api/tags" 2>/dev/null || echo "000")
  if [[ "$STATUS_OL" == "200" ]]; then
    info "Ollama raggiungibile."
    break
  fi
  warn "Attesa Ollama ($i/20) – HTTP $STATUS_OL..."
  sleep 3
done
[[ "$STATUS_OL" == "200" ]] || error "Ollama non raggiungibile dopo 60 secondi."

# ---------------------------------------------------------------------------
# 3. Pull del modello di embedding (skip se già presente)
# ---------------------------------------------------------------------------
info "Pull modello '$EMBED_MODEL' su Ollama (può richiedere qualche minuto al primo avvio)..."
PULL_RESP=$(curl -s -X POST "$OLLAMA_URL/api/pull" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$EMBED_MODEL\",\"stream\":false}")
echo "  → $(echo "$PULL_RESP" | grep -o '"status":"[^"]*"' | tail -1)"

# ---------------------------------------------------------------------------
# 4. Crea l'indice con mappatura dense_vector
#    Nessuna ingest pipeline: gli embedding vengono generati dall'app Java
#    tramite Ollama prima dell'indicizzazione.
# ---------------------------------------------------------------------------
info "Verifica esistenza indice '$INDEX_NAME'..."
EXISTS=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/$INDEX_NAME")

if [[ "$EXISTS" == "200" ]]; then
  warn "Indice '$INDEX_NAME' esiste già – skip creazione."
else
  info "Creazione indice '$INDEX_NAME' (dense_vector, $EMBED_DIMS dims, cosine)..."
  INDEX_RESP=$(curl -s -X PUT "$ES_URL/$INDEX_NAME" \
    -H "Content-Type: application/json" \
    -w "\nHTTP_STATUS:%{http_code}" \
    -d "{
      \"settings\": {
        \"number_of_shards\":   1,
        \"number_of_replicas\": 0
      },
      \"mappings\": {
        \"properties\": {
          \"documentId\":        { \"type\": \"keyword\" },
          \"fileName\":          { \"type\": \"keyword\" },
          \"chunkIndex\":        { \"type\": \"integer\" },
          \"chapterTitle\":      { \"type\": \"text\"    },
          \"chapterIndex\":      { \"type\": \"integer\" },
          \"content\":           { \"type\": \"text\"    },
          \"content_embedding\": {
            \"type\":       \"dense_vector\",
            \"dims\":       $EMBED_DIMS,
            \"index\":      true,
            \"similarity\": \"cosine\"
          }
        }
      }
    }")

  HTTP_CODE=$(echo "$INDEX_RESP" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
  echo "  → HTTP $HTTP_CODE"
  [[ "$HTTP_CODE" == "200" ]] || error "Errore creazione indice (HTTP $HTTP_CODE)."
fi

# ---------------------------------------------------------------------------
# Fine
# ---------------------------------------------------------------------------
echo ""
info "Setup completato:"
info "  • Embedding: Ollama – $EMBED_MODEL ($EMBED_DIMS dims, cosine)"
info "  • Indice semantico: $INDEX_NAME"
info "  • Nessuna licenza trial richiesta – stack 100% free"
echo ""
info "Endpoint disponibili dopo l'avvio dell'applicazione:"
info "  POST /api/semantic/index   – carica un PDF e lo indicizza con Ollama embeddings"
info "  POST /api/semantic/search  – ricerca kNN per similitudine semantica"
echo ""
