# Redis Job Store nel Docling Service

## Il problema: job store in-memory

Prima di questa modifica, il `docling-service` teneva traccia dei job asincroni in un semplice dizionario Python:

```python
_jobs: dict = {}
```

Questo dict viveva **dentro il processo uvicorn**. Funzionava perfettamente con una singola istanza, ma aveva tre limiti strutturali importanti.

### Limite 1 — nessuna scalabilità orizzontale

Se venivano avviate più istanze del servizio (per reggere più richieste in parallelo), ogni istanza aveva il suo `_jobs` isolato. Un client che inviava il file all'istanza A e poi interrogava lo stato sull'istanza B riceveva **404**, perché B non sapeva nulla del job.

```
Client → istanza A: POST /parse/async  →  job "abc" salvato in A._jobs
Client → istanza B: GET  /jobs/abc     →  404! B non sa nulla di "abc"
```

### Limite 2 — dati persi al riavvio

Se il container veniva riavviato (crash, deploy, aggiornamento), tutti i job in memoria sparivano. Un client che stava aspettando il completamento di un job lungo non avrebbe più potuto recuperare il risultato.

### Limite 3 — cleanup manuale con possibile memory leak

Il cleanup dei job scaduti era affidato a un task `asyncio` con un loop `while True`. Se questo task si bloccava per un'eccezione non gestita, smetteva silenziosamente di girare e i job vecchi rimanevano in memoria per sempre, consumando RAM.

---

## La soluzione: Redis come job store condiviso

Redis è un database **in-memory**, ultra-veloce, che gira come processo separato e accessibile da tutte le istanze del servizio contemporaneamente.

### Cosa cambia

| | Prima | Dopo |
|---|---|---|
| Storage | `dict` Python locale al processo | Redis (processo separato) |
| Scalabilità | solo single-instance | multi-istanza |
| Sopravvive al riavvio | no | sì (con `--save`) |
| TTL automatico | task asyncio manuale | `SETEX` di Redis (nativo) |
| Fallback se Redis giù | — | `dict` in-memory automatico |

### Come funziona ora

Il job store è astratto in tre funzioni helper:

```python
def _job_set(job_id: str, data: dict) -> None:
    """Salva il job in Redis con TTL di 5 ore. Fallback a dict."""
    r = _get_redis()
    if r is not None:
        r.setex(f"job:{job_id}", _JOB_TTL_SECONDS, json.dumps(data, default=str))
    else:
        _jobs_fallback[job_id] = data

def _job_get(job_id: str) -> Optional[dict]:
    """Legge il job da Redis. Fallback a dict."""
    r = _get_redis()
    if r is not None:
        raw = r.get(f"job:{job_id}")
        return json.loads(raw) if raw else None
    return _jobs_fallback.get(job_id)

def _job_list() -> list[dict]:
    """Lista tutti i job. Fallback a dict."""
    r = _get_redis()
    if r is not None:
        keys = r.keys("job:*")
        return [json.loads(r.get(k)) for k in keys if r.get(k)]
    return list(_jobs_fallback.values())
```

Il codice applicativo (`_process_document_async`, gli endpoint REST) non sa se sta parlando con Redis o con il dict — usa solo `_job_set` e `_job_get`.

### TTL automatico

Ogni job viene salvato con `SETEX` (SET + EXpire) con un TTL di **5 ore**. Redis elimina le chiavi scadute autonomamente, senza task di cleanup da gestire nel codice Python.

```python
r.setex(f"job:{job_id}", 5 * 3600, json.dumps(data))
# La chiave "job:abc..." sparirà automaticamente dopo 5 ore
```

### Fallback automatico

All'avvio, il servizio tenta la connessione a Redis:

```python
try:
    _redis_client = redis.Redis.from_url(_REDIS_URL, socket_connect_timeout=3)
    _redis_client.ping()
    logger.info("Redis connesso: %s", _REDIS_URL)
except Exception as e:
    _redis_client = None
    logger.warning("Redis non disponibile, uso fallback in-memory")
```

Se Redis non è raggiungibile (es. sviluppo locale senza Docker), il servizio continua a funzionare con il dict in-memory. **Non crasha, non rifiuta richieste.**

---

## Modifiche ai file

### `docker-compose.yml`

Aggiunto il servizio `redis` e il volume `redis-data`:

```yaml
redis:
  image: redis:7-alpine
  container_name: redis
  ports:
    - "6379:6379"
  volumes:
    - redis-data:/data
  networks:
    - elastic
  command: redis-server --save 60 1 --loglevel warning
```

Il parametro `--save 60 1` fa sì che Redis salvi lo snapshot su disco ogni 60 secondi se almeno 1 chiave è cambiata. Questo garantisce che i job sopravvivano a un riavvio del container Redis stesso.

`docling-service` ora dichiara la dipendenza e riceve l'URL:

```yaml
docling-service:
  depends_on:
    - redis
  environment:
    - REDIS_URL=redis://redis:6379
```

### `requirements.txt`

```
redis>=5.0.0
```

### `main.py`

- Rimosso `_jobs: dict` e `_cleanup_old_jobs()`
- Aggiunte `_job_set`, `_job_get`, `_job_list`
- All'avvio: tentativo connessione Redis con fallback
- `_process_document_async`: usa i nuovi helper invece di accedere direttamente a `_jobs`

---

## Cosa rimane da fare per un deployment production completo

Questa modifica risolve il problema della scalabilità orizzontale del job store. Rimangono aperti altri gap descritti in dettaglio nell'analisi del servizio:

- **Rate limiting**: nessun limite al numero di richieste in coda (vedi `slowapi`)
- **Autenticazione**: il servizio è accessibile senza credenziali — va messo su rete interna o protetto con API key
- **Worker crash recovery**: se un processo worker va in OOM, `ProcessPoolExecutor` non lo rilancia automaticamente
- **Monitoring**: nessun alert se il pool perde capacità
