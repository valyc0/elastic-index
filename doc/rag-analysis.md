# Analisi RAG — elastic-index

> Analisi dell'architettura e della qualità della pipeline RAG del progetto.

---

## Architettura generale

```
[Documento PDF/DOCX/...]
        │
        ▼
[Docling Service — Python/FastAPI]
  • Parsing strutturato (IBM Docling)
  • Estrazione sezioni, tabelle, metadati
  • ProcessPoolExecutor (bypass GIL)
  • Endpoint sincrono POST /parse
  • Endpoint asincrono POST /parse/async + GET /jobs/{id}
  • Job store Redis con TTL automatico
        │
        ▼
[Spring Boot — my-app]
  ┌─────────────────────────────────────┐
  │  SemanticChunkingService            │
  │  • Sentence-aware splitting         │
  │  • Overlap controllato              │
  │  • Paragraph boundary detection     │
  └──────────────┬──────────────────────┘
                 │
  ┌──────────────▼──────────────────────┐
  │  EmbeddingProvider (strategy)       │
  │  • OllamaEmbeddingProvider          │
  │  • OpenAiEmbeddingProvider          │
  └──────────────┬──────────────────────┘
                 │
  ┌──────────────▼──────────────────────┐
  │  SemanticIndexService               │
  │  • Bulk indexing su Elasticsearch   │
  │  • Deduplicazione per fileName      │
  │  • Filtro boilerplate/TOC           │
  └──────────────┬──────────────────────┘
                 │
        [Elasticsearch — semantic_docs]
        BM25 full-text + kNN dense vectors
                 │
  ┌──────────────▼──────────────────────┐
  │  HybridSearchService                │
  │  • BM25 + kNN in parallelo          │
  │  • RRF fusion (k=60)                │
  │  • Position boost (query "finale")  │
  │  • Two-stage reranker               │
  └──────────────┬──────────────────────┘
                 │
  ┌──────────────▼──────────────────────┐
  │  RagService                         │
  │  • Grounding enforcement            │
  │  • Query expansion (fallback)       │
  │  • Conversation history (sliding)   │
  │  • LlmProvider (strategy)           │
  │    ├─ OllamaLlmProvider             │
  │    ├─ OpenAiLlmProvider             │
  │    └─ OpenRouterLlmProvider         │
  └─────────────────────────────────────┘
        │
        ▼
[RagAnswer — risposta + fonti + follow-up questions]
```

---

## ✅ Punti di forza

### 1. Hybrid search BM25 + kNN con RRF
La ricerca combina full-text BM25 e similarità vettoriale kNN, fusi con Reciprocal Rank Fusion:

```
RRF_score(d) = Σ  1 / (k + rank_i(d))   con k = 60
```

BM25 e kNN vengono eseguiti **in parallelo** via `CompletableFuture`, abbattendo la latenza. Questa è la best practice consolidata nel 2025–2026 per sistemi RAG su Elasticsearch.

### 2. Chunking semantico con overlap
`SemanticChunkingService` implementa una strategia a tre livelli:
1. Divide su confini di **paragrafo** (`\n{2,}`)
2. Divide ogni paragrafo su confini di **frase** (`[.!?]`)
3. Mantiene le ultime N frasi come **overlap** nel chunk successivo

Questo evita il naïve splitting per caratteri che spezza concetti a metà e perde il contesto ai confini.

### 3. Two-stage reranker
Dopo il retrieval RRF, un secondo stadio rivaluta i candidati con:
- Score RRF normalizzato (peso 0.65)
- Term overlap query/contenuto (peso 0.25)
- Title/filename match (peso 0.10)

I pesi sono interamente configurabili in `application.properties`.

### 4. Grounding enforcement
Il `RagService` implementa regole di grounding rigorose:
- **min-score** (0.15): chunk sotto soglia → fallback senza LLM call
- **min-sources** (2): richiede almeno N fonti distinte
- **require-citations**: verifica che la risposta LLM citi le fonti con `[FONTE N]`
- **auto-append-citations**: aggiunge automaticamente le fonti se l'LLM le omette

### 5. Query expansion automatica
Se il retrieval restituisce evidenza insufficiente, il sistema espande la query e ritenta (una sola volta) prima di dichiarare il fallback — riducendo i falsi negativi senza rischiare allucinazioni.

### 6. Conversazione multi-turn con sliding window
Lo storico conversazionale è mantenuto server-side per sessione, con una finestra scorrevole (`rag.conversation.max-turns=10`) e TTL configurabile (`rag.conversation.ttl-minutes=60`).

### 7. Provider pattern (Strategy) per LLM ed embedding
Sia `LlmProvider` che `EmbeddingProvider` sono interfacce iniettate via Spring. Cambiare provider (Ollama → OpenAI → OpenRouter) non richiede modifiche alla logica di business, solo un cambio di profilo Spring.

### 8. Docling service — architettura Python robusta
- `ProcessPoolExecutor` con `initializer=_worker_init`: bypassa il GIL e carica i modelli PyTorch **una sola volta** per processo worker
- Semaforo asincrono che limita le conversioni concorrenti (`DOCLING_MAX_CONCURRENT`)
- Timeout configurabile per singola conversione (`DOCLING_TIMEOUT_SEC`)
- Fallback in-memory se Redis non è disponibile
- Healthcheck Docker integrato

### 9. Filtro boilerplate/TOC
Prima dell'indicizzazione, `SemanticIndexService` elimina:
- Chunk con meno di 10 parole
- Sezioni il cui titolo contiene riferimenti di pagina (es. "PARTE PRIMA: pagina 16")

---

## 🔴 Problemi critici

### 1. Elasticsearch senza autenticazione
**File:** `docker-compose.yml`, riga 11

```yaml
- xpack.security.enabled=false
```

L'indice `semantic_docs` con tutti i documenti indicizzati è accessibile senza credenziali. In qualsiasi ambiente esposto (anche una LAN aziendale) chiunque può leggere, scrivere o cancellare i dati.

**Fix:**
```yaml
- xpack.security.enabled=true
- ELASTIC_PASSWORD=<strong-password>
```
E configurare `spring.elasticsearch.username`/`password` nell'applicazione.

---

### 2. Upload limit mismatch (10 MB vs 100 MB)
**File:** `application.properties:90`, `docker-compose.yml:81`

Spring Boot limita i file a **10 MB**:
```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

Ma il Docling service accetta fino a **100 MB** (`DOCLING_MAX_FILE_MB=100`).  
Qualsiasi PDF > 10 MB fallisce con un 413 prima ancora di raggiungere Docling, senza un messaggio d'errore chiaro per l'utente.

**Fix:**
```properties
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
```

---

### 3. ES_URL hardcodato in ElasticsearchSearchService
**File:** `ElasticsearchSearchService.java:37`

```java
private static final String ES_URL = "http://localhost:9200";
```

Questa costante è usata per le query REST legacy (metodo `executeSearch`). Dentro Docker o in qualsiasi deployment remoto, `localhost` non risolve correttamente il container Elasticsearch.

**Fix:** Iniettare il valore da properties:
```java
@Value("${spring.elasticsearch.uris}")
private String esUrl;
```

---

### 4. Redis KEYS bloccante in list_jobs
**File:** `docling/main.py:102`

```python
keys = r.keys("job:*")
```

`KEYS` è un'operazione O(N) che **blocca Redis** per tutta la sua durata. Con migliaia di job può bloccare la pipeline di parsing.

**Fix:** Usare `SCAN` in modalità iterativa:
```python
keys = list(r.scan_iter("job:*"))
```

---

### 5. Device AUTO non gestito nel Docling service
**File:** `docling/main.py:52-57`

```python
_DEVICE_MAP = {
    "CPU":  "cpu",
    "CUDA": "cuda",
    "MPS":  "mps",
}
_DEVICE = _DEVICE_MAP.get(_DEVICE_STR, "cpu")   # "AUTO" non è nella map → sempre "cpu"
```

Se `DOCLING_DEVICE=AUTO` (il default), il fallback è `"cpu"` e la GPU non viene mai usata, anche se disponibile.

**Fix:**
```python
if _DEVICE_STR == "AUTO":
    _DEVICE = "cuda" if torch.cuda.is_available() else \
              "mps"  if torch.backends.mps.is_available() else "cpu"
else:
    _DEVICE = _DEVICE_MAP.get(_DEVICE_STR, "cpu")
```

---

## 🟡 Punti di miglioramento

### A. Chunking per parole, non per token
`SemanticChunkingService` conta le parole (`split("\\s+")`) ma i modelli di embedding hanno limiti in **token**. Una parola in tedesco o un termine tecnico può valere 3–5 token BPE, causando troncamento silenzioso degli embedding.

**Miglioramento:** Usare un tokenizer (es. Tiktoken per OpenAI, o il tokenizer nativo di Ollama) per contare i token reali.

### B. Tabelle serializzate come testo piatto
Le tabelle vengono embedded come `df.to_string(index=False)` — formato testuale che perde la struttura bidimensionale e l'allineamento delle colonne.

**Miglioramento:** Serializzare le tabelle in **Markdown** (`df.to_markdown()`) prima dell'embedding, che preserva la struttura e viene compreso meglio dai modelli linguistici.

### C. Filtro TOC solo in italiano
Il pattern di boilerplate detection:
```java
Pattern.compile("(?i)pagina\\s+\\d+")
```
cattura solo "pagina N" in italiano. Documenti in inglese ("page 16"), tedesco ("Seite 16") o francese ("page 16") non vengono filtrati.

**Miglioramento:**
```java
Pattern.compile("(?i)(pagina|page|seite|página)\\s+\\d+")
```

### D. Nessun auth sugli endpoint REST
L'API Spring Boot (RAG, indexing, admin) è aperta senza autenticazione. Chiunque raggiunga la porta 8080 può indicizzare documenti, fare query RAG o cancellare dati.

**Miglioramento:** Aggiungere almeno HTTP Basic Auth con Spring Security per gli endpoint `/api/*`.

### E. H2 embedded — non scalabile
H2 in modalità file (`jdbc:h2:file:./data/docling-store`) è ottimo per sviluppo ma non supporta istanze multiple dell'applicazione. In produzione è preferibile PostgreSQL.

### F. Reranker euristico vs neurale
Il second-stage reranker è basato su pesi manuali (term overlap, title match). Un **cross-encoder neurale** (es. `cross-encoder/ms-marco-MiniLM-L-6-v2`) produrrebbe qualità superiore, a costo di latenza aggiuntiva (~20–50ms per batch).  
Per uso locale la scelta attuale è accettabile.

---

## Sintesi

| Area | Valutazione |
|------|-------------|
| Architettura RAG | ⭐⭐⭐⭐⭐ Eccellente |
| Hybrid search (BM25 + kNN + RRF) | ⭐⭐⭐⭐⭐ Best practice |
| Chunking semantico | ⭐⭐⭐⭐☆ Molto buono (manca token counting) |
| Grounding e hallucination control | ⭐⭐⭐⭐⭐ Robusto |
| Pluggabilità provider | ⭐⭐⭐⭐⭐ Pattern corretto |
| Sicurezza | ⭐☆☆☆☆ Critica — nessuna auth |
| Configurazione deployment | ⭐⭐☆☆☆ Hardcoding e mismatch |
| Scalabilità | ⭐⭐⭐☆☆ Accettabile per single-node |

Il progetto è **ben progettato** per un sistema RAG locale/on-premise: le scelte architetturali (hybrid search, chunking con overlap, grounding, multi-turn) sono mature e corrette. I problemi critici sono quasi tutti di **configurazione**, non di design, e quindi facilmente risolvibili prima di un deploy in produzione.
