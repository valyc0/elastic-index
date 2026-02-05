# Esempi di Ricerca - Elasticsearch Document Index

## 1. Ricerca Semplice

Ricerca base con query multi-match su content e fileName:

```bash
curl -X POST "http://localhost:8080/api/search/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "lupo foresta",
    "language": "it",
    "size": 10
  }'
```

## 2. Ricerca Avanzata

Ricerca con boosting differenziato e fuzzy matching:

```bash
curl -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "la mente umana di solito è attratta",
    "language": "it",
    "size": 5
  }'
```

### Con formattazione (senza content)

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Il richiamo della foresta",
    "language": "it",
    "size": 2
  }' | jq '.[] |= del(.content)'
```

### Solo fileName e score

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "natura selvaggia",
    "language": "it",
    "size": 3
  }' | jq '.[] | {fileName, score, chunkIndex, highlights}'
```

## 3. Ricerca con Spiegazione dello Score

Visualizza il dettaglio del calcolo BM25:

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mente umana",
    "language": "it",
    "size": 1,
    "explain": true
  }' | jq '.[0] | {fileName, score, explanation}'
```

## 4. Ricerca Avanzata con Filtri Metadati

Combina ricerca testuale con filtri sui metadati:

### Filtra per autore

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "lupo",
    "language": "it",
    "size": 5,
    "metadataFilters": {
      "dc:creator": "Sabrina"
    }
  }' | jq '.[] | {fileName, score, creator: .metadata["dc:creator"]}'
```

### Filtra per numero di pagine

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "avventura",
    "language": "it",
    "size": 5,
    "metadataFilters": {
      "xmpTPg:NPages": "131"
    }
  }' | jq '.[] | {fileName, pages: .metadata["xmpTPg:NPages"]}'
```

### Filtri multipli

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "natura",
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "dc:creator": "Sabrina",
      "pdf:producer": "OpenOffice.org 2.3"
    }
  }' | jq '.[] |= del(.content)'
```

## 5. Ricerca Solo per Metadati

Trova documenti basandosi solo sui metadati, senza query testuale:

### Per autore

```bash
curl -s -X POST "http://localhost:8080/api/search/by-metadata" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "dc:creator": "Billy"
    }
  }' | jq '.[] | {fileName, creator: .metadata["dc:creator"], title: .metadata["dc:title"]}'
```

### Per produttore PDF

```bash
curl -s -X POST "http://localhost:8080/api/search/by-metadata" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "pdf:producer": "OpenOffice.org 2.3"
    }
  }' | jq '.[] | {fileName, producer: .metadata["pdf:producer"]}'
```

### Per numero di pagine

```bash
curl -s -X POST "http://localhost:8080/api/search/by-metadata" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "xmpTPg:NPages": "228"
    }
  }' | jq '.[] | {fileName, pages: .metadata["xmpTPg:NPages"], creator: .metadata["dc:creator"]}'
```

### Per data di creazione

```bash
curl -s -X POST "http://localhost:8080/api/search/by-metadata" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "dcterms:created": "2008-03-19T14:06:40Z"
    }
  }' | jq '.[] | {fileName, created: .metadata["dcterms:created"]}'
```

### Metadati multipli

```bash
curl -s -X POST "http://localhost:8080/api/search/by-metadata" \
  -H "Content-Type: application/json" \
  -d '{
    "language": "it",
    "size": 10,
    "metadataFilters": {
      "dc:creator": "Sabrina",
      "xmpTPg:NPages": "131",
      "pdf:producer": "OpenOffice.org 2.3"
    }
  }' | jq '.[] | {fileName, metadata: {creator: .metadata["dc:creator"], pages: .metadata["xmpTPg:NPages"]}}'
```

## 6. Ricerca Rapida (GET)

Endpoint semplificato con parametri query string:

```bash
curl "http://localhost:8080/api/search/quick?q=lupo&lang=it&size=5"
```

```bash
curl -s "http://localhost:8080/api/search/quick?q=mare&lang=it&size=3" | jq '.[] | {fileName, score}'
```

## 7. Ricerca Multi-lingua

### Cerca in tutti gli indici (tutte le lingue)

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "wolf",
    "language": "",
    "size": 10
  }' | jq '.[] | {fileName, language, score}'
```

### Cerca in inglese

```bash
curl -s -X POST "http://localhost:8080/api/search/advanced" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "wolf forest",
    "language": "en",
    "size": 5
  }'
```

## Parametri Comuni

- **query**: Testo da cercare (obbligatorio per ricerche testuali)
- **language**: Codice lingua (it, en, fr, de, es) o vuoto per tutte
- **size**: Numero massimo di risultati (default: 10)
- **explain**: Mostra spiegazione dello score (default: false)
- **metadataFilters**: Mappa chiave-valore per filtrare per metadati

## Campi Metadati Comuni

- **dc:creator**: Autore del documento
- **dc:title**: Titolo del documento
- **dcterms:created**: Data di creazione
- **dcterms:modified**: Data di ultima modifica
- **pdf:producer**: Software produttore del PDF
- **pdf:PDFVersion**: Versione PDF
- **xmpTPg:NPages**: Numero di pagine
- **Content-Type**: Tipo MIME del file
- **xmp:CreatorTool**: Tool di creazione

## Note

- La ricerca avanzata raggruppa i risultati per fileName, restituendo solo il chunk con score più alto per ogni documento
- I risultati sono ordinati per score decrescente
- I filtri metadati usano match esatto (term query) e non influenzano lo score
- Usa `jq` per formattare l'output JSON
