# Upload e Indicizzazione Asincrona

Questo documento descrive nel dettaglio come funziona il meccanismo di upload
asincrono dei documenti, i componenti coinvolti e il ciclo di vita di un job.

---

## Panoramica del flusso

```
Client HTTP
    │
    │  POST /api/docling/index (multipart)
    ▼
DoclingController  ──► 202 Accepted { jobId, status: QUEUED }
    │
    │  submitJob(file)
    ▼
DoclingJobService
    ├── submitParseAsync(file)  ──►  POST /parse/async  ──►  Python FastAPI
    │                               ◄──  { jobId: pythonJobId }  ◄──
    │
    ├── crea DoclingJobStatus(QUEUED)
    ├── jobs.put(javaJobId, job)
    └── executor.submit(processJob)  ──► thread daemon "docling-job-worker"

                                    Thread background
                                        │
                                        │  loop polling ogni 5s
                                        │  GET /jobs/{pythonJobId}  ──► Python
                                        │  ◄──  { status, result }  ◄──
                                        │
                                        │  DONE → converti risultato
                                        │       → indicizza su Elasticsearch
                                        │       → stato DONE
                                        │
                                        └── oppure ERROR / Timeout

Client HTTP
    │
    │  GET /api/docling/jobs/{javaJobId}  (polling periodico)
    ▼
DoclingController  ──► { status, chunks, sections, message }
```

---

## Componenti coinvolti

### 1. `DoclingController` — punto di ingresso HTTP

**File:** `my-app/src/main/java/io/bootify/my_app/rest/DoclingController.java`

| Endpoint | Metodo | Descrizione |
|---|---|---|
| `/api/docling/index` | `POST` | Avvia l'upload asincrono, risponde **202** con `jobId` |
| `/api/docling/jobs/{jobId}` | `GET` | Restituisce lo stato corrente del job |
| `/api/docling/jobs` | `GET` | Elenca tutti i job in memoria |

Il controller non blocca la richiesta HTTP: chiama `doclingJobService.submitJob(file)`
e risponde immediatamente. L'elaborazione avviene interamente in background.

```java
// POST /api/docling/index
String jobId = doclingJobService.submitJob(file);
return ResponseEntity.status(202).body(Map.of(
    "jobId", jobId,
    "status", "QUEUED",
    ...
));
```

---

### 2. `DoclingJobService` — orchestratore dei job

**File:** `my-app/src/main/java/io/bootify/my_app/service/DoclingJobService.java`

È il cuore dell'architettura async. Gestisce:
- un **`ConcurrentHashMap`** `jobs` con tutti i job in memoria (TTL 5 ore)
- un **`ExecutorService`** con thread pool cached (`docling-job-worker`)
- un **`ScheduledExecutorService`** (`docling-job-cleanup`) che rimuove i job scaduti ogni ora

#### `submitJob(file)`

```
1. Invia il file a Python via POST /parse/async  →  ottiene pythonJobId
2. Crea DoclingJobStatus con status=QUEUED e lo salva nella mappa
3. Lancia processJob() in un thread daemon
4. Restituisce il javaJobId al controller (e quindi al client)
```

Il passo 1 è bloccante ma rapido: Python risponde in pochi millisecondi
perché accetta solo il file e avvia l'elaborazione in background suo conto.

#### `processJob(javaJobId, pythonJobId, fileName)` — thread worker

```
1. Thread.interrupted()  →  pulisce eventuali flag stale (bug-fix interrupt)
2. jobs.get(javaJobId)   →  recupera il job dalla mappa
3. status = PARSING
4. Loop di polling (max 360 tentativi × 5s = 30 minuti):
     a. Thread.sleep(5000)
     b. GET /jobs/{pythonJobId}  →  DoclingPythonJobStatus
     c. se DONE   → converti risultato, esci dal loop
     d. se ERROR  → markError, return
     e. altrimenti (QUEUED/PROCESSING) → continua
5. status = INDEXING
6. semanticIndexService.indexDocument(documentId, extracted)
7. status = DONE  →  chunks, sections, message
```

In caso di eccezione non catturata, un secondo `Future.get()` monitor
cattura e logga l'errore e forza lo stato a ERROR (evita job bloccati su QUEUED).

---

### 3. `DoclingClient` — client HTTP verso Python

**File:** `my-app/src/main/java/io/bootify/my_app/service/DoclingClient.java`

Usa `java.net.http.HttpClient` con HTTP/1.1 forzato (compatibilità uvicorn multipart).

| Metodo | Chiamata HTTP | Timeout |
|---|---|---|
| `submitParseAsync(file)` | `POST /parse/async` | 30s |
| `getPythonJobStatus(id)` | `GET /jobs/{id}` | 10s |
| `convertResult(parsed, fileName)` | — | wrapper di `toExtractionResult()` |

`submitParseAsync` invia il file come `multipart/form-data` e restituisce il `pythonJobId`
estratto dalla risposta JSON `{ jobId, status, fileName }`.

`getPythonJobStatus` deserializza la risposta in `DoclingPythonJobStatus`:
quando lo stato Python è `DONE`, la risposta include anche il campo `result`
con l'intera struttura estratta (sezioni, tabelle, metadati).

---

### 4. Python FastAPI — `docling-service/main.py`

Il servizio Python gestisce a sua volta uno **store interno di job** (`_jobs` dict in memoria).

#### `POST /parse/async`

```python
job_id = str(uuid.uuid4())
_jobs[job_id] = { "status": "QUEUED", "fileName": filename, ... }
asyncio.create_task(_process_document_async(job_id, ...))  # fire-and-forget
return { "jobId": job_id, "status": "QUEUED", "fileName": filename }  # 202
```

Risponde in meno di 1ms. L'elaborazione reale parte nel task asyncio.

#### `_process_document_async(job_id, ...)`

```python
_jobs[job_id]["status"] = "PROCESSING"
async with _semaphore:                       # max _MAX_CONCURRENT conversioni
    raw = await asyncio.wait_for(
        loop.run_in_executor(_worker_pool, _convert_in_worker, tmp_path),
        timeout=_TIMEOUT_SEC,                # 900s su CPU
    )
_jobs[job_id]["status"] = "DONE"
_jobs[job_id]["result"] = result.model_dump()
```

Il `ProcessPoolExecutor` aggira il GIL: ogni worker ha il proprio converter Docling
(modelli caricati una volta sola in `_worker_init`).

#### `GET /jobs/{job_id}`

Restituisce lo stato corrente. Quando `status == "DONE"`, la risposta include
il campo `result` con sezioni, tabelle, full_text e metadati.

---

## Ciclo di vita di un job

```
              Java                              Python
              ─────                             ──────
POST /index   QUEUED ──── POST /parse/async ──► QUEUED
              │                                 │
[background]  PARSING ◄── polling GET /jobs ──  PROCESSING
              │                 (ogni 5s)        │
              │                                  │
              │           ◄── status: DONE ────  DONE (result incluso)
              │
              INDEXING  (scrittura su Elasticsearch)
              │
              DONE  { chunks: N, sections: M }
```

### Tabella degli stati

| Stato Java | Significato |
|---|---|
| `QUEUED` | File inviato a Python, thread worker non ancora entrato nel loop |
| `PARSING` | Thread worker attivo, polling Python in corso |
| `INDEXING` | Python ha finito, chunking + embedding + scrittura su ES in corso |
| `DONE` | Documento completamente indicizzato e ricercabile |
| `ERROR` | Fallimento in uno qualsiasi degli stadi (messaggio in `error`) |

| Stato Python | Significato |
|---|---|
| `QUEUED` | Task asyncio creato, in attesa del semaforo |
| `PROCESSING` | Dentro `_process_document_async`, Docling sta elaborando |
| `DONE` | Risultato pronto nel campo `result` |
| `ERROR` | Timeout o eccezione durante la conversione |

---

## Configurazione e limiti

| Parametro | Valore attuale | Dove |
|---|---|---|
| Polling interval Java | 5s | `DoclingJobService.POLL_INTERVAL_MS` |
| Max tentativi polling | 360 (= 30 min) | `DoclingJobService.MAX_POLL_ATTEMPTS` |
| TTL job in memoria Java | 5 ore | `DoclingJobService.JOB_TTL_MS` |
| Timeout submission Python | 30s | `DoclingClient.submitParseAsync` |
| Timeout polling singolo | 10s | `DoclingClient.getPythonJobStatus` |
| Timeout conversione Python | 900s | `DOCLING_TIMEOUT_SEC` in docker-compose |
| Max conversioni concorrenti | 1 | `DOCLING_MAX_CONCURRENT` in docker-compose |
| TTL job in memoria Python | 5 ore | `_JOB_TTL_HOURS` in main.py |

---

## Bug risolti durante l'implementazione

### Bug 1 — Interrupt flag stale su thread riutilizzato

**Scenario:** il thread worker precedente aveva chiamato `Thread.currentThread().interrupt()`
a causa di una `InterruptedException` wrappata in `DoclingException`. Quando il thread
veniva riutilizzato dal pool cached per un job successivo, il flag era già settato.
La prima `Thread.sleep(5000)` lanciava immediatamente `InterruptedException`,
il job entrava nello stato `ERROR` o (per un problema di visibilità della memoria)
restava bloccato su `QUEUED` senza mai loggare nulla.

**Fix:** prima riga di `processJob()`:
```java
boolean wasInterrupted = Thread.interrupted(); // legge E azzera il flag
```

### Bug 2 — Eccezioni silenziate dall'ExecutorService

**Scenario:** `executor.submit(runnable)` inghiotte le eccezioni non catturate:
il thread moriva silenziosamente e il job restava su `QUEUED` per sempre.

**Fix:** un secondo task monitora il `Future` del worker:
```java
Future<?> future = executor.submit(() -> processJob(...));
executor.submit(() -> {
    try { future.get(); }
    catch (Exception ex) {
        log.error("Eccezione non catturata nel job {}: {}", javaJobId, ex.getMessage(), ex);
        // forza ERROR se ancora QUEUED
    }
});
```

### Bug 3 — Timeout 300s insufficiente per PDF grandi su CPU

**Scenario:** PDF da 5MB supera i 300s di elaborazione Docling su CPU.

**Fix:** `DOCLING_TIMEOUT_SEC=900` in `docker-compose.yml`.

---

## Esempio di utilizzo via curl

```bash
# 1. Upload asincrono
RESP=$(curl -s -X POST http://localhost:8080/api/docling/index \
  -F "file=@documento.pdf")
JOB_ID=$(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
echo "Job: $JOB_ID"

# 2. Polling dello stato
while true; do
  STATUS=$(curl -s "http://localhost:8080/api/docling/jobs/$JOB_ID")
  echo "$STATUS" | python3 -m json.tool
  echo "$STATUS" | grep -q '"status":"DONE"\|"status":"ERROR"' && break
  sleep 5
done
```
