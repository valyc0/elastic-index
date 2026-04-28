# Pipeline RAG con Docling

## Panoramica

Questo sistema implementa una pipeline RAG (Retrieval-Augmented Generation) enterprise che usa **Docling** (IBM) come parser documentale avanzato, **Elasticsearch** come vector database e **Ollama** (o OpenAI) per embedding e generazione delle risposte.

```
┌─────────────────────────────────────────────────────────────────┐
│                        INGESTION PIPELINE                       │
│                                                                 │
│  Upload file  →  Docling (Python)  →  Chunking semantico        │
│                       │                       │                 │
│              sezioni + tabelle         embedding vettoriale      │
│                                               │                 │
│                                       Elasticsearch index        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        RAG PIPELINE                             │
│                                                                 │
│  Domanda  →  Embedding query  →  Hybrid Search (BM25 + kNN)     │
│                                         │                       │
│                                  top-K chunk                    │
│                                         │                       │
│                               Context builder                   │
│                                         │                       │
│                                   LLM (Ollama)                  │
│                                         │                       │
│                              Risposta + fonti                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Componenti

| Componente | Tecnologia | Ruolo |
|---|---|---|
| **Docling Service** | Python / FastAPI | Parsing strutturato di documenti |
| **Spring Boot App** | Java 21 / Spring Boot 4 | Orchestrazione pipeline |
| **Elasticsearch** | 8.11.3 | Vector store + full-text index |
| **Ollama** | llama3 + nomic-embed-text | LLM e embedding locali |
| **Docker Compose** | — | Infrastruttura containerizzata |

---

## Architettura dei servizi

```
┌──────────────────┐     multipart/form-data      ┌─────────────────────┐
│  Spring Boot     │ ────────────────────────────► │  Docling Service    │
│  :8080           │                               │  Python / FastAPI   │
│                  │ ◄──────────────────────────── │  :8001              │
│  DoclingClient   │     JSON strutturato          └─────────────────────┘
│  DoclingController│
│  SemanticIndexService│     embed + index         ┌─────────────────────┐
│  HybridSearchService │ ──────────────────────── ►│  Elasticsearch      │
│  RagService      │                               │  :9200              │
│                  │ ◄──────────────────────────── └─────────────────────┘
│                  │     chunk + score
│                  │
│                  │     embed / chat              ┌─────────────────────┐
│  EmbeddingProvider│ ─────────────────────────── ►│  Ollama             │
│  LlmProvider     │ ◄──────────────────────────── │  :11434             │
└──────────────────┘                               └─────────────────────┘
```

---

## Docling Service (Python)

### Cosa fa Docling

Docling è una libreria IBM che va oltre il semplice estrazione di testo grezzo:

- **Struttura gerarchica**: riconosce H1, H2, H3 e li mappa in sezioni numerate
- **Tabelle strutturate**: estrae tabelle e le converte in testo rappresentativo per l'embedding
- **Layout preservato**: mantiene l'ordine logico delle sezioni anche in PDF complessi
- **Formati supportati**: PDF, DOCX, HTML, PPTX, XLSX, Markdown, AsciiDoc

### API del servizio

#### `POST /parse`

Riceve un file via `multipart/form-data` e restituisce la struttura estratta.

**Request:**
```bash
curl -X POST http://localhost:8001/parse \
  -F "file=@documento.pdf"
```

**Response:**
```json
{
  "file_name": "documento.pdf",
  "full_text": "Testo completo del documento...",
  "page_count": 42,
  "sections": [
    {
      "title": "Introduzione",
      "chapter_index": 0,
      "level": 1,
      "text": "Questo documento descrive...",
      "page_number": 1
    },
    {
      "title": "1.1 Contesto",
      "chapter_index": 1,
      "level": 2,
      "text": "Il contesto di riferimento è...",
      "page_number": 2
    }
  ],
  "tables": [
    {
      "caption": "Tabella 1 - Risultati",
      "text_representation": "Colonna A  Colonna B\n   100        200\n   150        300",
      "page_number": 15
    }
  ],
  "metadata": {
    "fileName": "documento.pdf",
    "title": "Titolo del documento",
    "author": "Autore"
  }
}
```

#### `GET /health`

```bash
curl http://localhost:8001/health
# {"status": "UP", "service": "docling-service"}
```

### Confronto Tika vs Docling

| Caratteristica | Apache Tika | Docling |
|---|---|---|
| Estrazione testo | ✅ Sì | ✅ Sì |
| Struttura gerarchica (H1/H2) | ❌ No | ✅ Sì |
| Tabelle strutturate | ❌ Solo testo grezzo | ✅ DataFrame/markdown |
| Layout preservato | ❌ No | ✅ Sì |
| Velocità | ✅ Molto rapida | ⚠️ Più lenta (modelli ML) |
| Dipendenze | JVM nativa | Python + modelli |
| Qualità su PDF complessi | ⚠️ Variabile | ✅ Alta |

---

## Flusso di Ingestion dettagliato

### Step 1 — Upload file

Il client HTTP invia il file a Spring Boot:

```bash
POST /api/docling/index
Content-Type: multipart/form-data

file: <binary PDF/DOCX/HTML/...>
```

### Step 2 — Docling parsing (Python)

`DoclingClient.java` costruisce una richiesta `multipart/form-data` e la invia al servizio Python su `:8001/parse`.

Il servizio Python (`main.py`) itera gli item del documento classificandoli in tre tipi:

- `SectionHeaderItem` → titolo di sezione (H1/H2/H3). Crea una nuova sezione e traccia il capitolo H1 corrente per i livelli figli
- `TextItem` → testo narrativo. Viene aggregato alla sezione corrente
- `TableItem` → tabella. Viene esportata come DataFrame pandas e convertita in stringa colonnare

La gerarchia dei titoli viene preservata con due campi aggiuntivi:

```json
{
  "title": "2.1 Raccolta dati",
  "level": 2,
  "parent_chapter_title": "2. Metodologia",
  "parent_chapter_index": 3
}
```

Le sezioni con `level=1` hanno `parent_chapter_title: null` (sono già capitoli). Le sezioni con `level>=2` ereditano il titolo e l'indice del capitolo H1 padre.

### Step 3 — Conversione in `DocumentExtractionResult` (Java)

`DoclingClient.toExtractionResult()` converte ogni `DoclingSection` in `ChapterSection`. Per le sottosezioni costruisce un titolo gerarchico che unisce capitolo e sezione:

```
level=1  "2. Metodologia"           →  chapterTitle = "2. Metodologia"
level=2  "2.1 Raccolta dati"        →  chapterTitle = "2. Metodologia > 2.1 Raccolta dati"
level=3  "2.1.1 Campionamento"      →  chapterTitle = "2. Metodologia > 2.1.1 Campionamento"
```

Le tabelle vengono aggiunte in coda come sezioni extra con titolo `"Tabella N"` o la didascalia originale, così sono ricercabili separatamente dal testo narrativo.

Il risultato è una lista di `ChapterSection`, ognuna con tutto il testo della sezione (potenzialmente centinaia o migliaia di parole). **Il chunking vero e proprio avviene nel passo successivo.**

### Step 4 — Chunking semantico (Java)

`SemanticChunkingService.chunkSections()` itera la lista di `ChapterSection` e suddivide il testo di ciascuna in chunk più piccoli. Ogni chunk **eredita il `chapterTitle` della sezione di provenienza**, quindi se un capitolo lungo produce 3 chunk, tutti e 3 portano lo stesso titolo.

**Algoritmo interno (`chunkTextSemantically`):**

```
Testo della sezione
        │
        ▼
Divide in paragrafi (su \n\n)
        │
        ▼  per ogni paragrafo
Divide in frasi (su ". " "! " "? ")
        │
        ▼
Accumula frasi finché parole < 400
        │
     ┌──┴──────────────────────┐
     │ limite raggiunto?       │
     │  sì → salva chunk       │
     │      riparti dalle      │
     │      ultime 2 frasi     │  ← overlap
     │      (contesto bridge)  │
     └─────────────────────────┘
        │
        ▼
Chunk finale residuo < 30 parole?
  → fusione con il chunk precedente
```

Esempio concreto con un capitolo da 900 parole:

```
"2. Metodologia"  →  900 parole
        │
        ▼  SemanticChunkingService
ChunkEntry(0, "La metodologia... [~400 parole]",  "2. Metodologia")
ChunkEntry(1, "[overlap 2 frasi]... [~400 parole]","2. Metodologia")
ChunkEntry(2, "[overlap 2 frasi]... [~100 parole]","2. Metodologia")
```

I parametri sono configurabili in `application.properties`:

```properties
chunking.max-words=400          # parole massime per chunk
chunking.overlap-sentences=2    # frasi di overlap tra chunk consecutivi
chunking.min-words=30           # soglia sotto la quale il chunk viene fuso
```

### Step 5 — Generazione embedding (Java)

`EmbeddingProvider` (default: Ollama con `nomic-embed-text`, 768 dimensioni) riceve il testo di ogni `ChunkEntry` e restituisce un vettore denso normalizzato. Il provider è intercambiabile via property (`embedding.provider=ollama|openai`) senza modificare codice.

### Step 6 — Indicizzazione in Elasticsearch (Java)

`SemanticIndexService` salva ogni chunk nell'indice `semantic_docs`. Il `chapterTitle` gerarchico costruito al Step 3 è ora visibile nel documento indicizzato:

```json
{
  "documentId": "a1b2c3-...",
  "fileName": "documento.pdf",
  "chunkIndex": 1,
  "chapterTitle": "2. Metodologia > 2.1 Raccolta dati",
  "chapterIndex": 4,
  "content": "I dati sono stati raccolti tramite...",
  "content_embedding": [0.12, -0.34, 0.07, ...]
}
```

**Schema del flusso completo:**

```
Docling Python → sezioni strutturate (testo intero per sezione)
       │
       ▼  DoclingClient.toExtractionResult()
List<ChapterSection>  con chapterTitle gerarchico
       │
       ▼  SemanticChunkingService.chunkSections()
List<ChunkEntry>  (testo ≤400 parole, overlap 2 frasi, chapterTitle ereditato)
       │
       ▼  EmbeddingProvider.embed(chunk.content)
vector[768]
       │
       ▼  SemanticIndexService → Elasticsearch
SemanticChunk { content, chapterTitle, content_embedding, ... }
```

---

## Flusso RAG dettagliato

### Step 1 — Domanda utente

```bash
POST /api/docling/ask
{
  "query": "Quali sono i risultati nella tabella del capitolo 3?",
  "topK": 5,
  "language": "it"
}
```

### Step 2 — Hybrid Search (BM25 + kNN)

`HybridSearchService` esegue in parallelo due ricerche:

**BM25 (full-text):**
```
content^3 + chapterTitle^2 + fileName  (fuzzy, multi-match)
```

**kNN (vector similarity):**
```
La query viene embeddara → cosine similarity contro i vettori in ES
numCandidates = topK × 5  (ampia finestra per il merge)
```

**Reciprocal Rank Fusion (RRF):**
```
RRF_score(d) = Σ  1 / (k + rank_i(d))     k = 60
```
I due elenchi vengono fusi con RRF: i documenti presenti in entrambe le liste vengono promossi, combinando la precisione del keyword matching con la copertura semantica.

### Step 3 — Context building

`RagService` assembla il prompt con i chunk recuperati:

```
[FONTE 1] Documento: documento.pdf | Sezione: Tabella 1 - Risultati
Colonna A  Colonna B
   100        200
   ...

[FONTE 2] Documento: documento.pdf | Sezione: 3. Analisi risultati
Nel terzo capitolo vengono analizzati...
```

Il contesto viene troncato per non superare le `3000` parole (finestra token configurabile).

### Step 4 — System prompt anti-allucinazione

```
Sei un assistente esperto che risponde alle domande basandosi ESCLUSIVAMENTE
sul contesto documentale fornito di seguito.

REGOLE TASSATIVE:
1. Rispondi SOLO usando le informazioni presenti nel contesto.
2. Se il contesto non contiene informazioni sufficienti, dichiaralo esplicitamente.
3. NON inventare, NON aggiungere informazioni non presenti nel contesto.
4. Cita la sezione o il documento di riferimento quando possibile.
5. Rispondi in modo chiaro, strutturato e conciso.
6. Rispondi nella lingua: it.
```

### Step 5 — Risposta LLM

`LlmProvider` (default: Ollama con `llama3`) genera la risposta.

### Step 6 — RagAnswer con fonti

```json
{
  "query": "Quali sono i risultati nella tabella del capitolo 3?",
  "answer": "I risultati mostrano che la Colonna A ha valori 100 e 150, mentre...",
  "llmModel": "llama3",
  "embeddingModel": "nomic-embed-text",
  "processingTimeMs": 2340,
  "sources": [
    {
      "fileName": "documento.pdf",
      "chapterTitle": "Tabella 1 - Risultati",
      "chunkIndex": 12,
      "relevanceScore": 0.0164,
      "content": "Colonna A  Colonna B\n   100..."
    }
  ]
}
```

---

## Guida ai Test

### Prerequisiti

Tutti i servizi devono essere in esecuzione prima di testare:

```bash
# Verifica che tutto sia UP
curl -s http://localhost:8001/health     # {"status":"UP","service":"docling-service"}
curl -s http://localhost:8080/api/rag/health  # {"status":"UP","service":"RAG Pipeline"}
curl -s http://localhost:9200/_cluster/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"  # green
```

---

### Test 1 — Health check completo

```bash
echo "=== Docling ===" && curl -s http://localhost:8001/health
echo "=== RAG API ===" && curl -s http://localhost:8080/api/rag/health
echo "=== Docling via Java ===" && curl -s http://localhost:8080/api/docling/health
echo "=== Elasticsearch ===" && curl -s http://localhost:9200/_cluster/health?pretty | grep '"status"'
echo "=== Ollama ===" && curl -s http://localhost:11434/api/tags | python3 -c "import sys,json; d=json.load(sys.stdin); print([m['name'] for m in d['models']])"
```

**Atteso:** tutti i servizi rispondono UP / green.

---

### Test 2 — Indicizzazione documento con Docling

```bash
curl -X POST http://localhost:8080/api/docling/index \
  -F "file=@/path/to/documento.pdf"
```

**Atteso:**
```json
{
  "documentId": "a1b2c3d4-...",
  "fileName": "documento.pdf",
  "sections": 16,
  "chunks": 187,
  "parser": "docling",
  "message": "Documento indicizzato con Docling"
}
```

> Il parsing su CPU richiede ~1-5 minuti per PDF di 100-300 pagine.  
> Monitorare il progresso: `docker logs docling-service -f`

---

### Test 3 — Verifica chunk in Elasticsearch

```bash
# Conta i documenti indicizzati
curl -s "http://localhost:9200/semantic_docs/_count" | python3 -m json.tool

# Cerca un termine nel testo
curl -s -X POST "http://localhost:9200/semantic_docs/_search" \
  -H "Content-Type: application/json" \
  -d '{"query": {"match": {"content": "capitano"}}, "size": 2, "_source": ["fileName","chapterTitle","content"]}' \
  | python3 -m json.tool | head -40
```

**Atteso:** `count > 0`, risultati con `fileName`, `chapterTitle`, `content`.

---

### Test 4 — Ricerca ibrida (BM25 + kNN) senza LLM

```bash
curl -s -X POST http://localhost:8080/api/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "capitano Nemo", "topK": 5}' \
  | python3 -c "
import sys, json
results = json.load(sys.stdin)
for r in results:
    print(f\"score={r['relevanceScore']:.3f} | {r['fileName']} | {r['chapterTitle'][:50]}\")
"
```

**Atteso:** lista di chunk ordinati per rilevanza con score decrescente.

---

### Test 5 — Pipeline RAG completa

```bash
curl -s --max-time 120 -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"query": "Chi sono i protagonisti del romanzo?", "topK": 5}' \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('=== RISPOSTA ===')
print(d['answer'])
print()
print(f\"LLM: {d['llmModel']} | Embedding: {d['embeddingModel']} | {d['processingTimeMs']}ms\")
print()
print('=== FONTI ===')
for s in d['sources']:
    print(f\"  [{s['chunkIndex']}] {s['fileName']} — {s['chapterTitle'][:50]} (score={s['relevanceScore']:.3f})\")
"
```

**Atteso:** risposta in linguaggio naturale con fonti citate (fileName, chapterTitle, score).

---

### Test 6 — RAG filtrato per documento specifico

Prima recupera il `documentId` dall'indicizzazione (Test 2), poi:

```bash
curl -s --max-time 120 -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Qual è la conclusione del romanzo?",
    "topK": 5,
    "documentId": "a1b2c3d4-..."
  }' | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['answer'])"
```

**Atteso:** risposta che attinge solo ai chunk del documento indicato.

---

### Test 7 — Endpoint Docling (parse + indicizzazione + ask in un solo flusso)

```bash
# Indicizza tramite controller Docling
curl -X POST http://localhost:8080/api/docling/index \
  -F "file=@documento.pdf"

# Poi chiedi direttamente
curl -s --max-time 120 -X POST http://localhost:8080/api/docling/ask \
  -H "Content-Type: application/json" \
  -d '{"query": "Di cosa parla il documento?", "topK": 5}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['answer'])"
```

---

### Troubleshooting rapido

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| `HTTP 422` su `/parse` | Invio multipart malformato | Verificare Content-Type header |
| Risposta RAG vuota | Modello LLM non installato in Ollama | `ollama pull <modello>` |
| `HTTP 500` embedding | `nomic-embed-text` non disponibile | `ollama pull nomic-embed-text` |
| Docling impiega troppo | CPU lenta, modelli non in cache | Normale al primo avvio; i successivi sono più veloci |
| `index_not_found_exception` | Indice ES non creato | Eseguire `./setup-semantic-elastic.sh` |
| Risposta LLM in inglese | Modello non istruito in italiano | Aggiungere `"language":"it"` nella request, o usare modello multilingue |

---

## Avvio

### Prerequisiti

- Docker e Docker Compose
- Java 21
- Maven 3.9+

### 1. Avvio infrastruttura

```bash
# Avvia Elasticsearch, Kibana, Ollama e Docling
docker compose up -d

# Attendi che Docling sia pronto (scarica modelli al primo avvio, ~2-3 min)
docker compose logs -f docling-service
```

### 2. Setup Ollama (prima volta)

```bash
# Scarica il modello di embedding
docker exec ollama ollama pull nomic-embed-text

# Scarica il modello LLM
docker exec ollama ollama pull llama3
```

### 3. Crea l'indice Elasticsearch

```bash
# Crea il mapping dell'indice semantic_docs con dense_vector 768 dims
./setup-semantic-elastic.sh
```

### 4. Avvio applicazione Spring Boot

```bash
cd my-app
./mvnw spring-boot:run
```

### 5. Verifica servizi

```bash
# Docling service
curl http://localhost:8001/health

# Spring Boot + Docling integration
curl http://localhost:8080/api/docling/health
```

---

## Utilizzo API

### Indicizza un documento con Docling

```bash
curl -X POST http://localhost:8080/api/docling/index \
  -F "file=@/path/to/documento.pdf"
```

```json
{
  "documentId": "a1b2c3d4-...",
  "fileName": "documento.pdf",
  "sections": 24,
  "chunks": 87,
  "parser": "docling",
  "message": "Documento indicizzato con Docling"
}
```

### Fai una domanda RAG

```bash
curl -X POST http://localhost:8080/api/docling/ask \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Chi sono i protagonisti principali?",
    "topK": 5,
    "language": "it"
  }'
```

### Ricerca ibrida senza LLM (debug retrieval)

```bash
curl -X POST http://localhost:8080/api/rag/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "risultati della tabella",
    "topK": 10
  }'
```

### Filtra per documento specifico

```bash
curl -X POST http://localhost:8080/api/docling/ask \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Qual è la conclusione?",
    "topK": 5,
    "documentId": "a1b2c3d4-..."
  }'
```

---

## Configurazione

Tutte le impostazioni sono in `my-app/src/main/resources/application.properties`.

### Switch provider embedding (zero codice)

```properties
# Locale (default) — gratuito, richiede Ollama
embedding.provider=ollama
ollama.embed.model=nomic-embed-text
ollama.embed.dimensions=768

# Cloud — richiede API key OpenAI
embedding.provider=openai
openai.api.key=sk-...
openai.embed.model=text-embedding-3-small
openai.embed.dimensions=1536
```

### Switch LLM (zero codice)

```properties
# Locale (default)
llm.provider=ollama
ollama.chat.model=llama3

# Cloud
llm.provider=openai
openai.chat.model=gpt-4o-mini
openai.chat.max-tokens=1024
```

### Chunking semantico

```properties
chunking.max-words=400          # parole massime per chunk
chunking.overlap-sentences=2    # frasi di overlap tra chunk consecutivi
chunking.min-words=30           # chunk più corti vengono fusi con il precedente
```

### RAG pipeline

```properties
rag.context.max-tokens=3000     # parole massime nel contesto inviato all'LLM
rag.context.top-k=5             # chunk recuperati di default
```

### URL Docling service

```properties
docling.url=http://localhost:8001   # cambia se in Docker: http://docling-service:8001
```

---

## Struttura dei file

```
elastic-index/
├── docker-compose.yml               ← Elasticsearch + Ollama + Docling
├── docling-service/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py                      ← FastAPI + Docling parsing
└── my-app/
    └── src/main/java/io/bootify/my_app/
        ├── model/
        │   ├── DoclingParseResponse.java   ← mapping risposta Docling
        │   ├── RagAnswer.java              ← risposta RAG con fonti
        │   └── RagRequest.java             ← request RAG
        ├── service/
        │   ├── DoclingClient.java          ← HTTP client verso Python
        │   ├── DoclingException.java
        │   ├── SemanticChunkingService.java ← chunking sentence-aware
        │   ├── HybridSearchService.java    ← BM25 + kNN + RRF
        │   ├── RagService.java             ← orchestratore pipeline RAG
        │   ├── SemanticIndexService.java   ← indicizzazione ES
        │   └── embedding/
        │       ├── EmbeddingProvider.java  ← interfaccia
        │       ├── OllamaEmbeddingProvider.java
        │       └── OpenAiEmbeddingProvider.java
        │   └── llm/
        │       ├── LlmProvider.java        ← interfaccia
        │       ├── OllamaLlmProvider.java
        │       └── OpenAiLlmProvider.java
        └── rest/
            ├── DoclingController.java      ← /api/docling/*
            └── RagController.java          ← /api/rag/*
```

---

## Anti-pattern evitati

| Anti-pattern | Soluzione adottata |
|---|---|
| Parser che restituisce solo testo grezzo | Docling estrae gerarchia e tabelle |
| Chunking fixed-size che spezza le frasi | Sentence-boundary splitting con overlap |
| Solo BM25 o solo kNN | Hybrid search con RRF |
| LLM che inventa informazioni | System prompt con regole anti-allucinazione esplicite |
| Embedding provider hardcoded | Interfaccia `EmbeddingProvider`, switch via properties |
| LLM hardcoded | Interfaccia `LlmProvider`, switch via properties |
| Tabelle non indicizzabili | Tabelle convertite in testo e aggiunte come sezioni |
