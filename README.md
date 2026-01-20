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

### Test Completo

Usa lo script di test automatico:

```bash
./run-and-test.sh
```

Oppure singolarmente:

```bash
./test-elastic.sh
./test-upload.sh
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