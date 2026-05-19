# Chunking e Embedding — Dettaglio interno

Spiega **passo per passo** come un documento viene trasformato in chunk indicizzabili con il loro vettore semantico.

> Documentazione correlata: [API Reference](./API_REFERENCE.md)

---

## Indice

1. [Da documento a sezioni (`ChapterSection`)](#1-da-documento-a-sezioni)
2. [Da sezioni a chunk (`SemanticChunkingService`)](#2-da-sezioni-a-chunk)
3. [Il record `ChunkEntry`](#3-il-record-chunkentry)
4. [Filtraggio boilerplate](#4-filtraggio-boilerplate)
5. [Generazione embedding (`EmbeddingProvider`)](#5-generazione-embedding)
6. [Costruzione `SemanticChunk` e bulk indexing](#6-costruzione-semanticchunk-e-bulk-indexing)
7. [Flusso visuale completo](#7-flusso-visuale-completo)
8. [Parametri di tuning](#8-parametri-di-tuning)

---

## 1. Da documento a sezioni

Quando il documento arriva (via Tika o Docling), il primo obiettivo è suddividerlo in **sezioni logiche** prima ancora di fare chunking. Esistono due percorsi:

### Percorso A — Struttura da Docling o PDFBox

Docling restituisce direttamente le sezioni strutturate (titoli gerarchici, tabelle). PDFBox legge l'outline del PDF. Ogni sezione è un `ChapterSection`:

```java
public class ChapterSection {
    String title;        // "Capitolo 3 - La tempesta"
    int chapterIndex;    // 2  (posizione nel documento)
    String text;         // corpo testuale della sezione
}
```

### Percorso B — Fallback regex (`ChunkingUtils.splitByChapters`)

Se il documento non ha struttura nativa, si applica un pattern regex che riconosce intestazioni di capitolo in italiano e inglese:

```
(?m)^[ \t]*(?:
  Capitolo|Chapter|CAPITOLO|Sezione|Section|Parte|Part...  \s+  [IVXLCDM]+|\d+
  | \d{1,2}(\.\d{1,2})*\.\s{1,4}[MAIUSCOLA][^\\n]{2,}      ← es. "2.1 Introduzione"
  | [IVXLCDM]{1,6}\.\s+[MAIUSCOLA][^\\n]{3,}               ← es. "III. Analisi"
)
```

Il testo **prima** del primo capitolo rilevato viene salvato come sezione senza titolo (prefazione/intro).

---

## 2. Da sezioni a chunk

Una volta ottenute le sezioni, `SemanticChunkingService` trasforma ogni sezione in uno o più **chunk**. Il metodo centrale è `chunkTextSemantically`.

### Algoritmo in 4 passi

#### Passo 1 — Split in paragrafi poi in frasi

Il testo della sezione viene prima diviso sulle righe vuote (`\n{2,}`) per ottenere i paragrafi. Ogni paragrafo viene poi diviso sui confini di frase (`. `, `! `, `? `, `.\n`):

```
Testo sezione
    ↓ split \n{2,}
[paragrafo1, paragrafo2, ...]
    ↓ split (?<=[.!?])\s+
[frase1, frase2, frase3, frase4, ...]
```

#### Passo 2 — Accumulo frasi fino al limite

Le frasi vengono accumulate in un buffer `currentChunk`. Quando aggiungere la frase successiva supererebbe `maxWordsPerChunk` (default **400 parole**), il buffer viene emesso come chunk e si riparte.

```
frasi:   [F1  F2  F3  F4  F5  F6  F7  F8]
              ← ~400 parole →
chunks:  [F1  F2  F3  F4]    [F3  F4  F5  F6  F7  F8]
                               ↑── overlap ──↑
```

#### Passo 3 — Overlap controllato

Le ultime `overlapSentences` frasi (default **2**) del chunk appena emesso vengono **riportate come prefisso** del chunk successivo. Questo evita che un concetto che si sviluppa a cavallo tra due chunk risulti recuperabile solo da uno dei due.

```java
private List<String> buildOverlap(List<String> currentChunk) {
    int start = Math.max(0, currentChunk.size() - overlapSentences);
    return new ArrayList<>(currentChunk.subList(start, currentChunk.size()));
}
```

#### Passo 4 — Gestione casi limite

| Caso | Comportamento |
|------|---------------|
| Frase singola > 400 parole | Spezzata a sliding window per parole (senza confini semantici) |
| Residuo finale < `minChunkWords` (30 parole) | Fuso con il chunk precedente invece di creare un chunk quasi vuoto |

---

## 3. Il record `ChunkEntry`

Ogni chunk prodotto è un **record immutabile** con quattro campi:

```java
public record ChunkEntry(
    int    chunkIndex,    // posizione globale nel documento (0, 1, 2, ...)
    String content,       // testo del chunk (max ~400 parole)
    String chapterTitle,  // titolo sezione di appartenenza (può essere "")
    int    chapterIndex   // indice sezione di appartenenza
) {}
```

`chapterTitle` e `chapterIndex` vengono conservati nell'indice e usati nel **reranking**: un chunk il cui titolo contiene i termini della query riceve un punteggio bonus del 10%.

---

## 4. Filtraggio boilerplate

Prima di generare gli embedding, `SemanticIndexService` filtra i chunk che non portano valore informativo:

| Criterio | Regola | Esempio scartato |
|----------|--------|-----------------|
| Titolo da indice/sommario | `chapterTitle` matcha `(?i)pagina\s+\d+` | `"PARTE PRIMA pagina 16"` |
| Chunk vuoto | `content == null` | — |
| Testo troppo breve | Parole nel contenuto `< 10` | `"Fine capitolo."` |

```java
private static final Pattern TOC_TITLE_PATTERN = Pattern.compile("(?i)pagina\\s+\\d+");
private static final int MIN_CONTENT_WORDS = 10;
```

Il filtraggio avviene **prima** della chiamata al modello embedding, risparmiando tempo e costi API.

---

## 5. Generazione embedding

L'interfaccia `EmbeddingProvider` espone due metodi:

```java
List<Float>        embed(String text)           // singolo vettore
List<List<Float>>  embedBatch(List<String> texts) // lista di vettori
```

`SemanticIndexService` chiama **sempre** `embedBatch` su tutti i testi in una volta sola, **prima** di modificare l'indice. Se la generazione fallisce, l'indice rimane integro (nessun partial update).

### Provider Ollama (`nomic-embed-text`, 768 dims)

Attivo di default (`embedding.provider=ollama`). Usa due endpoint distinti a seconda del contesto:

| Endpoint Ollama | Quando usato | Body della request |
|-----------------|--------------|--------------------|
| `POST /api/embeddings` | Singolo testo (query a runtime) | `{"model":"nomic-embed-text","prompt":"...","keep_alive":-1}` |
| `POST /api/embed` | Batch durante l'indicizzazione | `{"model":"nomic-embed-text","input":["t1","t2",...],"keep_alive":-1}` |

**`keep_alive: -1`** mantiene il modello caricato in RAM tra i sotto-batch. Senza questa opzione, Ollama scaricherebbe il modello dopo ogni chiamata aggiungendo 5-10 secondi per batch.

Il batch viene suddiviso in **sotto-gruppi** da `batchSize` (default **184** chunk per request HTTP) per evitare timeout su documenti molto grandi:

```java
for (int start = 0; start < texts.size(); start += batchSize) {
    List<String> subBatch = texts.subList(start, Math.min(start + batchSize, texts.size()));
    results.addAll(embedBatchInternal(subBatch));
}
```

Timeout adattivo per sotto-batch: `min(n_testi × 10s + 60s, 600s)`.

**Risposta Ollama** (`/api/embed`):
```json
{
  "embeddings": [
    [0.12, -0.87, 0.03, ...],   ← 768 float per testo
    [0.45,  0.11, -0.22, ...],
    ...
  ]
}
```

---

### Provider OpenAI (`text-embedding-3-small`, 1536 dims)

Attivo con `embedding.provider=openai`. Invia **tutti** i testi in un'unica request HTTP:

```
POST https://api.openai.com/v1/embeddings
Authorization: Bearer <api_key>
Content-Type: application/json

{
  "model": "text-embedding-3-small",
  "input": ["testo chunk 1", "testo chunk 2", ...]
}
```

**Risposta OpenAI**:
```json
{
  "data": [
    { "index": 0, "embedding": [0.003, -0.012, ...] },
    { "index": 1, "embedding": [0.091,  0.034, ...] }
  ]
}
```

La lista `data[].embedding` è nello **stesso ordine** dell'array `input`, garantendo la corrispondenza chunk ↔ vettore.

---

## 6. Costruzione `SemanticChunk` e bulk indexing

Per ogni chunk, `SemanticIndexService` assembla un documento `SemanticChunk`:

```java
SemanticChunk chunk = new SemanticChunk();
chunk.setDocumentId(documentId);              // UUID univoco del documento
chunk.setFileName(result.getFileName());      // "Piccole donne.pdf"
chunk.setChunkIndex(entry.chunkIndex());      // posizione globale nel documento
chunk.setContent(entry.content());            // testo del chunk (~400 parole)
chunk.setChapterTitle(entry.chapterTitle());  // "Capitolo 3 - La tempesta"
chunk.setChapterIndex(entry.chapterIndex());  // 2
chunk.setContentEmbedding(embeddings.get(i)); // [0.12, -0.87, ..., 0.03]  768 o 1536 dims
```

Il campo `content_embedding` è il **dense vector** su cui Elasticsearch esegue la kNN query durante il retrieval.

### Deduplicazione

Prima del bulk insert, tutti i chunk esistenti per lo stesso file vengono eliminati:

```java
elasticsearchClient.deleteByQuery(d -> d
    .index(semanticIndex)
    .query(q -> q.term(t -> t.field("fileName.keyword").value(fileName)))
);
```

Questo garantisce che ri-indicizzare lo stesso file (es. dopo una modifica manuale delle sezioni) non generi duplicati.

### Bulk insert

Tutti i chunk vengono inviati in una **singola `BulkRequest`** a Elasticsearch:

```
PUT semantic_docs/_bulk
{"index":{"_id":"uuid-chunk-1"}}
{"documentId":"...","fileName":"Piccole donne.pdf","chunkIndex":0,"content":"...","chapterTitle":"Cap 1","chapterIndex":0,"content_embedding":[0.12,-0.87,...]}
{"index":{"_id":"uuid-chunk-2"}}
{"documentId":"...","fileName":"Piccole donne.pdf","chunkIndex":1,"content":"...","chapterTitle":"Cap 1","chapterIndex":0,"content_embedding":[0.45, 0.11,...]}
...
```

In caso di errori parziali (alcuni chunk rifiutati da ES), il servizio:
- logga il numero di chunk falliti
- se **tutti** i chunk falliscono → lancia `RuntimeException` (il file risulta non indicizzato)
- se solo **alcuni** falliscono → logga l'errore ma ritorna il conteggio dei chunk riusciti

---

## 7. Flusso visuale completo

```
Documento (PDF / DOCX / TXT / ...)
        │
        ▼
┌───────────────────────────────────────┐
│  Parser                               │
│  Tika  → testo grezzo + metadati      │
│  Docling → sezioni strutturate        │
│  PDFBox  → outline capitoli PDF       │
└──────────────────┬────────────────────┘
                   │
        ┌──────────▼──────────┐
        │  Ha capitoli/sezioni?│
        └──────┬──────┬────────┘
              SÌ     NO
               │      │
               │      ▼
               │  ChunkingUtils.splitByChapters()
               │  regex CHAPTER_HEADING
               │      │
               ▼      ▼
       List<ChapterSection>
       [{ title:"Cap 1", idx:0, text:"..." },
        { title:"Cap 2", idx:1, text:"..." }, ...]
               │
               ▼
┌──────────────────────────────────────────┐
│  SemanticChunkingService                 │
│  .chunkSections(sections)                │
│                                          │
│  Per ogni sezione:                       │
│    1. split paragrafi  (\n{2,})          │
│    2. split frasi      ([.!?]\s)         │
│    3. accumula frasi   ≤ 400 parole      │
│    4. overlap: riporta ultime 2 frasi    │
│       nel chunk successivo              │
└──────────────────┬───────────────────────┘
                   │
                   ▼
         List<ChunkEntry>
         [{ idx:0, "Le quattro sorelle...", "Cap 1", 0 },
          { idx:1, "Jo era la più...",      "Cap 1", 0 },  ← overlap
          { idx:2, "Quando arrivò...",      "Cap 2", 1 },
          ...]
                   │
                   ▼
┌──────────────────────────────────────────┐
│  Filtraggio boilerplate                  │
│  titoli "pagina N"   → scartato          │
│  contenuto < 10 parole → scartato        │
└──────────────────┬───────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────┐
│  EmbeddingProvider.embedBatch(texts)     │
│                                          │
│  Ollama  → POST /api/embed               │
│            sotto-batch da 184 chunk      │
│            keep_alive=-1 (RAM)           │
│                                          │
│  OpenAI  → POST /v1/embeddings           │
│            tutti i chunk in 1 request    │
└──────────────────┬───────────────────────┘
                   │
                   ▼
    List<List<Float>>
    [[0.12,-0.87,...], [0.45,0.11,...], ...]
    768 dims (Ollama) o 1536 dims (OpenAI)
                   │
                   ▼
┌──────────────────────────────────────────┐
│  SemanticIndexService                    │
│                                          │
│  1. deleteByQuery  fileName.keyword      │ ← deduplicazione
│  2. for each chunk → SemanticChunk {     │
│       documentId, fileName,              │
│       chunkIndex,  content,              │
│       chapterTitle, chapterIndex,        │
│       content_embedding: [Float...]  }   │
│  3. elasticsearchClient.bulk(request)    │
└──────────────────────────────────────────┘
                   │
                   ▼
     Elasticsearch  index: semantic_docs
     ┌─────────────────────────────────┐
     │  doc uuid-1                     │
     │  fileName: "Piccole donne.pdf"  │
     │  content:  "Le quattro..."      │
     │  chapterTitle: "Cap 1"          │
     │  content_embedding: [768 float] │
     └─────────────────────────────────┘
     ┌─────────────────────────────────┐
     │  doc uuid-2  ...                │
     └─────────────────────────────────┘
          ↑ pronti per BM25 + kNN
```

---

## 8. Parametri di tuning

| Proprietà | Default | Effetto pratico |
|-----------|---------|-----------------|
| `chunking.max-words` | `400` | Aumentare → chunk più grandi, più contesto per domanda ma rischio di annegare il segnale rilevante. Diminuire → più precisione, ma meno contesto. |
| `chunking.overlap-sentences` | `2` | Aumentare → meno perdita di contesto ai confini. A `0` ogni chunk è completamente indipendente. |
| `chunking.min-words` | `30` | Soglia sotto cui il chunk viene fuso con il precedente. Abbassare per preservare sezioni brevissime (es. definizioni). |
| `ollama.embed.batch-size` | `184` | Ridurre se Ollama va in timeout o OOM su GPU con poca VRAM. |
| `ollama.embed.model` | `nomic-embed-text` | Sostituibile con qualsiasi modello Ollama (es. `mxbai-embed-large`). |
| `ollama.embed.dimensions` | `768` | **Deve corrispondere** al mapping ES del campo `content_embedding`. Cambiare richiede re-index completo. |
| `openai.embed.model` | `text-embedding-3-small` | `text-embedding-3-large` offre qualità superiore (3072 dims) a costo maggiore. |
| `openai.embed.dimensions` | `1536` | Come sopra: deve corrispondere al mapping ES. |
| `semantic.index.name` | `semantic_docs` | Nome indice Elasticsearch target. |
