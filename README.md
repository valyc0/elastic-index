# Elastic Index - Sistema di Indicizzazione Full-Text con Elasticsearch

Sistema di indicizzazione e ricerca full-text multilingua per documenti PDF utilizzando Spring Boot, Elasticsearch e Apache Tika. Include anche un motore di **ricerca semantica** basato sul modello ML ELSER v2 di Elastic.

## 🎯 Caratteristiche

- ✅ **Estrazione testo da PDF** con Apache Tika
- ✅ **Chunking intelligente** con overlap per risultati precisi
- ✅ **Rilevamento automatico della lingua** (italiano, inglese, francese)
- ✅ **Indicizzazione multi-indice** (un indice per lingua)
- ✅ **Ricerca Google-like** con fuzzy matching e highlighting
- ✅ **API REST** complete per estrazione, indicizzazione e ricerca
- ✅ **Supporto documenti multilingua** (anche misti)
- ✅ **Ricerca semantica** con ELSER v2 (sparse embedding ML nativo Elastic)

## 🏗️ Architettura

### Stack Tecnologico

- **Spring Boot**: 4.0.1
- **Java**: 21
- **Elasticsearch**: 8.11.3
- **Kibana**: 8.11.3
- **Apache Tika**: 2.9.1
- **ELSER v2**: modello ML sparse embedding per ricerca semantica

### Logica di Chunking

Il sistema suddivide i documenti in chunk con le seguenti caratteristiche:

- **Dimensione chunk**: 500 parole
- **Overlap**: 100 parole tra chunk consecutivi
- **Strategia**: word-based splitting

```
[chunk 0] → parole 0-500
[chunk 1] → parole 400-900    (overlap di 100 parole)
[chunk 2] → parole 800-1300   (overlap di 100 parole)
...
```

**Vantaggi dell'overlap**:
- Evita di spezzare concetti tra chunk
- Migliora la rilevanza dei risultati
- Garantisce continuità semantica

### Indici Elasticsearch

Il sistema crea indici separati per lingua:

- `files_it` → documenti in italiano
- `files_en` → documenti in inglese
- `files_fr` → documenti in francese
- `files_generic` → documenti con lingua non rilevata
- `semantic_docs` → embedding ELSER per ricerca semantica (vedi sezione dedicata)

Ogni chunk viene indicizzato con:
- `id` - UUID univoco del chunk
- `documentId` - ID del documento originale
- `chunkIndex` - Posizione del chunk (0, 1, 2...)
- `content` - Testo del chunk
- `language` - Lingua rilevata
- `fileName` - Nome file originale
- `page` - Numero pagina (opzionale)

## 🚀 Quick Start

### Prerequisiti

- Java 21
- Maven 3.8+
- Docker e Docker Compose
- curl e jq (per gli script di test)

### Avvio

1. **Avvia Elasticsearch e Kibana**:
```bash
docker-compose up -d
```

2. **Avvia l'applicazione Spring Boot**:
```bash
cd my-app
mvn spring-boot:run
```

3. **Verifica lo stato**:
```bash
# Application health
curl http://localhost:8080/actuator/health

# Elasticsearch health
curl http://localhost:9200/_cluster/health
```

---

## 🤖 Ricerca Semantica con ELSER v2

### Cos'è ELSER

ELSER (Elastic Learned Sparse EncodeR) è un modello ML proprietario di Elastic che genera **sparse embeddings**: vettori sparsi di token pesati, addestrati su grandi corpora di testo. A differenza della ricerca full-text classica (che cerca corrispondenze esatte di parole), ELSER capisce il **significato semantico** della query.

**Esempi di capacità semantica:**
- Query `"motore del Nautilus"` → trova chunk che parlano di "propulsione elettrica" anche senza la parola "motore"
- Query `"polo sud ghiacci"` → trova chunk del capitolo "La banchisa" anche senza le parole esatte
- Query `"cibo a bordo"` → trova chunk che descrivono i pasti anche con vocabolario diverso

### Prerequisiti

- Licenza Elasticsearch **trial** o **Platinum/Enterprise** (ELSER richiede xpack.ml)
- La trial gratuita da 30 giorni si attiva automaticamente con `setup-semantic-elastic.sh`

### Setup: script `setup-semantic-elastic.sh`

Lo script configura l'intero stack semantico in un unico passaggio:

```bash
./setup-semantic-elastic.sh
```

**Cosa fa, step by step:**

1. **Attiva la trial license** (se non già attiva) via `POST /_license/start_trial`
2. **Verifica/scarica il modello** `.elser_model_2` dal registry Elastic ML (~438 MB, richiede connessione internet dal container)
3. **Deploya il modello** con `POST /_ml/trained_models/.elser_model_2/deployment/_start`
4. **Attende `fully_allocated`** polling su `/_ml/trained_models/.elser_model_2/_stats`
5. **Crea la ingest pipeline** `elser-v2-sparse`:
   - Processor `inference`: per ogni documento in ingestion, genera automaticamente il vettore sparso dal campo `content` e lo scrive in `content_embedding`
6. **Crea l'indice** `semantic_docs` con:
   - `default_pipeline: elser-v2-sparse` (ogni documento indicizzato passa automaticamente per ELSER)
   - Mapping `content_embedding` di tipo `sparse_vector`

**Output atteso:**
```
[INFO] Modello pronto (state=fully_allocated).
[INFO] Creazione ingest pipeline 'elser-v2-sparse'...  → HTTP 200
[INFO] Creazione indice 'semantic_docs'...  → HTTP 200
[INFO] Setup completato.
```

### Flusso di indicizzazione semantica

```
PDF → (Apache Tika) → testo grezzo
         ↓
    ChunkingUtils → chunk di testo (~500 parole)
         ↓
    POST /api/semantic/index/from-json
         ↓
    ElasticsearchClient.index() con pipeline="elser-v2-sparse"
         ↓
    Elasticsearch ingest pipeline
         ↓
    ELSER v2 genera content_embedding (sparse vector)
         ↓
    documento salvato in "semantic_docs" con embedding
```

### Flusso di ricerca semantica

```
POST /api/semantic/search {"query": "testo libero", "size": N}
         ↓
    SemanticSearchService.search()
         ↓
    Elasticsearch text_expansion query su "content_embedding"
    con model_id=".elser_model_2"
         ↓
    ELSER espande la query in token sparsi
         ↓
    confronto vettoriale con gli embedding indicizzati
         ↓
    risultati ordinati per score di similarità semantica
```

### API Endpoint semantici

#### Indicizzazione da JSON estratto

```bash
POST /api/semantic/index/from-json?jsonFile=<nome-file.json>
```

```bash
curl -X POST "http://localhost:8080/api/semantic/index/from-json?jsonFile=ventimila-leghe.pdf_20260316_205851.json"
```

**Response:**
```json
{
  "documentId": "4fefb820-30e3-40da-a5f4-c442297b409b",
  "fileName": "ventimila-leghe.pdf",
  "chunks": 179,
  "index": "semantic_docs",
  "message": "Documento indicizzato con embedding ELSER"
}
```

> **Nota**: ogni chunk impiega ~2 secondi (ELSER gira localmente nel container ES). Per 179 chunk → ~6 minuti totali.

#### Indicizzazione da upload PDF

```bash
POST /api/semantic/index  (multipart/form-data)
```

```bash
curl -X POST "http://localhost:8080/api/semantic/index" \
  -F "file=@documento.pdf"
```

#### Ricerca semantica

```bash
POST /api/semantic/search
```

```bash
curl -X POST "http://localhost:8080/api/semantic/search" \
  -H "Content-Type: application/json" \
  -d '{"query": "profondità massima raggiunta dal Nautilus", "size": 3}'
```

**Response:**
```json
[
  {
    "documentId": "4fefb820-...",
    "fileName": "ventimila-leghe.pdf",
    "chapterTitle": "5. Il Mediterraneo in quarantotto ore.",
    "chunkIndex": 28,
    "content": "...il Nautilus, scivolando con i suoi alettoni inclinati, si immergeva fino agli strati più profondi del mare. Là, in mancanza di meraviglie...",
    "score": 23.55
  }
]
```

### Differenza tra ricerca full-text e semantica

| Aspetto | Full-text (`files_it`) | Semantica (`semantic_docs`) |
|---|---|---|
| Tecnologia | BM25, fuzzy match | ELSER sparse embedding |
| Match | Parole esatte/simili | Significato concettuale |
| Query `"capitano Nemo"` | Trova solo chunk con "capitano" o "Nemo" | Trova anche scene dove agisce il comandante |
| Query `"polpi attaccano il Nautilus"` | Trova "polpi" e "Nautilus" | Trova l'episodio anche detto come "mostri tentacolari" |
| Latenza indicizzazione | ~immediata | ~2s/chunk (ELSER inference) |
| Latenza ricerca | <100ms | ~200-500ms |
| Licenza ES | Basic (gratuita) | Platinum/Trial |

### Comandi utili per il monitoraggio

```bash
# Stato deployment ELSER
curl -s "http://localhost:9200/_ml/trained_models/.elser_model_2/_stats" | \
  python3 -m json.tool | grep -E '"state"|"allocation_count"'

# Conta chunk indicizzati
curl -s "http://localhost:9200/semantic_docs/_count"

# Verifica pipeline
curl -s "http://localhost:9200/_ingest/pipeline/elser-v2-sparse"

# Verifica mapping sparse_vector
curl -s "http://localhost:9200/semantic_docs/_mapping" | \
  python3 -m json.tool | grep -A2 "content_embedding"
```

### Workflow semantico completo

```bash
# 1. Setup iniziale (una volta sola)
./setup-semantic-elastic.sh

# 2. Avvia l'applicazione
cd my-app && mvn spring-boot:run &

# 3. Indicizza un documento già estratto
curl -X POST "http://localhost:8080/api/semantic/index/from-json?jsonFile=documento.json"

# 4. Attendi il completamento (controlla il count)
watch -n 5 'curl -s http://localhost:9200/semantic_docs/_count'

# 5. Ricerca semantica
curl -s -X POST "http://localhost:8080/api/semantic/search" \
  -H "Content-Type: application/json" \
  -d '{"query": "la tua domanda in linguaggio naturale", "size": 5}' | \
  python3 -m json.tool
```

---

## 🔧 Script Shell - Guida Completa

Il progetto include diversi script Bash per automatizzare operazioni comuni. Tutti gli script devono essere eseguiti dalla directory principale del progetto.

### 1. run-and-test.sh - Avvio e Test Automatico

**Scopo**: Avvia l'applicazione Spring Boot e esegue i test automatici.

**Uso**:
```bash
./run-and-test.sh
```

**Cosa fa**:
1. Termina eventuali istanze precedenti dell'applicazione
2. Avvia Spring Boot in background
3. Attende 20 secondi per il completo avvio
4. Esegue automaticamente `test-elastic.sh`
5. Mostra i log in caso di errori

**Output**:
```
=== Avvio completo e test ===
Avvio applicazione (PID: 12345)...
Attesa 20 secondi per startup completo...
✓ Applicazione avviata su porta 8080
```

**Note**: 
- I log dell'applicazione sono salvati in `/tmp/spring-app.log`
- Utile per reset completo dell'ambiente di sviluppo

---

### 2. test-upload.sh - Upload PDF

**Scopo**: Carica un file PDF ed estrae il testo usando Apache Tika.

**Uso**:
```bash
./test-upload.sh <path-al-file.pdf>
```

**Esempio**:
```bash
./test-upload.sh ventimila-leghe.pdf
./test-upload.sh "Zanna Bianca (1).pdf"
```

**Cosa fa**:
1. Verifica che il file esista
2. Invia il PDF all'endpoint `/api/documents/extract`
3. Mostra il JSON risultante con testo estratto e metadata
4. Salva automaticamente il JSON in `my-app/extracted-documents/`

**Output esempio**:
```json
{
  "fileName": "ventimila-leghe.pdf",
  "text": "Ventimila leghe sotto i mari...",
  "contentType": "application/pdf",
  "metadata": {
    "Author": "Jules Verne",
    "Title": "Ventimila leghe sotto i mari"
  }
}
```

**Errori comuni**:
```bash
# File non trovato
Errore: File 'documento.pdf' non trovato

# Nome file mancante
Uso: ./test-upload.sh <path-al-file.pdf>
```

---

### 3. load-documents.sh - Caricamento Batch

**Scopo**: Indicizza tutti i file JSON dalla directory `extracted-documents/`.

**Uso**:
```bash
./load-documents.sh
```

**Cosa fa**:
1. Verifica che l'applicazione sia in esecuzione
2. Se non lo è, la avvia automaticamente
3. Attende che l'applicazione sia pronta (max 30 secondi)
4. Carica tutti i file `*.json` da `my-app/extracted-documents/`
5. Mostra la risposta per ogni file indicizzato

**Output esempio**:
```
Applicazione pronta!

Caricamento di ventimila-leghe.pdf_20260120_192148.json...
Risposta: Documento indicizzato con ID: abc-123

Caricamento di Zanna_Bianca__1_.pdf_20260204_183611.json...
Risposta: Documento indicizzato con ID: def-456

Caricamento completato!
```

**Quando usarlo**:
- Dopo aver estratto più PDF con `test-upload.sh`
- Per ricaricare tutti i documenti dopo aver cancellato gli indici
- Per inizializzare un nuovo ambiente

**Note**:
- Avvia l'applicazione automaticamente se non è già in esecuzione
- Processa solo i file con estensione `.json`
- Salva i log in `my-app/app.log` se avvia l'applicazione

---

### 4. test-elastic.sh - Test Indicizzazione e Ricerca

**Scopo**: Esegue una serie di test per verificare indicizzazione e ricerca.

**Uso**:
```bash
./test-elastic.sh
```

**Cosa fa**:
1. Indicizza il file JSON `ventimila-leghe.pdf_20260120_192148.json`
2. Mostra tutti gli indici Elasticsearch creati
3. Esegue una ricerca semplice (GET) per la parola "mare"
4. Esegue una ricerca avanzata (POST) per "capitano"

**Output esempio**:
```
=== Test 1: Indicizzazione del JSON ===
Documento indicizzato con ID: abc-123
✓ Indicizzazione completata

=== Test 2: Verifica indici Elasticsearch ===
health status index        uuid   pri rep docs.count
yellow open   files_it     xyz123   1   0         45
yellow open   files_fr     abc456   1   0         12

=== Test 3: Ricerca semplice ===
[
  {
    "documentId": "abc-123",
    "fileName": "ventimila-leghe.pdf",
    "content": "...il mare era calmo...",
    "score": 12.5
  }
]

=== Test 4: Ricerca avanzata ===
[...]
```

**Quando usarlo**:
- Per verificare che il sistema funzioni end-to-end
- Dopo modifiche al codice di indicizzazione o ricerca
- Per debug di problemi di ricerca

---

### 5. test-search.sh - Ricerca Parametrica

**Scopo**: Esegue ricerche personalizzate con parametri variabili.

**Uso**:
```bash
./test-search.sh [query] [lingua] [dimensione]
```

**Parametri** (tutti opzionali):
- `query`: termine di ricerca (default: "nautilus")
- `lingua`: codice lingua (default: "it")
- `dimensione`: numero risultati (default: 3)

**Esempi**:
```bash
# Ricerca di default
./test-search.sh

# Ricerca personalizzata
./test-search.sh "capitano nemo" it 5

# Ricerca in inglese
./test-search.sh "captain" en 10

# Ricerca senza lingua specifica
./test-search.sh "adventure"
```

**Output**:
```
Ricerca di: 'capitano nemo' (lingua: it, dimensione: 5)
Endpoint: http://localhost:8080/api/search/quick

[
  {
    "documentId": "abc-123",
    "fileName": "ventimila-leghe.pdf",
    "content": "Il capitano Nemo era...",
    "highlights": ["Il <em>capitano</em> <em>Nemo</em>..."]
  }
]
```

**Quando usarlo**:
- Per testare query specifiche rapidamente
- Per verificare la qualità dei risultati di ricerca
- Durante il debugging di problemi di rilevanza

---

### 6. create-index-template.sh - Configurazione Indici

**Scopo**: Crea un template Elasticsearch per standardizzare la configurazione di tutti gli indici `files_*`.

**Uso**:
```bash
./create-index-template.sh
```

**Cosa fa**:
1. Crea un template con mapping e settings predefiniti
2. Configura analyzer standard
3. Definisce i tipi di campo (keyword, text, integer)
4. Imposta 1 shard e 0 repliche (ottimale per sviluppo)

**Quando usarlo**:
- Prima configurazione del sistema
- Quando si vuole modificare la struttura degli indici
- Per resettare la configurazione degli indici

**Dopo l'esecuzione**:
```bash
# Elimina indici esistenti
curl -X DELETE 'http://localhost:9200/files_*'

# Ricarica documenti per applicare il nuovo template
./load-documents.sh
```

**Output**:
```
Template creato con successo!

Per applicare il template, elimina gli indici esistenti e ricarica i documenti:
  curl -X DELETE 'http://localhost:9200/files_*'
  Poi ricarica i documenti con ./load-documents.sh
```

---

## 📋 Workflow Tipici con gli Script

### Workflow 1: Setup Iniziale
```bash
# 1. Avvia i servizi
docker-compose up -d

# 2. Crea il template degli indici
./create-index-template.sh

# 3. Avvia app e testa
./run-and-test.sh

# 4. Carica tutti i documenti
./load-documents.sh
```

### Workflow 2: Aggiungere Nuovo Documento
```bash
# 1. Estrai testo dal PDF
./test-upload.sh "nuovo-documento.pdf"

# 2. Il JSON viene salvato automaticamente in extracted-documents/

# 3. Indicizza il JSON (manualmente)
curl -X POST "http://localhost:8080/api/index/from-json?jsonFile=nuovo-documento.pdf_*.json"

# Oppure ricarica tutti
./load-documents.sh
```

### Workflow 3: Reset Completo
```bash
# 1. Elimina tutti gli indici
curl -X DELETE 'http://localhost:9200/files_*'

# 2. Ricrea template
./create-index-template.sh

# 3. Riavvia e testa
./run-and-test.sh

# 4. Ricarica documenti
./load-documents.sh
```

### Workflow 4: Testing e Debug
```bash
# 1. Avvia applicazione
./run-and-test.sh

# 2. Prova diverse ricerche
./test-search.sh "parola1" it 5
./test-search.sh "parola2" en 10

# 3. Verifica indici
curl "http://localhost:9200/_cat/indices?v"

# 4. Controlla documenti
curl "http://localhost:9200/files_it/_search?size=1&pretty"
```

---

## 💡 Suggerimenti per gli Script

1. **Permessi di esecuzione**: Se uno script non è eseguibile:
   ```bash
   chmod +x *.sh
   ```

2. **Dipendenze**: Installa strumenti mancanti:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install curl jq
   
   # macOS
   brew install curl jq
   ```

3. **Path assoluti**: Gli script usano path assoluti, quindi devono essere eseguiti dalla directory root del progetto

4. **Background processes**: `run-and-test.sh` e `load-documents.sh` avviano processi in background; usa `pkill -f spring-boot` per terminarli

5. **Timeout**: Se `load-documents.sh` va in timeout, aumenta `MAX_WAIT` nello script:
   ```bash
   MAX_WAIT=60  # aumenta da 30 a 60 secondi
   ```

## 📡 API Endpoints

### 1. Estrazione Testo da PDF

**Endpoint**: `POST /api/documents/extract`

Estrae testo e metadata da un file PDF usando Apache Tika.

```bash
curl -X POST "http://localhost:8080/api/documents/extract" \
  -F "file=@documento.pdf"
```

**Response**:
```json
{
  "fileName": "documento.pdf",
  "text": "Contenuto estratto...",
  "contentType": "application/pdf",
  "metadata": {
    "Author": "Nome Autore",
    "Title": "Titolo",
    "pages": "120"
  }
}
```

### 2. Indicizzazione da JSON

**Endpoint**: `POST /api/index/from-json`

Indicizza un documento da file JSON precedentemente estratto.

```bash
curl -X POST "http://localhost:8080/api/index/from-json?jsonFile=documento.json"
```

### 3. Indicizzazione Diretta

**Endpoint**: `POST /api/index/from-extraction`

Indicizza direttamente il risultato dell'estrazione.

```bash
curl -X POST "http://localhost:8080/api/index/from-extraction" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "test.pdf",
    "text": "Contenuto del documento...",
    "contentType": "application/pdf"
  }'
```

### 4. Ricerca Semplice

**Endpoint**: `POST /api/search/simple`

Ricerca base con match su content e fileName.

```bash
curl -X POST "http://localhost:8080/api/search/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "contratto lavoro",
    "language": "it",
    "size": 5
  }'
```

### 5. Ricerca Avanzata

**Endpoint**: `POST /api/search/advanced`

Ricerca con boost personalizzati e ranking avanzato.

```bash
curl -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "employment contract",
    "language": "en",
    "size": 10
  }'
```

**Caratteristiche**:
- Match phrase con boost 3.0
- Fuzzy match con boost 1.0
- FileName boost 5.0
- Highlighting automatico

### 6. Ricerca Rapida (GET)

**Endpoint**: `GET /api/search/quick`

Ricerca veloce via query parameters.

```bash
curl -X GET "http://localhost:8080/api/search/quick?q=mare&lang=it&size=3"
```

**Response**:
```json
[
  {
    "documentId": "abc-123",
    "fileName": "ventimila-leghe.pdf",
    "content": "...testo del chunk...",
    "language": "it",
    "chunkIndex": 5,
    "score": 12.456,
    "highlights": [
      "...il <em>mare</em> era calmo..."
    ]
  }
]
```

## 🔧 Configurazione

### application.properties

```properties
# Server
server.port=8080

# Elasticsearch
elasticsearch.url=http://localhost:9200

# File Upload
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB
```

### Chunking Personalizzato

Modifica i parametri in `ChunkingUtils.java`:

```java
private static final int CHUNK_SIZE = 500;  // parole per chunk
private static final int OVERLAP = 100;     // parole di overlap
```

## 📊 Comandi Utili Elasticsearch

### Visualizza tutti gli indici

```bash
curl http://localhost:9200/_cat/indices?v
```

### Conta documenti per indice

```bash
curl -s "http://localhost:9200/files_*/_count"
```

### Visualizza documenti indicizzati

```bash
curl "http://localhost:9200/files_it/_search?pretty&size=5"
```

### Elimina un indice

```bash
curl -X DELETE "http://localhost:9200/files_it"
```

### Visualizza mapping

```bash
curl "http://localhost:9200/files_it/_mapping?pretty"
```

## 🎨 Kibana Dashboard

Accedi a Kibana per esplorare i dati visivamente:

```
http://localhost:5601
```

## 🏗️ Struttura Progetto

```
elastic-index/
├── docker-compose.yml              # Elasticsearch + Kibana
├── setup-semantic-elastic.sh       # Setup ELSER v2 + pipeline + indice semantico
├── my-app/                         # Applicazione Spring Boot
│   ├── src/main/java/
│   │   └── io/bootify/my_app/
│   │       ├── rest/
│   │       │   ├── IndexController.java          # /api/index/* (full-text)
│   │       │   ├── SearchController.java         # /api/search/* (full-text)
│   │       │   ├── SemanticController.java       # /api/semantic/* (ELSER)
│   │       │   └── DocumentExtractionController.java
│   │       ├── service/
│   │       │   ├── ElasticsearchIndexService.java   # indicizzazione full-text
│   │       │   ├── ElasticsearchSearchService.java  # ricerca full-text
│   │       │   ├── SemanticIndexService.java        # indicizzazione ELSER
│   │       │   ├── SemanticSearchService.java       # ricerca semantica ELSER
│   │       │   ├── DocumentExtractionService.java
│   │       │   └── LanguageDetectionService.java
│   │       ├── model/
│   │       │   ├── SemanticChunk.java     # chunk con @JsonIgnoreProperties
│   │       │   └── ...
│   │       ├── config/
│   │       └── util/
│   │           └── ChunkingUtils.java     # splitting testo in chunk
│   ├── extracted-documents/        # JSON estratti da PDF
│   └── pom.xml
├── run-and-test.sh                 # Script avvio e test completo
├── test-elastic.sh                 # Test ricerca full-text
├── test-upload.sh                  # Test upload PDF
└── create-index-template.sh        # Configurazione template indici
```

## 🧪 Testing

### Test Estrazione PDF

```bash
curl -X POST "http://localhost:8080/api/documents/extract" \
  -F "file=@test.pdf" | jq .
```

### Test Indicizzazione

```bash
# Estrai e salva
curl -X POST "http://localhost:8080/api/documents/extract" \
  -F "file=@test.pdf" > extracted-documents/test.json

# Indicizza
curl -X POST "http://localhost:8080/api/index/from-json?jsonFile=test.json"
```

### Test Ricerca

```bash
# Ricerca semplice
curl -X GET "http://localhost:8080/api/search/quick?q=test&size=3" | jq .

# Ricerca avanzata
curl -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{"query": "test document", "size": 5}' | jq .
```

## 🔍 Best Practices

1. **Chunking**: mantieni overlap al 20% della dimensione chunk
2. **Lingue**: rileva sempre la lingua per chunk, non per documento
3. **Indici**: usa indici separati per lingua per migliori performance
4. **Ricerca**: usa ricerca avanzata per risultati tipo Google
5. **Highlighting**: abilita sempre per migliorare UX

## 🐛 Troubleshooting

### Elasticsearch non si avvia

```bash
# Verifica container
docker-compose ps

# Visualizza log
docker-compose logs elasticsearch
```

### Errore di memoria

Aumenta memoria in `docker-compose.yml`:

```yaml
ES_JAVA_OPTS=-Xms1g -Xmx1g
```

### ELSER non si deploya

```bash
# Controlla stato modello
curl -s "http://localhost:9200/_ml/trained_models/.elser_model_2/_stats" | \
  python3 -m json.tool | grep -E '"state"|"error"'

# Riprova setup completo
./setup-semantic-elastic.sh
```

### Ricerca semantica restituisce 500

Causa più comune: `content_embedding` (sparse_vector) non è escluso dalla `_source` quando ES restituisce i risultati, causando un errore di deserializzazione Jackson.

**Soluzione già applicata** in `SemanticSearchService.java`:
```java
.source(src -> src.filter(f -> f.excludes("content_embedding")))
```

E in `SemanticChunk.java`:
```java
@JsonIgnoreProperties(ignoreUnknown = true)
```

### Pipeline `elser-v2-sparse` non trovata (risposta `{}`)

Esegui di nuovo lo script di setup:
```bash
./setup-semantic-elastic.sh
```

### Indice `semantic_docs` non esiste

```bash
curl -s "http://localhost:9200/semantic_docs/_count"
# se 404 → rieseguire setup-semantic-elastic.sh
```

### Documenti non trovati

Verifica indicizzazione:

```bash
curl "http://localhost:9200/files_*/_count"
curl "http://localhost:9200/semantic_docs/_count"
```

## 📝 Licenza

Questo progetto è di proprietà privata.

## 👥 Autori

Progetto sviluppato per indicizzazione e ricerca documentale enterprise.

---

**Status**: ✅ Completato e funzionante (Aprile 2026)
- Ricerca full-text: indici `files_*` con BM25
- Ricerca semantica: indice `semantic_docs` con ELSER v2 sparse embedding