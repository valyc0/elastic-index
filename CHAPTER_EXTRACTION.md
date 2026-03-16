# Estrazione dei Capitoli e Gestione nei Chunk

## Panoramica

Quando un documento PDF viene caricato nell'applicazione, il testo viene prima estratto con Apache Tika e poi suddiviso in chunk. Il processo usa una **strategia ibrida** per rilevare i capitoli:

1. **PDFBox outline** — se il PDF contiene segnalibri nativi (TOC embedded), i capitoli vengono estratti direttamente dalla struttura del file, con titoli e pagine esatti.
2. **Fallback regex** — se il PDF non ha outline, i capitoli vengono individuati cercando nel testo le intestazioni di capitolo via espressione regolare.

---

## 1. Estrazione del testo (Tika)

`DocumentExtractionService` usa Apache Tika (`AutoDetectParser` + `BodyContentHandler`) per estrarre il testo grezzo dal PDF in un'unica stringa continua. In parallelo, per i file `.pdf`, viene invocato `PdfOutlineExtractor` per tentare la lettura dell'outline.

Il risultato è un `DocumentExtractionResult` che contiene:
- `text` — testo grezzo completo
- `metadata` — metadati Tika (autore, data, lingua, ecc.)
- `chapters` — lista di `ChapterSection` estratta da PDFBox (null se nessun outline)

Il risultato viene salvato come file JSON in `extracted-documents/` per poter essere reindicizzato senza ri-estrarre il PDF.

---

## 2. Estrazione capitoli tramite PDFBox outline (strategia primaria)

La classe `PdfOutlineExtractor` legge il `PDDocumentOutline` del PDF — l'albero dei segnalibri — che nei PDF ben strutturati mappa ogni capitolo alla sua pagina di destinazione.

### Come funziona

```
PDF Outline
  ├── "1. Introduzione"  → pagina 5
  ├── "2. Metodi"        → pagina 12
  └── "3. Conclusioni"   → pagina 28
```

Per ogni voce dell'outline viene risolto il numero di pagina (`PDPageDestination.getPageNumber()` con fallback su `getPage()`), quindi il testo di quell'intervallo di pagine viene estratto con `PDFTextStripper.setStartPage() / setEndPage()`.

Il testo prima del primo capitolo (es. indice, prefazione) viene incluso come sezione 0 con titolo vuoto.

### Quando viene usato

Solo se il PDF ha un outline con almeno una voce risolvibile a una pagina. In tutti gli altri casi (PDF senza outline, outline con link non risolvibili, errori di parsing) il metodo restituisce lista vuota senza eccezioni e il sistema cade in automatico sul fallback regex.

---

## 3. Rilevamento capitoli tramite regex (fallback)

Quando PDFBox non trova un outline, `ChunkingUtils` analizza il testo grezzo cercando intestazioni di capitolo con un'espressione regolare.

### Pattern riconosciuti

| Forma                        | Esempi                                          |
|------------------------------|-------------------------------------------------|
| Parola chiave + numero       | `Capitolo 1`, `Chapter III`, `Sezione 2`, `PART IV` |
| Numero decimale + titolo     | `1. Introduzione`, `2.1 Metodi`, `3.4.1 Analisi` |
| Numero romano + titolo       | `I. Prefazione`, `IV. Conclusioni`              |

Le parole chiave riconosciute sono: `Capitolo`, `Chapter`, `Sezione`, `Section`, `Parte`, `Part` (italiano e inglese, maiuscolo e minuscolo).

Il pattern impone che l'intestazione sia su una **riga propria** (anchor `^` con flag multilinea), per ridurre i falsi positivi nel corpo del testo.

### Suddivisione in sezioni

```
[testo prima del primo capitolo]  → chapterTitle = "",  chapterIndex = 0
[testo capitolo 1]                → chapterTitle = "1. Introduzione", chapterIndex = 1
[testo capitolo 2]                → chapterTitle = "2. Metodi",       chapterIndex = 2
...
```

---

## 4. Il modello ChapterSection

Sia PDFBox che la regex producono una lista di `ChapterSection`:

```java
public class ChapterSection {
    String title;        // titolo del capitolo ("" per il testo pre-capitolo)
    int    chapterIndex; // indice progressivo (0 = prefazione/indice)
    String text;         // testo completo della sezione
}
```

Questa lista viene salvata nel `DocumentExtractionResult` (campo `chapters`) e persistita nel JSON — quindi è disponibile anche nelle reindicizzazioni senza rileggere il PDF.

---

## 5. Il record ChunkEntry

Ogni chunk prodotto da `ChunkingUtils` è rappresentato dal record:

```java
public record ChunkEntry(
    int    chunkIndex,    // indice progressivo globale del chunk (0, 1, 2, ...)
    String content,       // testo del chunk
    String chapterTitle,  // titolo del capitolo (vuoto se fuori da un capitolo)
    int    chapterIndex   // indice progressivo del capitolo
)
```

I due metodi di produzione dei chunk:

```java
// Da testo grezzo (usa regex internamente)
ChunkingUtils.chunkWithChapters(String text)

// Da sezioni già estratte (PDFBox o regex esterna)
ChunkingUtils.chunkFromSections(List<ChapterSection> sections)
```

---

## 6. Chunking all'interno di ogni sezione

Ogni sezione (capitolo) viene suddivisa in chunk con la strategia a **finestra scorrevole**:

- **Dimensione chunk:** 500 parole
- **Overlap:** 100 parole (le ultime 100 parole del chunk N sono le prime 100 del chunk N+1)

L'overlap evita che un concetto a cavallo di due chunk venga tagliato netto. I confini di capitolo **non vengono mai attraversati**: un chunk appartiene sempre e solo a un singolo capitolo.

```
Capitolo 2 (800 parole totali)
  ├── chunk #5  → parole   0–499  | chapterTitle="2. Metodi", chapterIndex=2
  ├── chunk #6  → parole 400–799  | chapterTitle="2. Metodi", chapterIndex=2

Capitolo 3 (300 parole totali)
  └── chunk #7  → parole   0–299  | chapterTitle="3. Risultati", chapterIndex=3
```

L'indice `chunkIndex` è **globale** sul documento, non riparte a zero per ogni capitolo.

---

## 7. Logica di selezione in ElasticsearchIndexService

```java
public void indexDocument(String documentId, DocumentExtractionResult result) {
    if (result.getChapters() != null && !result.getChapters().isEmpty()) {
        // PDFBox outline disponibile → uso diretto delle sezioni
        chunks = ChunkingUtils.chunkFromSections(result.getChapters());
    } else {
        // Nessun outline → fallback regex sul testo grezzo
        chunks = ChunkingUtils.chunkWithChapters(result.getText());
    }
}
```

---

## 8. Indicizzazione su Elasticsearch

`ElasticsearchIndexService` crea un documento `DocumentChunk` per ogni `ChunkEntry`:

| Campo          | Tipo      | Descrizione                                      |
|----------------|-----------|--------------------------------------------------|
| `id`           | keyword   | UUID univoco del chunk                           |
| `documentId`   | keyword   | UUID del documento padre                         |
| `chunkIndex`   | integer   | Indice globale del chunk nel documento           |
| `content`      | text      | Testo del chunk                                  |
| `language`     | keyword   | Lingua rilevata (es. `it`, `en`)                 |
| `fileName`     | text/kw   | Nome del file originale                          |
| `chapterTitle` | text/kw   | Titolo del capitolo (`""` se nessun capitolo)    |
| `chapterIndex` | integer   | Indice progressivo del capitolo                  |
| `page`         | integer   | Numero di pagina (non popolato da Tika di default)|
| `metadata`     | object    | Metadati Tika del documento (autore, data, ecc.) |

Il template Elasticsearch (`create-index-template.sh`) mappa `chapterTitle` come campo `text` con sub-field `keyword`, per supportare sia la ricerca full-text che le aggregazioni/filtri esatti.

---

## 9. Ricerca e risultati

`SearchResult` include `chapterTitle` e `chapterIndex`, quindi ogni risultato di ricerca riporta in quale capitolo si trova il chunk. Questo permette al client di:

- **mostrare il titolo del capitolo** accanto all'anteprima del testo trovato
- **filtrare per capitolo** tramite term filter su `chapterTitle.keyword`
- **aggregare per capitolo** per capire in quali sezioni si concentra la risposta a una query

### Esempio di risposta API

```json
{
  "fileName": "ventimila-leghe.pdf",
  "chapterIndex": 12,
  "chapterTitle": "10. Il Nautilus.",
  "chunkIndex": 49,
  "score": 5.52,
  "content": "Il capitano Nemo si avviò e io lo seguii. Una doppia porta posta in fondo alla sala si aprì..."
}
```

---

## 10. File coinvolti

| File | Ruolo |
|---|---|
| `service/DocumentExtractionService.java` | Orchestrazione: Tika + invocazione PDFBox |
| `util/PdfOutlineExtractor.java` | Lettura outline PDFBox e estrazione testo per pagine |
| `util/ChunkingUtils.java` | Regex fallback, chunking, produzione ChunkEntry |
| `model/ChapterSection.java` | Modello sezione capitolo (titolo + testo + indice) |
| `model/DocumentExtractionResult.java` | Risultato estrazione con campo `chapters` |
| `model/DocumentChunk.java` | Documento indicizzato con `chapterTitle`/`chapterIndex` |
| `model/SearchResult.java` | Risultato ricerca con `chapterTitle`/`chapterIndex` |
| `service/ElasticsearchIndexService.java` | Logica ibrida PDFBox/regex → indicizzazione |
| `create-index-template.sh` | Mapping ES con `chapterTitle` (text+keyword) e `chapterIndex` (integer) |

---

## 11. Limitazioni note

- **PDF senza outline e senza titoli testuali** — se i capitoli sono immagini (es. font grafico non selezionabile) nessuno dei due metodi funziona.
- **Regex: falsi positivi** — frasi che iniziano con un numero seguito da punto e lettera maiuscola possono essere scambiate per intestazioni di capitolo.
- **PDFBox: outline a più livelli** — vengono letti solo i nodi di primo livello (`getFirstChild()`/`getNextSibling()`); i sotto-capitoli annidati dell'outline vengono ignorati.
- **Campo `page`** — non viene popolato: Tika con `BodyContentHandler` non espone il numero di pagina per singola porzione di testo. Con PDFBox il numero di pagina è noto ma non è attualmente propagato al `DocumentChunk`.
