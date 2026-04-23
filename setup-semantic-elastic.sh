#!/bin/bash
# =============================================================================
# setup-semantic-elastic.sh
#
# Configura Elasticsearch per la ricerca semantica con ELSER v2:
#   1. Avvia il deploy del modello .elser_model_2 (sparse embedding interno ES)
#   2. Attende che il modello sia fully_allocated
#   3. Crea l'ingest pipeline "elser-v2-sparse" che genera automaticamente
#      l'embedding sul campo "content" e lo scrive in "content_embedding"
#   4. Crea l'indice "semantic_docs" con la mappatura sparse_vector
#
# Prerequisiti:
#   - Elasticsearch in esecuzione su localhost:9200
#   - Licenza trial o Enterprise/Platinum attiva (necessaria per xpack.ml)
#     Per attivare la trial gratuita di 30 giorni:
#       POST http://localhost:9200/_license/start_trial?acknowledge=true
#
# Uso:
#   chmod +x setup-semantic-elastic.sh
#   ./setup-semantic-elastic.sh
# =============================================================================

set -e

ES_URL="${ES_URL:-http://localhost:9200}"
MODEL_ID=".elser_model_2"
PIPELINE_ID="elser-v2-sparse"
INDEX_NAME="semantic_docs"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------------------------------------------------------------------------
# 0. Attende che Elasticsearch sia raggiungibile
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
# 1. Attiva la trial license (necessaria per ML/ELSER)
#    Se è già attiva o hai già una licenza valida, questo step restituisce un
#    warning che viene ignorato silenziosamente.
# ---------------------------------------------------------------------------
info "Tentativo attivazione trial license (ignorato se già attiva)..."
TRIAL_RESP=$(curl -s -X POST "$ES_URL/_license/start_trial?acknowledge=true" \
  -H "Content-Type: application/json")
echo "  → $TRIAL_RESP"

# ---------------------------------------------------------------------------
# 2a. Download del modello ELSER v2 (se non già presente)
# ---------------------------------------------------------------------------
info "Verifica presenza modello $MODEL_ID..."
MODEL_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/_ml/trained_models/${MODEL_ID}")

if [[ "$MODEL_EXISTS" == "200" ]]; then
  info "Modello già presente – skip download."
else
  info "Download modello ELSER v2 dal registry Elastic (può richiedere alcuni minuti)..."
  DL_RESP=$(curl -s -X PUT "$ES_URL/_ml/trained_models/${MODEL_ID}" \
    -H "Content-Type: application/json" \
    -d '{"input":{"field_names":["text_field"]}}' \
    -w "\nHTTP_STATUS:%{http_code}")
  HTTP_CODE=$(echo "$DL_RESP" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
  BODY=$(echo "$DL_RESP" | sed '/HTTP_STATUS:/d')
  echo "  → HTTP $HTTP_CODE: $BODY"
  [[ "$HTTP_CODE" == "200" ]] || error "Errore download modello (HTTP $HTTP_CODE). Verifica connessione internet dal container ES."

  info "Attesa completamento download modello..."
  for i in $(seq 1 60); do
    DEFINED=$(curl -s "$ES_URL/_ml/trained_models/${MODEL_ID}?include=definition_status" \
      | grep -o '"fully_defined":[^,}]*' | head -1 | cut -d: -f2 | tr -d ' ')
    if [[ "$DEFINED" == "true" ]]; then
      info "Download completato."
      break
    fi
    warn "Download in corso ($i/60)..."
    sleep 5
  done
  [[ "$DEFINED" == "true" ]] || error "Download modello non completato entro il timeout."
fi

# ---------------------------------------------------------------------------
# 2b. Deploy del modello ELSER v2
#    Se il modello è già deployato, la chiamata restituisce un errore 409
#    che viene ignorato.
# ---------------------------------------------------------------------------
info "Avvio deploy del modello ELSER v2 ($MODEL_ID)..."
DEPLOY_RESP=$(curl -s -X POST \
  "$ES_URL/_ml/trained_models/${MODEL_ID}/deployment/_start?deployment_id=${MODEL_ID}&number_of_allocations=1&threads_per_allocation=1&wait_for=starting" \
  -H "Content-Type: application/json" \
  -w "\nHTTP_STATUS:%{http_code}")

HTTP_CODE=$(echo "$DEPLOY_RESP" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
BODY=$(echo "$DEPLOY_RESP" | sed '/HTTP_STATUS:/d')
echo "  → HTTP $HTTP_CODE: $BODY"

if [[ "$HTTP_CODE" != "200" && "$HTTP_CODE" != "409" ]]; then
  warn "Deploy restituito HTTP $HTTP_CODE – potrebbe essere già in corso o completato."
fi

# ---------------------------------------------------------------------------
# 3. Attende che il modello sia fully_allocated
# ---------------------------------------------------------------------------
info "Attesa che il modello sia fully_allocated..."
for i in $(seq 1 40); do
  ALLOC_STATE=$(curl -s "$ES_URL/_ml/trained_models/${MODEL_ID}/_stats" \
    | python3 -c 'import sys,json; r=json.load(sys.stdin); ds=r.get("trained_model_stats",[]); print(ds[0].get("deployment_stats",{}).get("allocation_status",{}).get("state","") if ds else "")' 2>/dev/null)

  if [[ "$ALLOC_STATE" == "fully_allocated" || "$ALLOC_STATE" == "started" ]]; then
    info "Modello pronto (state=$ALLOC_STATE)."
    break
  fi
  warn "Attesa deploy modello ($i/40) – state=$ALLOC_STATE..."
  sleep 5
done

if [[ "$ALLOC_STATE" != "fully_allocated" && "$ALLOC_STATE" != "started" ]]; then
  error "Il modello ELSER non è diventato fully_allocated entro il timeout."
fi

# ---------------------------------------------------------------------------
# 4. Crea l'ingest pipeline
# ---------------------------------------------------------------------------
info "Creazione ingest pipeline '$PIPELINE_ID'..."
PIPELINE_RESP=$(curl -s -X PUT "$ES_URL/_ingest/pipeline/$PIPELINE_ID" \
  -H "Content-Type: application/json" \
  -w "\nHTTP_STATUS:%{http_code}" \
  -d '{
    "description": "Pipeline ELSER v2: genera sparse embedding su content → content_embedding",
    "processors": [
      {
        "inference": {
          "model_id": ".elser_model_2",
          "input_output": [
            {
              "input_field":  "content",
              "output_field": "content_embedding"
            }
          ]
        }
      }
    ],
    "on_failure": [
      {
        "set": {
          "field": "_index",
          "value": "failed-semantic"
        }
      }
    ]
  }')

HTTP_CODE=$(echo "$PIPELINE_RESP" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
echo "  → HTTP $HTTP_CODE"
[[ "$HTTP_CODE" == "200" ]] || warn "Pipeline create response HTTP $HTTP_CODE (potrebbe esistere già)."

# ---------------------------------------------------------------------------
# 5. Crea l'indice con mappatura sparse_vector
#    Se l'indice esiste già viene saltato.
# ---------------------------------------------------------------------------
info "Verifica esistenza indice '$INDEX_NAME'..."
EXISTS=$(curl -s -o /dev/null -w "%{http_code}" "$ES_URL/$INDEX_NAME")

if [[ "$EXISTS" == "200" ]]; then
  warn "Indice '$INDEX_NAME' esiste già – skip creazione."
else
  info "Creazione indice '$INDEX_NAME'..."
  INDEX_RESP=$(curl -s -X PUT "$ES_URL/$INDEX_NAME" \
    -H "Content-Type: application/json" \
    -w "\nHTTP_STATUS:%{http_code}" \
    -d '{
      "settings": {
        "number_of_shards":   1,
        "number_of_replicas": 0,
        "default_pipeline":   "elser-v2-sparse"
      },
      "mappings": {
        "properties": {
          "documentId":        { "type": "keyword" },
          "fileName":          { "type": "keyword" },
          "chunkIndex":        { "type": "integer" },
          "chapterTitle":      { "type": "text"    },
          "chapterIndex":      { "type": "integer" },
          "content":           { "type": "text"    },
          "content_embedding": { "type": "sparse_vector" }
        }
      }
    }')

  HTTP_CODE=$(echo "$INDEX_RESP" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
  echo "  → HTTP $HTTP_CODE"
  [[ "$HTTP_CODE" == "200" ]] || error "Errore creazione indice (HTTP $HTTP_CODE)."
fi

# ---------------------------------------------------------------------------
# Fine
# ---------------------------------------------------------------------------
echo ""
info "Setup completato:"
info "  • Modello ELSER:  $MODEL_ID"
info "  • Ingest pipeline: $PIPELINE_ID"
info "  • Indice semantico: $INDEX_NAME"
echo ""
info "Endpoint disponibili dopo l'avvio dell'applicazione:"
info "  POST /api/semantic/index   – carica un PDF e lo indicizza con ELSER"
info "  POST /api/semantic/search  – ricerca per similitudine semantica"
echo ""
