# Estrazione dei Capitoli e Gestione nei Chunk

## Panoramica

Quando un documento PDF viene caricato nell'applicazione, il testo viene prima estratto con Apache Tika e poi suddiviso in chunk. A partire dalla versione attuale, il processo riconosce automaticamente le intestazioni di capitolo presenti nel testo e le associa a ciascun chunk prodotto.

---

## 1. Estrazione del testo (Tika)

`DocumentExtractionService` usa Apache Tika (`AutoDetectParser` + `BodyContentHandler`) per estrarre il testo grezzo dal PDF in un'unica stringa continua. In questa fase non viene ancora rilevata alcuna struttura di capitoli; il testo è semplicemente tutto concatenato nell'ordine delle pagine.

---

## 2. Rilevamento dei capitoli (ChunkingUtils)

La classe `ChunkingUtils` è il cuore del rilevamento. Il metodo principale è:

```java
public static List<ChunkEntry> chunkWithChapters(String text)
```

### 2.1 Il pattern regex per le intestazioni

Il rilevamento si basa su un'espressione regolare che cerca, ad inizio riga, le seguenti forme:

| Forma                        | Esempi riconosciuti                              |
|------------------------------|--------------------------------------------------|
| Parola chiave + numero        | `Capitolo 1`, `Chapter III`, `Sezione 2`, `Part IV` |
| Numero decimale + titolo      | `1. Introduzione`, `2.1 Metodi`, `3.4.1 Analisi` |
| Numero romano + titolo        | `I. Prefazione`, `IV. Conclusioni`               |

Le parole chiave riconosciute sono: `Capitolo`, `Chapter`, `Sezione`, `Section`, `Parte`, `Part` (sia in italiano che in inglese, maiuscolo e minuscolo).

Il pattern impone che l'intestazione sia su una **riga propria** (anchor `^` con flag multilinea), per evitare falsi positivi nel corpo del testo.

### 2.2 Suddivisione in sezioni

Una volta trovate tutte le posizioni delle intestazioni, il testo viene diviso così:

```
[testo prima del primo capitolo]  → sezione 0, chapterTitle = ""
[testo capitolo 1]                → sezione 1, chapterTitle = "1. Introduzione"
[testo capitolo 2]                → sezione 2, chapterTitle = "2. Metodi"
...
```

Il testo che precede il primo capitolo (indice, prefazione, note iniziali) viene trattato come **sezione 0 senza titolo** (`chapterTitle = ""`).

### 2.3 Il record ChunkEntry

Ogni chunk prodotto è rappresentato dal record:

```java
public record ChunkEntry(
    int    chunkIndex,    // indice progressivo globale del chunk (0, 1, 2, ...)
    String content,       // testo del chunk
    String chapterTitle,  // titolo del capitolo (vuoto se fuori da un capitolo)
    int    chapterIndex   // indice progressivo del capitolo (0 = prefazione)
)
```

---

## 3. Chunking all'interno di ogni capitolo

Dopo la suddivisione in sezioni, ogni sezione viene a sua volta suddivisa in chunk usando la strategia a **finestra scorrevole**:

- **Dimensione chunk:** 500 parole
- **Overlap:** 100 parole (le ultime 100 parole del chunk N sono le prime 100 del chunk N+1)

L'overlap evita che un concetto a cavallo di due chunk venga tagliato netto e perso. I confini di capitolo **non vengono mai attraversati**: un chunk appartiene sempre e solo a un singolo capitolo.

```
Capitolo 2 (800 parole totali)
  ├── chunk #5  → parole   0–499  | chapterTitle="2. Metodi", chapterIndex=2
  ├── chunk #6  → parole 400–799  | chapterTitle="2. Metodi", chapterIndex=2
  └── (fine capitolo)

Capitolo 3 (300 parole totali)
  └── chunk #7  → parole   0–299  | chapterTitle="3. Risultati", chapterIndex=3
```

L'indice `chunkIndex` è **globale** sul documento, non riparte a zero per ogni capitolo.

---

## 4. Indicizzazione su Elasticsearch

`ElasticsearchIndexService.indexDocument()` itera la lista di `ChunkEntry` e per ogni elemento crea un documento `DocumentChunk` con i campi:

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

Il template Elasticsearch (`create-index-template.sh`) mappa `chapterTitle` come campo `text` con sub-field `keyword`, in modo da poterlo usare sia per la ricerca full-text che per aggregazioni e filtri esatti.

---

## 5. Ricerca e risultati

`SearchResult` include i campi `chapterTitle` e `chapterIndex`, quindi ogni risultato di ricerca riporta in quale capitolo si trova il chunk rilevante. Questo permette al client di:

- **mostrare il titolo del capitolo** accanto all'anteprima del testo trovato
- **filtrare per capitolo** usando un term filter su `chapterTitle.keyword`
- **aggregare per capitolo** per capire in quali sezioni del documento si concentra la risposta a una query

### Esempio di risposta API

```json
{
  "fileName": "ventimila-leghe.pdf",
  "chapterIndex": 12,
  "chapterTitle": "10. Il Nautilus.",
  "chunkIndex": 49,
  "score": 5.529,
  "content": "Il capitano Nemo si avviò e io lo seguii..."
}
```

---

## 6. Limitazioni note

- Il rilevamento è basato su **pattern testuale**: se il PDF ha intestazioni di capitolo in formato immagine (es. titoli in font grafico non selezionabile), non vengono riconosciute.
- Tika linearizza il testo: la formattazione visiva (grassetto, dimensione font) non è disponibile per disambiguare un titolo dal corpo del testo — il rilevamento si affida quindi alla struttura sintattica (numerazione, parole chiave).
- Il campo `page` non viene attualmente popolato: Tika con `BodyContentHandler` non espone facilmente il numero di pagina per ogni porzione di testo.
