# Elastic Index - Sistema di Indicizzazione Full-Text con Elasticsearch

Sistema di indicizzazione e ricerca full-text multilingua per documenti PDF utilizzando Spring Boot, Elasticsearch e Apache Tika.

## 🎯 Caratteristiche

- ✅ **Estrazione testo da PDF** con Apache Tika
- ✅ **Chunking intelligente** con overlap per risultati precisi
- ✅ **Rilevamento automatico della lingua** (italiano, inglese, francese)
- ✅ **Indicizzazione multi-indice** (un indice per lingua)
- ✅ **Ricerca Google-like** con fuzzy matching e highlighting
- ✅ **API REST** complete per estrazione, indicizzazione e ricerca
- ✅ **Supporto documenti multilingua** (anche misti)

## 🏗️ Architettura

### Stack Tecnologico

- **Spring Boot**: 4.0.1
- **Java**: 21
- **Elasticsearch**: 8.11.3
- **Kibana**: 8.11.3
- **Apache Tika**: 2.9.1

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
├── docker-compose.yml          # Elasticsearch + Kibana
├── my-app/                     # Applicazione Spring Boot
│   ├── src/main/java/
│   │   └── io/bootify/my_app/
│   │       ├── rest/           # Controller REST
│   │       ├── service/        # Business logic
│   │       ├── model/          # Domain models
│   │       ├── config/         # Configuration
│   │       └── util/           # Utilities (chunking)
│   ├── extracted-documents/    # JSON estratti da PDF
│   └── pom.xml
├── run-and-test.sh            # Script avvio e test completo
├── test-elastic.sh            # Test ricerca
└── test-upload.sh             # Test upload
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

### Documenti non trovati

Verifica indicizzazione:

```bash
curl "http://localhost:9200/files_*/_count"
```

## 📝 Licenza

Questo progetto è di proprietà privata.

## 👥 Autori

Progetto sviluppato per indicizzazione e ricerca documentale enterprise.

---

**Status**: ✅ Completato e funzionante (Gennaio 2026)