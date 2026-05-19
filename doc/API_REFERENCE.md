# API Reference — RAG System (my-app + client-web)

Documentazione completa di tutte le chiamate API esposte da **my-app** (backend Spring Boot, porta `8080`) e invocate da **client-web** (frontend Vaadin, porta `8093`).

---

## Architettura generale

```
┌─────────────────────────────────────────────────────────┐
│  client-web  :8093  (Vaadin / RagApiService)            │
│   WebClient → http://localhost:8080                     │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP
┌───────────────────────▼─────────────────────────────────┐
│  my-app  :8080  (Spring Boot REST)                      │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ /api/docling │  │  /api/tika   │  │  /api/rag    │  │
│  │ /api/semantic│  │ /api/search  │  │ /api/documents│ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                  │          │
│  DoclingClient     DocumentExtraction  HybridSearch     │
│  (→ :8001)         Service (Tika)      Service (BM25+kNN│
│                                                         │
│  SemanticIndexService  EmbeddingProvider  LlmProvider   │
│  (→ Elasticsearch :9200)                               │
└─────────────────────────────────────────────────────────┘
                        │
          ┌─────────────▼──────────────┐
          │  docling-service  :8001     │
          │  (Python / FastAPI)         │
          └────────────────────────────┘
```

Le due pipeline di indicizzazione condividono lo stesso indice Elasticsearch (`semantic_docs`) e la stessa pipeline di query RAG.

---

## Indice

1. [Pipeline Docling (indicizzazione strutturata)](#1-pipeline-docling)
2. [Pipeline Tika (indicizzazione veloce)](#2-pipeline-tika)
3. [Pipeline Semantic / ELSER (indicizzazione legacy)](#3-pipeline-semantic)
4. [Gestione documenti parsati](#4-gestione-documenti-parsati)
5. [Pipeline RAG (query + risposta LLM)](#5-pipeline-rag)
6. [Ricerca full-text legacy](#6-ricerca-full-text-legacy)
7. [Estrazione testo grezza](#7-estrazione-testo-grezza)
8. [Indicizzazione da JSON](#8-indicizzazione-da-json)
9. [Sessioni conversazionali](#9-sessioni-conversazionali)
10. [Flussi interni dettagliati](#10-flussi-interni)

---

## 1. Pipeline Docling

> **Parser**: microservizio Python Docling (`:8001`) — parsing strutturato con riconoscimento layout, titoli gerarchici e tabelle.

### `POST /api/docling/index`

**Scopo**: carica un file, lo invia a Docling per il parsing strutturato e avvia l'indicizzazione semantica in modo **asincrono**.

**Chiamante (client-web)**: `RagApiService.uploadDocument(filename, content)`

```
POST /api/docling/index
Content-Type: multipart/form-data

file: <binario>
```

**Passi interni**:
1. `DoclingController` riceve il file e invoca `DoclingJobService.submitJob(file)`.
2. `DoclingJobService` crea un record H2 con stato `PROCESSING` (`ParsedDocumentService.createPending`).
3. Invia il file a Docling Python via `DoclingClient.submitParseAsync(file)` → `POST http://localhost:8001/parse/async`.
4. Crea un `DoclingJobStatus` con `status=QUEUED` e lo salva in memoria (`ConcurrentHashMap`).
5. Avvia un thread di background (`processJob`) che esegue il **polling** verso Python ogni 5 secondi.

**Risposta (202 Accepted)**:
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "fileName": "report.pdf",
  "message": "Elaborazione avviata. Controlla lo stato con GET /api/docling/jobs/<jobId>"
}
```

**Errori**:
- `400 Bad Request` — file vuoto
- `503 Service Unavailable` — servizio Docling Python non raggiungibile

---

### `GET /api/docling/jobs/{jobId}`

**Scopo**: controlla lo stato di un job di indicizzazione Docling.

**Chiamante (client-web)**: `RagApiService.getDoclingJobStatus(jobId)` — chiamato in polling dalla `UploadView`.

```
GET /api/docling/jobs/{jobId}
```

**Passi interni**:
1. Legge `DoclingJobStatus` dalla mappa in memoria via `DoclingJobService.getJob(jobId)`.
2. Riflette lo stato aggiornato dal thread di background.

**Stati possibili del job**:

| Stato | Significato |
|-------|-------------|
| `QUEUED` | Job creato, thread avviato |
| `PARSING` | Polling verso Docling Python in corso |
| `DONE` | Parsing completato, documento salvato in H2 come `TRANSCRIBED` |
| `ERROR` | Errore durante il parsing o timeout |

**Risposta (200 OK)**:
```json
{
  "jobId": "550e8400-...",
  "fileName": "report.pdf",
  "status": "DONE",
  "sections": 12,
  "message": "Trascrizione completata: 12 sezioni estratte.",
  "createdAt": 1716134400000,
  "updatedAt": 1716134520000
}
```

**Nota**: quando il job arriva a `DONE`, il documento è in stato `TRANSCRIBED` su H2 (pronto per revisione/modifica) ma **non ancora indicizzato in Elasticsearch**. Per indicizzarlo occorre chiamare [`POST /api/documents/parsed/{id}/index`](#post-apidocumentsparsedidindex).

---

### `GET /api/docling/jobs`

**Scopo**: lista tutti i job in memoria (utile per dashboard di monitoraggio).

```
GET /api/docling/jobs
```

**Risposta**: array di `DoclingJobStatus` (stesso schema del singolo job sopra).

---

### `POST /api/docling/ask`

**Scopo**: RAG completo su documenti indicizzati tramite Docling. Identico a `/api/rag/ask` (usa la stessa `RagService`).

Vedere sezione [5. Pipeline RAG](#5-pipeline-rag) per i dettagli.

---

### `GET /api/docling/health`

**Scopo**: verifica raggiungibilità del microservizio Docling Python.

**Chiamante (client-web)**: `RagApiService.getDocumentHealth()`

```
GET /api/docling/health
```

**Passi interni**: `DoclingClient.isAvailable()` → `GET http://localhost:8001/health`.

**Risposta (200)**:
```json
{ "status": "UP", "service": "docling-service" }
```
**Risposta (503)**:
```json
{ "status": "DOWN", "service": "docling-service", "message": "..." }
```

---

## 2. Pipeline Tika

> **Parser**: Apache Tika (in-process) + PDFBox per outline capitoli PDF — più veloce di Docling, senza microservizi esterni, meno preciso su documenti complessi.

### `POST /api/tika/index`

**Scopo**: carica un documento, lo estrae con Tika e lo indicizza con embedding vettoriali. Indicizzazione **sincrona** (risponde solo a completamento).

**Formato accettato**: PDF, DOCX, PPTX, XLSX, HTML, TXT, RTF, ODF e altri formati Tika.

```
POST /api/tika/index
Content-Type: multipart/form-data

file: <binario>
```

**Passi interni**:
1. `TikaController` riceve il file.
2. **Estrazione** — `DocumentExtractionService.extractTextAndMetadata(file)`:
   - Usa Apache Tika per estrarre testo e metadati (autore, titolo, date, ecc.).
   - Per i PDF tenta anche `PdfOutlineExtractor` (PDFBox) per ricavare i capitoli dall'outline.
3. **Indicizzazione** — `SemanticIndexService.indexDocument(documentId, extracted)`:
   - Se il documento ha capitoli → `SemanticChunkingService.chunkSections(chapters)`.
   - Se il documento è testo grezzo → `SemanticChunkingService.chunkText(text)`.
   - Filtra chunk boilerplate (indici, TOC, chunk < 10 parole).
   - Genera embedding per tutti i chunk in batch via `EmbeddingProvider.embedBatch(texts)`.
   - Elimina i vecchi chunk per lo stesso `fileName` (deduplicazione).
   - Esegue un'unica `BulkRequest` su Elasticsearch (`semantic_docs`).

**Risposta (200 OK)**:
```json
{
  "documentId": "uuid-generato",
  "fileName": "documento.pdf",
  "sections": 8,
  "chunks": 47,
  "parser": "tika",
  "message": "Documento indicizzato con Apache Tika"
}
```

**Parametri chunking** (da `application.properties`):
- `chunking.max-words=400` — dimensione massima chunk
- `chunking.overlap-sentences=2` — sovrapposizione tra chunk
- `chunking.min-words=30` — chunk più corti vengono scartati

---

## 3. Pipeline Semantic

> Pipeline legacy con ELSER (Elastic Learned Sparse Encoder) — indicizzazione tramite ingest pipeline Elasticsearch.

### `POST /api/semantic/index`

**Scopo**: carica un PDF, lo estrae con Tika e lo indicizza usando la ingest pipeline ELSER di Elasticsearch.

```
POST /api/semantic/index
Content-Type: multipart/form-data

file: <PDF>
```

**Passi interni**: identici a Tika (`DocumentExtractionService` + `SemanticIndexService`). L'embedding è generato da ELSER attraverso la ingest pipeline anziché da un provider esterno.

**Risposta**:
```json
{
  "documentId": "uuid",
  "fileName": "documento.pdf",
  "chunks": 42,
  "index": "semantic_docs",
  "message": "Documento indicizzato con embedding ELSER"
}
```

---

### `POST /api/semantic/search`

**Scopo**: ricerca semantica pura tramite ELSER (`text_expansion` query).

```
POST /api/semantic/search
Content-Type: application/json

{
  "query": "testo della domanda",
  "size": 10
}
```

**Risposta**: array di `SearchResult` (vedi schema in §5).

---

### `DELETE /api/semantic/document`

**Scopo**: elimina tutti i chunk di un documento dall'indice semantico.

**Chiamante (client-web)**: `RagApiService.deleteDocument(filename)`

```
DELETE /api/semantic/document?fileName=report.pdf
```

**Passi interni**: `SemanticIndexService.deleteChunksByFileName(fileName)` → `deleteByQuery` su `fileName.keyword`.

**Risposta**:
```json
{
  "fileName": "report.pdf",
  "deleted": 47,
  "message": "Chunk eliminati con successo"
}
```

---

### `POST /api/semantic/index/from-json`

**Scopo**: indicizza da un file JSON già estratto nella cartella `extracted-documents/`.

```
POST /api/semantic/index/from-json?jsonFile=report.json
```

---

## 4. Gestione documenti parsati

Il database H2 persistente (`./data/docling-store.mv.db`) conserva lo stato di ogni documento dalla fase di parsing fino all'indicizzazione.

**Stati documento**:

```
PROCESSING → TRANSCRIBED → INDEXING → INDEXED
                 ↘                      ↗
                  ERROR ←──────────────
```

### `GET /api/documents/parsed`

**Scopo**: lista tutti i documenti con il loro stato corrente.

**Chiamante (client-web)**: `RagApiService.getParsedDocuments()` → `DocumentListView`

```
GET /api/documents/parsed
```

**Risposta**: array di `ParsedDocumentSummary`
```json
[
  {
    "id": "uuid",
    "fileName": "report.pdf",
    "state": "TRANSCRIBED",
    "pageCount": 24,
    "sectionCount": 12,
    "chunks": null,
    "errorMessage": null,
    "createdAt": "2026-05-19T10:00:00Z",
    "updatedAt": "2026-05-19T10:05:00Z"
  }
]
```

---

### `GET /api/documents/parsed/{id}`

**Scopo**: dettaglio di un documento con le sezioni estratte (per l'editor).

**Chiamante (client-web)**: `RagApiService.getParsedDocument(id)` → `DocumentEditView`

```
GET /api/documents/parsed/{id}
```

**Risposta**: `ParsedDocumentDetail` — include `chapters` (lista di `ChapterSection` con titolo e testo) per consentire la modifica manuale prima dell'indicizzazione.

---

### `PUT /api/documents/parsed/{id}/sections`

**Scopo**: salva le sezioni modificate dall'utente nell'editor.

**Chiamante (client-web)**: `RagApiService.updateSections(id, chapters)`

```
PUT /api/documents/parsed/{id}/sections
Content-Type: application/json

{
  "chapters": [
    { "title": "Capitolo 1", "content": "Testo modificato...", "chapterIndex": 0 }
  ]
}
```

**Passi interni**: `ParsedDocumentService.updateSections(id, chapters)` serializza le sezioni aggiornate nel JSON persistente su H2.

**Risposta**:
```json
{ "id": "uuid", "sections": 12, "message": "Sezioni aggiornate" }
```

---

### `POST /api/documents/parsed/{id}/index`

**Scopo**: avvia l'indicizzazione in Elasticsearch per un documento in stato `TRANSCRIBED`.

**Chiamante (client-web)**: `RagApiService.indexParsedDocument(id)`

```
POST /api/documents/parsed/{id}/index
```

**Passi interni**:
1. Imposta lo stato a `INDEXING` immediatamente (risposta rapida).
2. Avvia `ParsedDocumentService.indexDocumentAsync(id)` in background.
3. Il background job carica il JSON da H2, esegue `SemanticIndexService.indexDocument(...)` e aggiorna lo stato a `INDEXED` (o `ERROR`).

**Risposta (202 Accepted)**:
```json
{ "id": "uuid", "state": "INDEXING", "message": "Indicizzazione avviata in background" }
```

---

### `DELETE /api/documents/parsed/{id}`

**Scopo**: elimina il documento da H2 e i relativi chunk da Elasticsearch.

**Chiamante (client-web)**: `RagApiService.deleteParsedDocument(id)`

```
DELETE /api/documents/parsed/{id}
```

**Risposta**:
```json
{ "id": "uuid", "message": "Documento eliminato" }
```

---

## 5. Pipeline RAG

> Endpoint principale per le query in linguaggio naturale. Combina retrieval ibrido e generazione LLM.

### `POST /api/rag/ask`

**Scopo**: pipeline RAG completa — recupera i chunk rilevanti e genera una risposta arricchita con fonti e domande di approfondimento.

**Chiamante (client-web)**: `RagApiService.query(question, sessionId)` → `QueryView`

```
POST /api/rag/ask
Content-Type: application/json

{
  "query": "Quali sono le sorelle protagoniste?",
  "topK": 5,
  "language": "it",
  "sessionId": "550e8400-...",
  "fileName": "Piccole donne.pdf"
}
```

**Campi request**:

| Campo | Tipo | Default | Descrizione |
|-------|------|---------|-------------|
| `query` | string | — | Domanda in linguaggio naturale (obbligatorio) |
| `topK` | int | 15 | Numero di chunk da recuperare |
| `language` | string | null | Lingua risposta (es. `"it"`, `"en"`) |
| `sessionId` | string | null | ID sessione per cronologia multi-turno |
| `fileName` | string | null | Filtro su documento specifico |
| `documentId` | string | null | Filtro su documentId specifico |
| `metadataFilter` | map | null | Filtri arbitrari sui metadati ES |

---

**Passi interni della pipeline RAG** (`RagService.ask`):

```
1. Carica storico conversazionale dalla sessione (ConversationSessionService)
2. Costruisce filtri metadati dalla request
3. HybridSearchService.search(query, topK, filter)
   ├─ BM25 full-text (ES multiMatch su content^3, chapterTitle^2, fileName)
   └─ kNN vettoriale (EmbeddingProvider.embed(query) → ES kNN su content_embedding)
   → RRF merge (k=60): score(d) = Σ 1/(60 + rank_i(d))
   → Position boost se query contiene segnali di "finale/morte/conclusione"
   → Second-stage reranking (RRF score 65% + term overlap 25% + title match 10%)
4. Valuta evidenza: sufficient() = bestScore >= 0.15 AND fonti >= 2
   └─ Se insufficiente → expand query e retry una volta
   └─ Se ancora insufficiente → restituisce risposta "insufficiente" senza LLM
5. Costruisce il context (chunk formattati con [FONTE N]) entro max 6000 token
6. Costruisce i messaggi: system prompt + storico + userMessage(query + context)
7. LlmProvider.complete(messages) → risposta grezza
8. Parsing risposta: estrae answer, followUpQuestions, needsClarification
9. Grounding check: se nessuna citazione [FONTE N] e require-citations=true → appende fonti automaticamente
10. Aggiorna sessione con nuovo turno (sliding window max 10 turni)
```

**Risposta (200 OK)**:
```json
{
  "answer": "Le sorelle protagoniste sono Meg, Jo, Beth e Amy March. [FONTE 1]",
  "sources": [
    {
      "documentId": "uuid",
      "fileName": "Piccole donne.pdf",
      "chapterTitle": "Capitolo 1",
      "chapterIndex": 0,
      "chunkIndex": 3,
      "content": "Le quattro sorelle March...",
      "relevanceScore": 0.87
    }
  ],
  "llmModel": "openrouter/mistral-7b",
  "embeddingModel": "nomic-embed-text",
  "query": "Quali sono le sorelle protagoniste?",
  "processingTimeMs": 1240,
  "followUpQuestions": [
    "Qual è il ruolo di Jo nella storia?",
    "Come si sviluppa il rapporto tra le sorelle?",
    "Qual è il destino di Beth?"
  ],
  "needsClarification": false,
  "sessionId": "550e8400-..."
}
```

---

### `POST /api/rag/search`

**Scopo**: retrieval ibrido senza generazione LLM — restituisce i chunk grezzi. Utile per debug o UI con visualizzazione diretta dei risultati.

**Chiamante (client-web)**: non usato direttamente dalla UI principale; disponibile per testing.

```
POST /api/rag/search
Content-Type: application/json

{
  "query": "sorelle che crescono insieme",
  "topK": 10,
  "metadataFilter": { "fileName": "Piccole donne.pdf" }
}
```

**Passi interni**: `HybridSearchService.search(query, topK, filter)` (identico allo step 3 della pipeline RAG, senza LLM).

**Risposta**: array di `SearchResult`
```json
[
  {
    "documentId": "uuid",
    "fileName": "Piccole donne.pdf",
    "chunkIndex": 3,
    "chapterTitle": "Capitolo 1",
    "chapterIndex": 0,
    "content": "Le quattro sorelle...",
    "score": 0.87,
    "highlights": [],
    "language": null,
    "metadata": {}
  }
]
```

---

### `GET /api/rag/documents`

**Scopo**: lista tutti i fileName distinti presenti nell'indice semantico.

**Chiamante (client-web)**: `RagApiService.getDocumentList()` → autocomplete/dropdown nella `QueryView`

```
GET /api/rag/documents
```

**Passi interni**: `HybridSearchService.listDocuments()` → aggregazione `terms` su `fileName.keyword` in Elasticsearch.

**Risposta**:
```json
["ventimila-leghe.pdf", "Piccole donne.pdf", "Zanna Bianca (1).pdf"]
```

---

### `GET /api/rag/health`

**Scopo**: health check della pipeline RAG (solo verifica Spring Boot, non LLM/ES).

**Chiamante (client-web)**: `RagApiService.getQueryHealth()` → `StatusView`

```
GET /api/rag/health
```

**Risposta**: `{ "status": "UP", "service": "RAG Pipeline" }`

---

## 6. Ricerca full-text legacy

> Ricerca BM25 pura su Elasticsearch senza embedding vettoriali.

### `POST /api/search/simple`

```
POST /api/search/simple
Content-Type: application/json

{
  "query": "sorelle March",
  "language": "it",
  "size": 10
}
```

**Passi interni**: `ElasticsearchSearchService.search(query, language, size)` → query `multi_match` con analyzer corrispondente alla lingua.

---

### `POST /api/search/advanced`

```
POST /api/search/advanced
Content-Type: application/json

{
  "query": "sorelle March",
  "language": "it",
  "size": 10,
  "explain": true,
  "metadataFilters": { "fileName": "Piccole donne.pdf" }
}
```

**Aggiunge rispetto a `/simple`**: filtri metadati e opzionalmente il campo `explanation` con il punteggio BM25 dettagliato.

---

### `POST /api/search/by-metadata`

```
POST /api/search/by-metadata
Content-Type: application/json

{
  "metadataFilters": { "fileName": "Piccole donne.pdf" },
  "size": 20
}
```

Ricerca filtrata esclusivamente per metadati, senza query testuale.

---

### `GET /api/search/quick`

```
GET /api/search/quick?q=sorelle+March&lang=it&size=10
```

Equivalente a `/api/search/advanced` via query string per integrazione rapida.

---

## 7. Estrazione testo grezza

### `POST /api/documents/extract`

**Scopo**: estrae testo e metadati da un file senza indicizzarlo. Utile per anteprima o pipeline personalizzate.

```
POST /api/documents/extract
Content-Type: multipart/form-data

file: <binario>
```

**Passi interni**: `DocumentExtractionService.extractTextAndMetadata(file)` → Tika + PDFBox.

**Risposta**: `DocumentExtractionResult`
```json
{
  "fileName": "documento.pdf",
  "text": "Testo completo estratto...",
  "metadata": { "Author": "Mario Rossi", "dc:title": "Rapporto" },
  "chapters": [
    { "title": "Introduzione", "content": "...", "chapterIndex": 0 }
  ]
}
```

---

## 8. Indicizzazione da JSON

### `POST /api/index/from-json`

**Scopo**: indicizza un documento da un file JSON già presente nella cartella `extracted-documents/`.

```
POST /api/index/from-json?jsonFile=documento.json
```

**Passi interni**: legge il JSON, genera un UUID come `documentId`, chiama `ElasticsearchIndexService.indexDocument(documentId, result)`.

---

### `POST /api/index/from-extraction`

**Scopo**: indicizza un `DocumentExtractionResult` inviato direttamente nel body.

```
POST /api/index/from-extraction
Content-Type: application/json

{ "fileName": "...", "text": "...", "chapters": [...] }
```

---

## 9. Sessioni conversazionali

Le sessioni server-side mantengono la cronologia dei turni con una sliding window (default: 10 turni, TTL: 60 minuti).

### `POST /api/rag/session`

**Scopo**: crea una nuova sessione conversazionale vuota.

**Chiamante (client-web)**: `RagApiService.createSession()` — invocato all'apertura della `QueryView`.

```
POST /api/rag/session
```

**Risposta**:
```json
{ "sessionId": "550e8400-e29b-41d4-a716-446655440000" }
```

Il `sessionId` va incluso in ogni successiva richiesta a `/api/rag/ask` per mantenere il contesto multi-turno.

---

### `DELETE /api/rag/session/{sessionId}`

**Scopo**: elimina esplicitamente una sessione (es. utente chiude la chat).

**Chiamante (client-web)**: `RagApiService.deleteSession(sessionId)` — invocato alla chiusura della `QueryView`.

```
DELETE /api/rag/session/{sessionId}
```

**Risposta**: `204 No Content`

---

## 10. Flussi interni

### Flusso completo di indicizzazione Docling

```
client-web                my-app                   Docling :8001       Elasticsearch :9200
     │                       │                           │                      │
     │  POST /docling/index   │                           │                      │
     │──────────────────────►│                           │                      │
     │                       │  POST /parse/async        │                      │
     │                       │──────────────────────────►│                      │
     │                       │  { pythonJobId }          │                      │
     │                       │◄──────────────────────────│                      │
     │  { jobId, QUEUED }     │                           │                      │
     │◄──────────────────────│                           │                      │
     │                       │  ┌─ background thread ─┐  │                      │
     │  GET /docling/jobs/id  │  │ poll ogni 5s        │  │                      │
     │──────────────────────►│  │ GET /jobs/{pythonId}│  │                      │
     │  { status: PARSING }  │  │──────────────────────►│                      │
     │◄──────────────────────│  │  { status: DONE }   │  │                      │
     │                       │  │◄─────────────────────  │                      │
     │  GET /docling/jobs/id  │  │ convertResult()     │  │                      │
     │──────────────────────►│  │ EmbeddingProvider   │  │                      │
     │  { status: DONE }     │  │ embedBatch()        │  │                      │
     │◄──────────────────────│  │ ES.bulk()           │──────────────────────►│
     │                       │  └────────────────────-┘  │                      │
     │                       │                           │                      │
     │  GET /documents/parsed │                           │                      │
     │──────────────────────►│ (stato: TRANSCRIBED)      │                      │
     │                       │                           │                      │
     │  PUT /{id}/sections    │                           │                      │
     │──────────────────────►│  (modifica sezioni)       │                      │
     │                       │                           │                      │
     │  POST /{id}/index      │                           │                      │
     │──────────────────────►│  SemanticIndexService     │                      │
     │  { INDEXING }         │──────────────────────────────────────────────────►│
     │                       │  (stato: INDEXED)         │                      │
```

---

### Flusso completo di query RAG

```
client-web            my-app (RagService)         EmbeddingProvider     Elasticsearch    LLM
     │                     │                            │                      │           │
     │  POST /rag/session  │                            │                      │           │
     │────────────────────►│ { sessionId }              │                      │           │
     │                     │                            │                      │           │
     │  POST /rag/ask      │                            │                      │           │
     │  { query, sessionId}│                            │                      │           │
     │────────────────────►│                            │                      │           │
     │                     │  embed(query)              │                      │           │
     │                     │───────────────────────────►│                      │           │
     │                     │  [0.12, -0.87, ...]        │                      │           │
     │                     │◄───────────────────────────│                      │           │
     │                     │                            │                      │           │
     │                     │  BM25 multiMatch ──────────────────────────────►  │           │
     │                     │  kNN query ────────────────────────────────────►  │           │
     │                     │  (parallelo)                                      │           │
     │                     │  top-N chunks ◄────────────────────────────────   │           │
     │                     │                            │                      │           │
     │                     │  RRF merge + reranking     │                      │           │
     │                     │  buildContext()            │                      │           │
     │                     │                            │                      │           │
     │                     │  llm.complete(messages) ───────────────────────────────────►  │
     │                     │  raw response ◄───────────────────────────────────────────── │
     │                     │                            │                      │           │
     │                     │  parseResponse()           │                      │           │
     │                     │  enforceGrounding()        │                      │           │
     │                     │  saveHistory(session)      │                      │           │
     │                     │                            │                      │           │
     │  { answer, sources, │                            │                      │           │
     │    followUpQ, ... } │                            │                      │           │
     │◄────────────────────│                            │                      │           │
```

---

### Parametri chiave dell'hybrid search

| Parametro | Default | Descrizione |
|-----------|---------|-------------|
| `bm25Weight` | 1.0 | Peso BM25 nella fusione RRF |
| `vectorWeight` | 1.0 | Peso kNN nella fusione RRF |
| `positionWeight` | 2.0 | Boost posizionale per query sul "finale" |
| `rerank.enabled` | true | Abilita il second-stage reranker |
| `rerank.window` | 40 | Candidati riesaminati dal reranker |
| `rerank.rrf-weight` | 0.65 | Peso score RRF nel reranker |
| `rerank.term-overlap-weight` | 0.25 | Peso overlap termini query/chunk |
| `rerank.title-match-weight` | 0.10 | Peso match su titolo capitolo |

### Parametri chiave della pipeline RAG

| Parametro | Default | Descrizione |
|-----------|---------|-------------|
| `rag.context.max-tokens` | 6000 | Token massimi nel context window |
| `rag.context.top-k` | 15 | Chunk massimi per richiesta |
| `rag.grounding.min-score` | 0.15 | Score minimo per risposta grounded |
| `rag.grounding.min-sources` | 2 | Fonti minime per risposta grounded |
| `rag.followup.questions-count` | 3 | Follow-up questions generate |
| `rag.conversation.max-turns` | 10 | Turni mantenuti per sessione |
| `rag.conversation.ttl-minutes` | 60 | TTL sessione per inattività |

---

---

## 11. Chunking e Embedding — Dettaglio interno

> Documentazione completa con flusso visuale, algoritmo di chunking e dettagli sui provider di embedding:
> **[CHUNKING_EMBEDDING.md](./CHUNKING_EMBEDDING.md)**

---

## Provider LLM e Embedding

I provider sono selezionabili via profilo Spring (`spring.profiles.active`):

| Profilo | LLM | Embedding |
|---------|-----|-----------|
| `openrouter` | OpenRouter API (qualsiasi modello) | Ollama `nomic-embed-text` |
| `openai` | OpenAI API (GPT-4 ecc.) | OpenAI `text-embedding-3-small` |
| `ollama` | Ollama locale | Ollama locale |

L'interfaccia `EmbeddingProvider` e `LlmProvider` rendono i provider intercambiabili senza modifiche al codice.
