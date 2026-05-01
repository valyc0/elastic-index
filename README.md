# elastic-index — Pipeline RAG con Docling + Elasticsearch

Sistema di **Retrieval-Augmented Generation (RAG)** per documenti strutturati.  
Usa **Docling** (IBM) per il parsing intelligente dei PDF, **Elasticsearch** come vector store e **Ollama / OpenAI / OpenRouter** per embedding e generazione delle risposte.

---

## Indice

- [Architettura](#architettura)
- [Stack tecnologico](#stack-tecnologico)
- [Struttura del progetto](#struttura-del-progetto)
- [Quick Start](#quick-start)
- [Configurazione](#configurazione)
- [Pipeline di ingestion](#pipeline-di-ingestion)
- [Pipeline RAG](#pipeline-rag)
- [Docling Service](#docling-service)
- [Hybrid Search](#hybrid-search)
- [Profili LLM](#profili-llm)
- [Script](#script)
- [API Reference](#api-reference)
- [Ottimizzazione performance](#ottimizzazione-performance)

---

## Architettura

```
                        ┌─────────────────────────────────────┐
                        │           CLIENT HTTP               │
                        │   curl / Spring Boot frontend       │
                        └──────────────┬──────────────────────┘
                                       │
                        ┌──────────────▼──────────────────────┐
                        │         SPRING BOOT :8080           │
                        │                                     │
                        │  DoclingController                  │
                        │  ├─ DoclingClient                   │
                        │  ├─ SemanticChunkingService         │
                        │  ├─ SemanticIndexService            │
                        │  ├─ HybridSearchService             │
                        │  ├─ RagService                      │
                        │  └─ ConversationSessionService      │
                        └──────┬─────────────┬───────────────┘
                               │             │
               ┌───────────────▼──┐   ┌──────▼──────────────┐
               │  DOCLING SERVICE │   │   ELASTICSEARCH     │
               │  Python/FastAPI  │   │   :9200             │
               │  :8001           │   │   indice:           │
               │                  │   │   semantic_docs     │
               │  Modelli:        │   └──────┬──────────────┘
               │  - Layout        │          │
               │  - TableFormer   │   ┌──────▼──────────────┐
               └──────────────────┘   │      OLLAMA         │
                                      │      :11434         │
                                      │  nomic-embed-text   │
                                      │  llama3.2:3b (opt.) │
                                      └─────────────────────┘
```

---

## Stack tecnologico

| Componente | Versione | Ruolo |
|---|---|---|
| Java / Spring Boot | 21 / 4.x | Orchestrazione pipeline |
| Python / FastAPI | 3.11 / 0.111+ | Microservizio parsing |
| Docling (IBM) | ≥ 2.0 | Parsing strutturato documenti |
| Elasticsearch | 8.11.3 | Vector store + full-text search |
| Ollama | latest | LLM e embedding locali |
| Docker Compose | — | Infrastruttura containerizzata |

---

## Struttura del progetto

```
elastic-index/
├── esempi/                    # PDF di esempio per i test
├── bin/                       # Script legacy (pipeline Tika, non più attiva)
├── doc/                       # Documentazione approfondita
├── docling-service/           # Microservizio Python
│   ├── main.py                # FastAPI + logica parsing
│   ├── Dockerfile
│   └── requirements.txt
├── my-app/                    # Applicazione Spring Boot
│   └── src/main/java/io/bootify/my_app/
│       ├── config/            # ElasticsearchConfig, IndexNameResolver
│       ├── model/             # DTO: ChapterSection, DocumentChunk, RagAnswer...
│       ├── rest/              # Controller REST
│       └── service/           # Logica di business
│           ├── DoclingClient.java
│           ├── SemanticChunkingService.java
│           ├── SemanticIndexService.java
│           ├── HybridSearchService.java
│           ├── RagService.java
│           └── ConversationSessionService.java
├── start-all.sh               # Avvia stack Docker + Spring Boot
├── ingest-docling.sh          # Indicizza un documento
├── reset-all.sh               # Reset completo (container + volumi)
├── run-and-test-docling.sh    # start-all + ingest in un comando
├── test-rag.sh                # Test pipeline RAG
├── query-rag.sh               # Singola query RAG
└── docker-compose.yml
```

---

## Quick Start

### Prerequisiti

- Java 21, Maven 3.8+
- Docker + Docker Compose
- `curl`, `python3`

### Avvio in un comando

```bash
./run-and-test-docling.sh esempi/ventimila-leghe.pdf
```

Oppure separato:

```bash
# 1. Avvia l'infrastruttura
./start-all.sh

# 2. Indicizza un documento
./ingest-docling.sh esempi/ventimila-leghe.pdf

# 3. Query RAG manuale
QUERY="Chi è il capitano Nemo?" ./query-rag.sh
```

### Reset completo

```bash
./reset-all.sh
```

---

## Configurazione

### Profilo attivo

In [my-app/src/main/resources/application.properties](my-app/src/main/resources/application.properties):

```properties
spring.profiles.active=openrouter   # ollama | openai | openrouter
```

### Parametri principali

| Parametro | Default | Descrizione |
|---|---|---|
| `chunking.max-words` | 400 | Parole massime per chunk |
| `chunking.overlap-sentences` | 2 | Frasi di overlap tra chunk contigui |
| `chunking.min-words` | 30 | Chunk più corti vengono scartati |
| `rag.context.top-k` | 5 | Chunk recuperati per query |
| `rag.context.max-tokens` | 3000 | Token massimi del contesto LLM |
| `rag.conversation.max-turns` | 10 | Turni mantenuti in sessione |
| `rag.conversation.ttl-minutes` | 60 | TTL sessione per inattività |

### Variabili d'ambiente Docling Service

| Variabile | Default | Descrizione |
|---|---|---|
| `DOCLING_THREADS` | 12 | Thread CPU per modello |
| `DOCLING_MAX_CONCURRENT` | 1 | Worker paralleli (ognuno carica i modelli in RAM) |
| `DOCLING_TIMEOUT_SEC` | 300 | Timeout per singola conversione |
| `DOCLING_MAX_FILE_MB` | 100 | Limite dimensione file |
| `DOCLING_DEVICE` | cpu | Dispositivo: `cpu`, `cuda`, `mps` |

---

## Pipeline di Ingestion

Il flusso completo dall'upload al documento indicizzato:

```
PDF / DOCX / HTML
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  1. POST /api/docling/index  (Spring Boot)          │
│     DoclingController riceve il file                │
└──────────────────────┬──────────────────────────────┘
                       │ multipart/form-data
                       ▼
┌─────────────────────────────────────────────────────┐
│  2. Docling Service  :8001/parse  (Python)          │
│                                                     │
│  Iterazione unica su doc.iterate_items():           │
│  ├─ SectionHeaderItem → nuova sezione (H1/H2/H3)   │
│  ├─ TextItem          → testo aggregato alla sez.  │
│  └─ TableItem         → DataFrame → stringa         │
│                                                     │
│  Output JSON:                                       │
│  { sections: [...], tables: [...], full_text }      │
└──────────────────────┬──────────────────────────────┘
                       │ JSON strutturato
                       ▼
┌─────────────────────────────────────────────────────┐
│  3. DoclingClient.toExtractionResult()  (Java)      │
│                                                     │
│  Converte sezioni in ChapterSection con titolo      │
│  gerarchico:                                        │
│  level=1 "Metodologia"                              │
│  level=2 "Metodologia > 2.1 Raccolta dati"         │
│  level=3 "Metodologia > 2.1.1 Campionamento"       │
│                                                     │
│  Le tabelle diventano ChapterSection extra          │
│  (ricercabili separatamente)                        │
└──────────────────────┬──────────────────────────────┘
                       │ List<ChapterSection>
                       ▼
┌─────────────────────────────────────────────────────┐
│  4. SemanticChunkingService  (Java)                 │
│                                                     │
│  Ogni ChapterSection viene suddivisa in chunk       │
│  da max 400 parole con 2 frasi di overlap.          │
│                                                     │
│  Chunk troppo corti (< 30 parole) vengono scartati. │
│  Il titolo della sezione viene preposto al chunk    │
│  per migliorare la qualità dell'embedding.          │
└──────────────────────┬──────────────────────────────┘
                       │ List<DocumentChunk>
                       ▼
┌─────────────────────────────────────────────────────┐
│  5. SemanticIndexService  (Java)                    │
│                                                     │
│  Per ogni chunk:                                    │
│  ├─ EmbeddingProvider.embed(text) → float[]        │
│  └─ Elasticsearch index → semantic_docs             │
│                                                     │
│  Documento ES:                                      │
│  { id, documentId, chunkIndex, chapterTitle,        │
│    content, embedding[768/1536], fileName,          │
│    pageNumber, uploadedAt }                         │
└─────────────────────────────────────────────────────┘
```

---

## Pipeline RAG

Il flusso dalla domanda alla risposta:

```
Domanda utente
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  1. POST /api/docling/ask  (Spring Boot)            │
│     RagController riceve { query, topK, sessionId } │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│  2. EmbeddingProvider.embed(query) → float[]        │
│     Converte la domanda in vettore                  │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│  3. HybridSearchService  (BM25 + kNN RRF)           │
│                                                     │
│  Reciprocal Rank Fusion combina:                    │
│  ├─ BM25 full-text (peso 1.0)                      │
│  └─ kNN cosine similarity su embedding (peso 1.0)  │
│                                                     │
│  Restituisce i top-K chunk con score di rilevanza   │
└──────────────────────┬──────────────────────────────┘
                       │ List<SearchResult>
                       ▼
┌─────────────────────────────────────────────────────┐
│  4. RagService — Context builder                    │
│                                                     │
│  Assembla il contesto per l'LLM:                   │
│  - Cronologia conversazione (sliding window)        │
│  - Chunk rilevanti con titolo sezione e fonte       │
│  - Prompt di sistema con istruzioni                 │
│                                                     │
│  Tronca se supera rag.context.max-tokens            │
└──────────────────────┬──────────────────────────────┘
                       │ prompt
                       ▼
┌─────────────────────────────────────────────────────┐
│  5. LlmProvider.chat(prompt) → risposta             │
│     Ollama / OpenAI / OpenRouter                    │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│  6. RagAnswer                                       │
│  { answer, sources, llmModel, embeddingModel,       │
│    processingTimeMs, followupQuestions }            │
└─────────────────────────────────────────────────────┘
```

### Conversazione multi-turno

Il sistema mantiene sessioni conversazionali in memoria con sliding window:

- Ogni richiesta può includere un `sessionId`
- Gli ultimi `rag.conversation.max-turns` turni (domanda + risposta) vengono inclusi nel contesto
- Le sessioni scadono dopo `rag.conversation.ttl-minutes` minuti di inattività
- Il `ConversationSessionService` gestisce la pulizia automatica

---

## Docling Service

Il microservizio Python è progettato per la produzione con più client concorrenti.

### Gestione della concorrenza

```
Request 1 ──► Semaphore ──► Worker Process 1 ──► Converter isolato
Request 2 ──► Semaphore ──► Worker Process 2 ──► Converter isolato
Request 3 ──► Semaphore ──►  (attende slot libero)
```

- **`ProcessPoolExecutor`**: ogni worker è un processo separato → bypassa il GIL di Python
- **`asyncio.Semaphore`**: limita i worker attivi a `DOCLING_MAX_CONCURRENT`
- **Converter isolato**: ogni worker carica i modelli una volta sola al boot (non ad ogni richiesta)
- **Timeout**: `asyncio.wait_for` garantisce un HTTP 504 se la conversione supera il limite
- **Limite file**: restituisce HTTP 413 se il file supera `DOCLING_MAX_FILE_MB`

### Modelli interni di Docling

Docling usa due modelli ML scaricati da HuggingFace al primo avvio:

| Modello | Ruolo |
|---|---|
| **Layout Analysis** | Identifica regioni di testo, titoli, tabelle nel PDF |
| **TableFormer** | Riconosce la struttura delle celle nelle tabelle |

I modelli vengono scaricati in `~/.cache/huggingface` e riusati ad ogni avvio.

### Perché Docling è più lento di Tika

| Fase | Tika | Docling |
|---|---|---|
| Estrazione testo | Regole basate su testo | Layout Analysis (ML) |
| Tabelle | Testo grezzo | TableFormer (ML) |
| Ordine lettura | Non garantito | Ricostruito dal layout |
| Tempo tipico (100 pag.) | < 5s | 1-5 min (CPU), < 30s (GPU) |

---

## Hybrid Search

La ricerca combina due approcci con **Reciprocal Rank Fusion (RRF)**:

```
Query: "capitano del Nautilus"
         │
         ├─► BM25 (full-text)
         │   Cerca le parole esatte nel campo content
         │   Score: TF-IDF + BM25
         │
         └─► kNN (semantic)
             Confronta il vettore della query con i vettori dei chunk
             Distanza: cosine similarity
             Campo: embedding (768 o 1536 dimensioni)
         │
         ▼
    RRF Fusion
    score_rrf = Σ (weight / (rank + 60))
         │
         ▼
    Top-K risultati ordinati per rilevanza combinata
```

Questo approccio supera i limiti di entrambe le tecniche singole:
- BM25 è preciso per termini esatti ma non capisce sinonimi o parafrasi
- kNN capisce il significato ma può restituire falsi positivi per query specifiche
- RRF combina i rank in modo robusto senza richiedere normalizzazione degli score

---

## Profili LLM

### openrouter (default)

```properties
embedding.provider=ollama       # nomic-embed-text locale
llm.provider=openrouter         # LLM remoto via OpenRouter API
openrouter.chat.model=nvidia/nemotron-3-nano-30b-a3b:free
```

Richiede `OPENROUTER_API_KEY` in `.env`.

### ollama (tutto locale)

```properties
embedding.provider=ollama       # nomic-embed-text
llm.provider=ollama             # llama3.2:3b
```

Nessuna chiave API necessaria. Più lento, richiede ~4-6 GB RAM extra per il modello.

### openai

```properties
embedding.provider=openai       # text-embedding-3-small
llm.provider=openai             # gpt-4o-mini
```

Richiede `openai.api.key` in `application-openai.properties`.

---

## Script

| Script | Descrizione |
|---|---|
| `./start-all.sh` | Avvia Docker (ES + Ollama + Docling) + Spring Boot. Verifica e scarica automaticamente i modelli Ollama richiesti dal profilo attivo |
| `./ingest-docling.sh [file]` | Indica un documento e testa la pipeline RAG. Richiede stack già avviata |
| `./reset-all.sh` | Stop container + rimozione volumi + pulizia log. Le immagini Docker vengono mantenute |
| `./run-and-test-docling.sh [file]` | `start-all` + `ingest-docling` in sequenza |
| `./test-rag.sh` | Test end-to-end della pipeline RAG su documento già indicizzato |
| `./query-rag.sh "domanda"` | Singola query RAG su documenti già indicizzati |

### Override variabili

Tutti gli script rispettano le variabili d'ambiente:

```bash
BASE_URL=http://server:8080 QUERY="chi è Zanna Bianca?" ./ingest-docling.sh esempi/Zanna\ Bianca\ \(1\).pdf
```

---

## API Reference

### Indicizzazione

```bash
# Indicizza un PDF
POST /api/docling/index
Content-Type: multipart/form-data
file: <binary>

# Risposta
{
  "documentId": "uuid",
  "fileName": "documento.pdf",
  "sections": 42,
  "chunks": 87,
  "pageCount": 210,
  "processingTimeMs": 180000
}
```

### Query RAG

```bash
# Query con sessione
POST /api/docling/ask
Content-Type: application/json

{
  "query": "Chi è il capitano Nemo?",
  "topK": 5,
  "sessionId": "opzionale-per-conversazione"
}

# Risposta
{
  "answer": "Il capitano Nemo è...",
  "sources": [
    {
      "fileName": "ventimila-leghe.pdf",
      "chapterTitle": "Capitolo IV > Il Nautilus",
      "relevanceScore": 0.9231,
      "pageNumber": 48
    }
  ],
  "followupQuestions": ["Dove è stato costruito il Nautilus?", "..."],
  "llmModel": "nvidia/nemotron-3-nano-30b-a3b:free",
  "embeddingModel": "nomic-embed-text",
  "processingTimeMs": 2340,
  "sessionId": "uuid-sessione"
}
```

### Health

```bash
GET /api/docling/health
# {"status": "UP", "doclingService": "UP", "elasticsearch": "green"}
```

### Docling Service diretto

```bash
# Parse diretto (senza Spring Boot)
POST http://localhost:8001/parse
Content-Type: multipart/form-data
file: <binary>

GET http://localhost:8001/health
GET http://localhost:8001/docs    # Swagger UI
```

---

## Ottimizzazione performance

### Su macchina senza GPU (CPU only)

| Parametro | Valore consigliato | Motivo |
|---|---|---|
| `DOCLING_MAX_CONCURRENT` | 1 | Ogni worker carica ~2-3 GB di modelli |
| `DOCLING_THREADS` | n. core fisici | Evita contesa su hyperthreading |
| `DOCLING_DEVICE` | `cpu` | Auto-detection non sempre affidabile |
| `do_table_structure` | `false` | Risparmia ~30% se non ci sono tabelle |

**Impatto maggiore:** verificare il governor CPU.
```bash
cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
# se restituisce "powersave":
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor
```

### Con GPU NVIDIA

Nel `docker-compose.yml`, sotto `docling-service`:

```yaml
environment:
  - DOCLING_DEVICE=cuda
  - DOCLING_MAX_CONCURRENT=4
  - DOCLING_THREADS=4
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: 1
          capabilities: [gpu]
```

### Dimensionamento RAM

| Config | RAM richiesta |
|---|---|
| 1 worker (default) | ~3 GB per Docling + 2 GB ES + 1 GB Ollama embed |
| 2 worker | ~6 GB per Docling + overhead |
| llama3.2:3b locale | +2 GB aggiuntivi |

