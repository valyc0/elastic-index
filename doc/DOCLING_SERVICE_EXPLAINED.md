# Come funziona il Docling Service

> Guida dettagliata per chi non conosce Python.

---

## Cos'è

`docling-service` è un **microservizio** scritto in Python che fa una cosa sola: riceve un file (PDF, Word, HTML, PowerPoint…), lo "smonta" pezzo per pezzo estraendo titoli, paragrafi e tabelle, e restituisce il tutto come dati strutturati in formato JSON.

È costruito come un piccolo server web autonomo, che gira nella propria scatola Docker sulla porta **8001** e parla con il resto del sistema tramite chiamate HTTP.

---

## I pezzi che lo compongono

| File | Ruolo |
|------|-------|
| `main.py` | Il cuore del servizio: tutta la logica |
| `requirements.txt` | Le librerie Python che usa |
| `Dockerfile` | La ricetta per creare il container Docker |

---

## Le librerie usate (`requirements.txt`)

Prima di capire il codice è utile sapere di cosa si serve:

| Libreria | A cosa serve |
|----------|-------------|
| **docling** | Libreria IBM per estrarre testo e struttura da documenti |
| **fastapi** | Framework per creare API web in Python in modo semplice |
| **uvicorn** | Il "motore" che fa girare il server web |
| **pydantic** | Definisce la forma esatta dei dati in entrata e in uscita |
| **pandas** | Manipola tabelle (usato per esportare le tabelle estratte) |
| **redis** | Collega il servizio a Redis per salvare lo stato dei job asincroni |
| **python-multipart** | Permette di ricevere file via HTTP |

---

## Il Dockerfile: come viene "confezionato"

```dockerfile
FROM python:3.11-slim          # Parte da una immagine Linux minimale con Python 3.11

WORKDIR /app                   # Imposta /app come cartella di lavoro

RUN apt-get install ...        # Installa librerie di sistema necessarie a Docling
RUN pip install -r requirements.txt  # Installa tutte le librerie Python

# Trucco importante: scarica i modelli AI di Docling durante il BUILD
# così al primo avvio non perde tempo a scaricarli (~2-3 GB)
RUN python -c "from docling.document_converter import DocumentConverter; DocumentConverter()"

COPY main.py .                 # Copia il codice

EXPOSE 8001                    # Dichiara che il servizio ascolta sulla porta 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]  # Avvia il server
```

---

## Il codice Python spiegato passo per passo

### 1. Configurazione tramite variabili d'ambiente

```python
_NUM_THREADS    = int(os.environ.get("DOCLING_THREADS", os.cpu_count() or 4))
_MAX_CONCURRENT = int(os.environ.get("DOCLING_MAX_CONCURRENT", 2))
_TIMEOUT_SEC    = int(os.environ.get("DOCLING_TIMEOUT_SEC", 300))
_MAX_FILE_MB    = int(os.environ.get("DOCLING_MAX_FILE_MB", 100))
_DEVICE_STR     = os.environ.get("DOCLING_DEVICE", "AUTO").upper()
```

**Cosa fa:** Legge le impostazioni dall'ambiente Docker (dal `docker-compose.yml`).  
**Perché:** Così si può cambiare il comportamento del servizio senza modificare il codice — basta cambiare un valore nel docker-compose.

| Variabile | Significato pratico |
|-----------|---------------------|
| `DOCLING_THREADS` | Quanti "fili" CPU usare — più è alto, più è veloce su CPU |
| `DOCLING_MAX_CONCURRENT` | Quanti documenti elaborare in parallelo (ogni worker usa ~2-3 GB di RAM) |
| `DOCLING_TIMEOUT_SEC` | Dopo quanti secondi abbandonare un documento troppo lento |
| `DOCLING_MAX_FILE_MB` | Dimensione massima del file accettato |
| `DOCLING_DEVICE` | Usa la CPU, la GPU NVIDIA (CUDA), o la GPU Apple (MPS) |

---

### 2. Il job store: Redis con fallback in-memory

```python
_REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379")
_JOB_TTL_SECONDS = 5 * 3600  # 5 ore
_redis_client: Optional[redis.Redis] = None
_jobs_fallback: dict = {}
```

**Cosa fa:** I job asincroni (i documenti che si elaborano in background) devono essere "ricordati" da qualche parte. Redis è un database ultra-veloce in memoria usato come archivio temporaneo.

**Come funziona il fallback:** Se Redis non è disponibile, il servizio usa un semplice dizionario Python in memoria. È meno robusto (i dati si perdono al riavvio) ma il servizio non si blocca.

Le tre funzioni di supporto:
- `_job_set(id, dati)` → salva o aggiorna un job
- `_job_get(id)` → recupera un job per ID
- `_job_list()` → elenca tutti i job attivi

I job vengono cancellati automaticamente da Redis dopo **5 ore** (TTL = Time To Live).

---

### 3. Il converter Docling e il pool di processi

```python
def _make_converter() -> DocumentConverter:
    opts = PdfPipelineOptions()
    opts.do_ocr = False
    opts.do_table_structure = True
    ...
    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)}
    )
```

**Cosa fa:** Crea un oggetto `DocumentConverter` di Docling, configurato per:
- **Non fare OCR** (riconosce testo "nativo" dal PDF, non immagini scansionate)
- **Riconoscere le tabelle** in modo strutturato
- **Non generare immagini** delle pagine (riduce memoria e tempo)

```python
_worker_pool: ProcessPoolExecutor
```

**Perché un pool di processi separati?**  
Python ha un limite chiamato **GIL** (Global Interpreter Lock) che impedisce a più thread di fare calcoli CPU in parallelo. Per aggirarlo, il servizio lancia dei **processi separati** (non thread). Ogni processo ha il proprio spazio di memoria e il proprio converter Docling già pronto.

```python
def _worker_init():
    global _worker_converter
    _worker_converter = _make_converter()
```

Questa funzione viene eseguita **una sola volta per ogni processo worker** all'avvio. I modelli AI di Docling (pesanti ~2-3 GB) vengono caricati in RAM una sola volta e riutilizzati per tutte le richieste successive — questo è fondamentale per le prestazioni.

---

### 4. La funzione di conversione: `_convert_in_worker`

Questa è la parte più importante: gira nei processi worker e fa il vero lavoro.

```python
def _convert_in_worker(tmp_path: str) -> dict:
    result = _worker_converter.convert(tmp_path)
    doc = result.document
```

**Passo 1:** Docling converte il file e restituisce un oggetto `document` che rappresenta il documento in forma strutturata.

```python
    for item, _ in doc.iterate_items():
        if isinstance(item, SectionHeaderItem):
            # È un titolo (H1, H2, H3...)
        elif isinstance(item, TextItem):
            # È un paragrafo di testo
        elif isinstance(item, TableItem):
            # È una tabella
```

**Passo 2:** Scorre tutti gli elementi del documento uno ad uno. Ogni elemento è "tipizzato" — Docling sa già se è un titolo, un paragrafo o una tabella.

**Logica per i titoli (SectionHeaderItem):**
- Se è un titolo di **livello 1** (H1), diventa un "capitolo" principale e viene ricordato come contesto
- Se è un titolo di **livello 2+** (H2, H3…), viene collegato al capitolo H1 genitore tramite `parent_chapter_title` e `parent_chapter_index`
- Ogni titolo incrementa `chapter_index` per mantenere l'ordine

**Logica per i paragrafi (TextItem):**
- Il testo del paragrafo viene **accodato** all'ultima sezione trovata
- Se non ci sono sezioni ancora, crea una sezione "anonima" (livello 0)

**Logica per le tabelle (TableItem):**
- Tenta di esportare la tabella come DataFrame pandas (formato tabellare)
- Se fallisce, usa l'esportazione Markdown come fallback
- Salva la rappresentazione testuale della tabella

```python
    sections_raw = [s for s in sections_raw if len(s["text"].strip()) >= 10]
```

**Passo 3 (pulizia):** Scarta le sezioni con meno di 10 caratteri di testo — evita sezioni vuote o quasi vuote.

```python
    return dict(
        full_text=doc.export_to_text(),  # tutto il testo del documento
        sections=sections_raw,           # lista sezioni con gerarchia
        tables=tables_raw,               # lista tabelle
        metadata=metadata,               # autore, titolo, ecc.
        page_count=page_count,           # numero di pagine
    )
```

**Passo 4:** Restituisce un dizionario Python semplice (serializzabile) — non oggetti Docling che non si possono trasferire tra processi.

---

### 5. Il server FastAPI e il ciclo di vita

```python
app = FastAPI(title="Docling Parsing Service", version="1.0.0")

@app.on_event("startup")
async def startup():
    global _semaphore, _worker_pool, _redis_client
    _semaphore = asyncio.Semaphore(_MAX_CONCURRENT)
    _worker_pool = ProcessPoolExecutor(max_workers=_MAX_CONCURRENT, initializer=_worker_init)
    ...
```

**All'avvio del server:**
1. Crea un **semaforo** (un contatore che limita l'accesso concorrente) — impedisce che troppe richieste girino in parallelo
2. Avvia il **pool di processi worker**, che a loro volta caricano i modelli Docling
3. Prova a connettersi a **Redis**; se non riesce, usa il fallback in-memory

```python
@app.on_event("shutdown")
async def shutdown():
    _worker_pool.shutdown(wait=False)
```

**Allo spegnimento:** Chiude ordinatamente il pool di processi.

---

### 6. I modelli di dati (Pydantic)

```python
class ParsedSection(BaseModel):
    title: str
    chapter_index: int
    text: str
    level: int
    page_number: Optional[int] = None
    parent_chapter_title: Optional[str] = None
    parent_chapter_index: Optional[int] = None
```

**Cosa fa Pydantic:** Definisce la "forma" esatta dei dati JSON in uscita. FastAPI usa questi modelli per:
- **Validare** automaticamente che i dati siano corretti
- **Documentare** automaticamente l'API (OpenAPI/Swagger)
- **Serializzare** gli oggetti Python in JSON

`Optional[int]` significa che il campo può essere un numero intero oppure `null` (assente).

---

## Le API disponibili

Il servizio espone sei endpoint HTTP.

---

### `GET /health` — verifica che il servizio sia vivo

**Richiesta:**
```bash
curl http://localhost:8001/health
```

**Risposta:**
```json
{ "status": "UP", "service": "docling-service" }
```

Usato da Docker per il **healthcheck** — se non risponde, Docker sa che il container è in stato anomalo.

---

### `POST /parse` — parsing sincrono

Il client carica un file e **aspetta** finché il parsing è completato. La connessione HTTP rimane aperta per tutta la durata (può richiedere minuti su file grandi).

**Come funziona internamente:**

```
Client → POST /parse (con file allegato)
              │
              ▼
        1. Validazione: formato supportato? dimensione OK?
              │
              ▼
        2. Salva il file in una cartella temporanea (/tmp)
              │
              ▼
        3. Acquisisce il semaforo (aspetta se ci sono già _MAX_CONCURRENT in corso)
              │
              ▼
        4. Lancia _convert_in_worker nel pool di processi
           (con timeout: se supera DOCLING_TIMEOUT_SEC → errore 504)
              │
              ▼
        5. Riceve il dict risultante e lo trasforma in ParseResponse
              │
              ▼
        6. Cancella il file temporaneo
              │
              ▼
Client ← 200 OK + JSON completo
```

**Richiesta:**
```bash
curl -X POST http://localhost:8001/parse \
  -F "file=@mio-documento.pdf"
```

**Risposta (`200 OK`):**
```json
{
  "file_name": "mio-documento.pdf",
  "page_count": 42,
  "full_text": "Tutto il testo estratto...",
  "sections": [
    {
      "title": "Introduzione",
      "chapter_index": 0,
      "text": "Questo documento tratta di...",
      "level": 1,
      "page_number": 1,
      "parent_chapter_title": null,
      "parent_chapter_index": null
    },
    {
      "title": "1.1 Sottosezione",
      "chapter_index": 1,
      "text": "Contenuto della sottosezione...",
      "level": 2,
      "page_number": 3,
      "parent_chapter_title": "Introduzione",
      "parent_chapter_index": 0
    }
  ],
  "tables": [
    {
      "caption": null,
      "text_representation": "Colonna1  Colonna2\n   val1     val2",
      "page_number": 10
    }
  ],
  "metadata": {
    "fileName": "mio-documento.pdf",
    "title": "Titolo del documento",
    "author": "Mario Rossi"
  }
}
```

**Errori possibili:**
| Codice HTTP | Causa |
|-------------|-------|
| `400` | Nome file mancante |
| `413` | File troppo grande (supera `DOCLING_MAX_FILE_MB`) |
| `415` | Formato non supportato |
| `504` | Timeout superato (`DOCLING_TIMEOUT_SEC`) |
| `500` | Errore interno durante il parsing |

---

### `POST /parse/async` — parsing asincrono

Il client carica il file e riceve **subito** un `jobId`, senza aspettare. Il parsing avviene in background. Il client deve fare **polling** (interrogare periodicamente) per sapere quando è finito.

**Come funziona internamente:**

```
Client → POST /parse/async (con file allegato)
              │
              ▼
        1. Validazione (stessa di /parse)
              │
              ▼
        2. Genera un UUID univoco come jobId
              │
              ▼
        3. Salva il job in Redis con status="QUEUED"
              │
              ▼
        4. Lancia _process_document_async in background (asyncio.create_task)
              │
              ▼
Client ← 202 Accepted + { jobId, status: "QUEUED", fileName }
              │
              │   (in background, intanto...)
              ▼
        5. Aggiorna status → "PROCESSING"
        6. Esegue la conversione (stessa di /parse)
        7. Aggiorna status → "DONE" (o "ERROR") + salva result in Redis
```

**Richiesta:**
```bash
curl -X POST http://localhost:8001/parse/async \
  -F "file=@mio-documento.pdf"
```

**Risposta immediata (`202 Accepted`):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "fileName": "mio-documento.pdf"
}
```

---

### `GET /jobs/{jobId}` — stato di un job asincrono

Interroga lo stato di un job lanciato con `/parse/async`.

**Ciclo di vita di un job:**
```
QUEUED → PROCESSING → DONE
                    → ERROR
```

**Richiesta:**
```bash
curl http://localhost:8001/jobs/550e8400-e29b-41d4-a716-446655440000
```

**Risposta quando è in corso:**
```json
{
  "jobId": "550e8400-...",
  "fileName": "mio-documento.pdf",
  "status": "PROCESSING",
  "error": null,
  "result": null
}
```

**Risposta quando è completato (`status: "DONE"`):**
```json
{
  "jobId": "550e8400-...",
  "fileName": "mio-documento.pdf",
  "status": "DONE",
  "error": null,
  "result": {
    "file_name": "mio-documento.pdf",
    "page_count": 42,
    "full_text": "...",
    "sections": [ ... ],
    "tables": [ ... ],
    "metadata": { ... }
  }
}
```

**Risposta in caso di errore:**
```json
{
  "jobId": "...",
  "status": "ERROR",
  "error": "Timeout: conversione superato 900s",
  "result": null
}
```

---

### `GET /jobs` — lista tutti i job

Restituisce tutti i job attivi/recenti, **senza** il risultato (solo stato e metadati).

```bash
curl http://localhost:8001/jobs
```

```json
[
  {
    "jobId": "550e8400-...",
    "fileName": "documento1.pdf",
    "status": "DONE",
    "error": null,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:35:42"
  },
  {
    "jobId": "661f9511-...",
    "fileName": "documento2.pdf",
    "status": "PROCESSING",
    "error": null,
    "createdAt": "2024-01-15T10:38:00",
    "updatedAt": "2024-01-15T10:38:05"
  }
]
```

---

### `GET /openapi.json` — documentazione automatica

FastAPI genera automaticamente la documentazione OpenAPI del servizio. È accessibile anche tramite interfaccia grafica a:
- `http://localhost:8001/docs` (Swagger UI)
- `http://localhost:8001/redoc` (ReDoc)

---

## Architettura completa del flusso

```
┌─────────────┐         ┌──────────────────────────────────────────┐
│   Client    │ HTTP    │            docling-service               │
│ (Spring     │────────▶│                                          │
│  Boot,      │         │  ┌─────────────────────────────────┐    │
│  curl, ...)  │         │  │         FastAPI (main thread)   │    │
└─────────────┘         │  │                                  │    │
                        │  │  POST /parse ──────────────────┐ │    │
                        │  │  POST /parse/async ────────────┤ │    │
                        │  │  GET  /jobs/{id} ──────────────┤ │    │
                        │  │  GET  /jobs ───────────────────┤ │    │
                        │  │  GET  /health ─────────────────┤ │    │
                        │  └──────────────┬─────────────────┘ │    │
                        │                 │ semaforo            │    │
                        │                 ▼ (max N concorrenti) │    │
                        │  ┌──────────────────────────────────┐│    │
                        │  │     ProcessPoolExecutor          ││    │
                        │  │                                  ││    │
                        │  │  Worker 1 ┌──────────────────┐  ││    │
                        │  │           │ DocumentConverter │  ││    │
                        │  │           │ (modelli in RAM)  │  ││    │
                        │  │           └──────────────────┘  ││    │
                        │  │  Worker 2 ┌──────────────────┐  ││    │
                        │  │           │ DocumentConverter │  ││    │
                        │  │           │ (modelli in RAM)  │  ││    │
                        │  │           └──────────────────┘  ││    │
                        │  └──────────────────────────────────┘│    │
                        │                                        │    │
                        │  ┌────────────────────────────────────┐    │
                        │  │  Redis (job store asincrono)       │    │
                        │  │  TTL: 5 ore per job                │    │
                        │  └────────────────────────────────────┘    │
                        └──────────────────────────────────────────┘
```

---

## Quando usare `/parse` vs `/parse/async`

| Situazione | Endpoint consigliato |
|-----------|----------------------|
| File piccoli (< 10 MB), risposta veloce attesa | `/parse` |
| Integrazione semplice, script bash, test | `/parse` |
| File grandi, PDF di centinaia di pagine | `/parse/async` |
| Client web che non può tenere la connessione aperta | `/parse/async` |
| Pipeline batch di molti documenti | `/parse/async` |

---

## Formati supportati

| Estensione | Formato |
|-----------|---------|
| `.pdf` | PDF |
| `.docx`, `.doc` | Microsoft Word |
| `.pptx` | Microsoft PowerPoint |
| `.xlsx` | Microsoft Excel |
| `.html`, `.htm` | HTML |
| `.md` | Markdown |
| `.adoc` | AsciiDoc |

---

## Considerazioni sulle risorse

- Ogni **worker** carica i modelli Docling in RAM: circa **2-3 GB per worker**
- Con `DOCLING_MAX_CONCURRENT=1` (default): minimo 2-3 GB RAM
- Con `DOCLING_MAX_CONCURRENT=2`: minimo 4-6 GB RAM
- Il limite di memoria nel docker-compose è impostato a **8 GB**
- Su macchine con GPU, impostare `DOCLING_DEVICE=CUDA` o `DOCLING_DEVICE=MPS` accelera il parsing significativamente
- I modelli vengono scaricati al **build time** del container (non al primo avvio), per evitare attese al deployment
