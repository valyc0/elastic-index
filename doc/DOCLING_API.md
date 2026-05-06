# Docling Service API

Microservizio Python (FastAPI) per il parsing strutturato di documenti.
Avviato come container Docker su porta `8001`.

## Endpoints

| Metodo | Path | Descrizione |
|---|---|---|
| `GET` | `/health` | Health check |
| `GET` | `/openapi.json` | Schema OpenAPI |
| `POST` | `/parse` | Parsing sincrono — attende il risultato |
| `POST` | `/parse/async` | Parsing asincrono — restituisce un `jobId` |
| `GET` | `/jobs/{jobId}` | Stato e risultato di un job asincrono |
| `GET` | `/jobs` | Lista di tutti i job (senza risultato) |

Formati supportati: PDF, DOCX, DOC, HTML, PPTX, XLSX, Markdown, AsciiDoc.

---

## `POST /parse` — sincrono

Carica il file e attende la risposta completa. La connessione HTTP rimane aperta per tutta la durata del parsing (timeout default: 900s).

**Request:**
```bash
curl -X POST http://localhost:8001/parse \
  -F "file=@documento.pdf"
```

**Response `200 OK`:**
```json
{
  "file_name": "documento.pdf",
  "page_count": 42,
  "full_text": "Testo completo estratto...",
  "sections": [
    {
      "title": "Capitolo 1",
      "chapter_index": 0,
      "text": "Testo del capitolo...",
      "level": 1,
      "page_number": 3,
      "parent_chapter_title": null,
      "parent_chapter_index": null
    }
  ],
  "tables": [
    {
      "caption": null,
      "text_representation": "Col1  Col2\n val1  val2",
      "page_number": 10
    }
  ],
  "metadata": {
    "fileName": "documento.pdf",
    "title": "...",
    "author": "..."
  }
}
```

**Quando usarlo:** file piccoli/medi, integrazione sincrona, script di test.
Spring Boot lo usa tramite `DoclingClient.java` con timeout 900s.

---

## `POST /parse/async` — asincrono

Carica il file e riceve subito un `jobId`. Il parsing avviene in background.
Il client deve fare **polling** su `/jobs/{jobId}` per ottenere il risultato.

**Request:**
```bash
curl -X POST http://localhost:8001/parse/async \
  -F "file=@documento.pdf"
```

**Response `202 Accepted`:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "fileName": "documento.pdf"
}
```

**Polling — `GET /jobs/{jobId}`:**
```bash
curl http://localhost:8001/jobs/550e8400-e29b-41d4-a716-446655440000
```

**Ciclo di vita del job:**

```
QUEUED → PROCESSING → DONE
                    → ERROR
```

| Status | Significato |
|---|---|
| `QUEUED` | In attesa di un worker libero |
| `PROCESSING` | Parsing in corso |
| `DONE` | Completato — `result` presente nella risposta |
| `ERROR` | Fallito — `error` contiene il messaggio |

**Response `DONE`:**
```json
{
  "jobId": "550e8400-...",
  "fileName": "documento.pdf",
  "status": "DONE",
  "error": null,
  "result": { ...stessa struttura di /parse... }
}
```

**Quando usarlo:** file grandi, client che non possono tenere connessioni aperte a lungo.
I job vengono conservati in Redis con TTL di 5 ore.

---

## `GET /jobs` — lista job

```bash
curl http://localhost:8001/jobs
```

Restituisce tutti i job attivi/recenti senza includere il risultato (solo stato e metadati).

---

## Flusso interno

```
POST /parse (o /parse/async)
    │
    ├─ validazione file (formato, dimensione max configurabile)
    │
    ├─ semaforo asyncio  ← limita le conversioni concorrenti (DOCLING_MAX_CONCURRENT)
    │
    └─ ProcessPoolExecutor
           │
           └─ _convert_in_worker()   ← processo separato, aggira il GIL
                  │
                  ├─ DocumentConverter (Docling/IBM)
                  ├─ itera SectionHeaderItem, TextItem, TableItem
                  └─ restituisce dict serializzabile con sezioni, tabelle, full_text
```

I worker del pool vengono inizializzati **una sola volta** al primo avvio (`_worker_init`): i modelli Docling (~2-3 GB) vengono caricati in RAM una volta e riutilizzati per tutte le richieste successive.

---

## Configurazione (variabili d'ambiente)

| Variabile | Default | Descrizione |
|---|---|---|
| `DOCLING_THREADS` | tutti i core | Thread CPU per worker |
| `DOCLING_MAX_CONCURRENT` | `1` | Conversioni simultanee (ogni worker carica i modelli) |
| `DOCLING_TIMEOUT_SEC` | `900` | Timeout singola conversione |
| `DOCLING_MAX_FILE_MB` | `100` | Dimensione massima file |
| `DOCLING_DEVICE` | `AUTO` | Acceleratore: `CPU`, `CUDA`, `MPS`, `AUTO` |
| `REDIS_URL` | `redis://localhost:6379` | URL Redis per job store |

---

## Test diretto da shell

```bash
# Parsing completo con output dettagliato
./bin/test-docling-api.sh esempi/ventimila-leghe.pdf

# Solo riepilogo rapido (contatori, metadata)
./bin/test-docling-api.sh esempi/ventimila-leghe.pdf --summary

# URL personalizzato
DOCLING_URL=http://altro-host:8001 ./bin/test-docling-api.sh documento.pdf
```

Il JSON completo viene salvato in `/tmp/docling-parse-<nome>.json`.

---
