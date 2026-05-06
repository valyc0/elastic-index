# Analisi: adozione di Spring AI

## Contesto

Adozione di **Spring AI** come framework per la gestione dei componenti AI (embedding, LLM, vector store). Questa nota analizza se l'adozione sia vantaggiosa nel contesto attuale del progetto.

---

## Cosa offre Spring AI

Spring AI è un framework del portfolio Spring che fornisce:

- Interfacce standard per `EmbeddingModel`, `ChatModel`, `ChatClient`
- Integrazione con vector store (Elasticsearch, Pinecone, Weaviate, ecc.)
- `DocumentReader` e pipeline di chunking
- `QuestionAnswerAdvisor` per flussi RAG preconfezionati
- Cambio di provider AI via configurazione (senza modificare il codice)

---

## Equivalenza con il codice già presente

| Funzionalità Spring AI | Equivalente già implementato |
|---|---|
| `EmbeddingModel` interface | `EmbeddingProvider` → `OllamaEmbeddingProvider`, `OpenAiEmbeddingProvider` |
| `ChatClient` / `ChatModel` | `LlmProvider` → `OllamaLlmProvider`, `OpenAiLlmProvider`, `OpenRouterLlmProvider` |
| `VectorStore` (Elasticsearch) | `HybridSearchService` + `SemanticIndexService` |
| `DocumentReader` / chunking | `SemanticChunkingService` + integrazione Docling |
| `QuestionAnswerAdvisor` (RAG pipeline) | `RagService.ask()` completo con sessioni conversazionali |

Il progetto ha già **tutte le astrazioni** che Spring AI fornirebbe, implementate su misura.

---

## Funzionalità custom non coperte da Spring AI

Il valore differenziante del progetto risiede in funzionalità che Spring AI non offre nativamente:

### Hybrid Search con RRF Fusion
`HybridSearchService` esegue BM25 e kNN in parallelo (`CompletableFuture`) e li fonde con **Reciprocal Rank Fusion**:

```
RRF_score(doc) = Σ 1/(k + rank_i(doc))   dove k = 60
```

Spring AI `ElasticsearchVectorStore` supporta solo ricerca kNN pura, senza fusione BM25.

### Second-stage Reranking
Sui top-40 candidati RRF viene applicato un reranker custom con pesi configurabili:
- Term overlap normalizzato (peso 0.25)
- Title match boost (peso 0.10)
- Position boost contestuale per query narrative (peso 2.0)
- RRF score normalizzato (peso 0.65)

### Parsing strutturato via Docling
Il servizio Python Docling estrae sezioni, tabelle, gerarchie di capitoli e metadati strutturati dai PDF. Questa granularità non è replicabile con i `DocumentReader` generici di Spring AI.

### Token Budget Management
`TokenBudgetService` tronca il contesto rispettando il limite di token del modello prima della chiamata LLM.

### Sessioni conversazionali
`ConversationSessionService` mantiene la storia della conversazione con gestione della finestra di contesto.

---

## Quando Spring AI varrebbe la pena

- Progetto **nuovo da zero** senza logica di retrieval custom
- Team che vuole evitare la gestione dei client HTTP verso Ollama/OpenAI
- Necessità di **cambiare provider AI frequentemente** senza modificare il codice
- Casi d'uso RAG semplici (single-vector search senza reranking)

---

## Rischi della migrazione

Adottare Spring AI ora richiederebbe:

1. **Riscrivere `HybridSearchService`** — la componente più critica e differenziante — adattandola all'API `VectorStore`, che non supporta RRF né reranking custom nativamente.
2. Perdita di qualità del retrieval senza benefici concreti in termini di manutenibilità.
3. Costo di migrazione elevato a fronte di un'architettura già matura e testata.

---

## Conclusione

L'adozione di Spring AI è **giustificata per nuovi progetti**, ma in questo contesto il codice custom è già **più avanzato** di quanto Spring AI offrirebbe out-of-the-box. La migrazione comporterebbe una regressione sulla qualità del retrieval senza vantaggi pratici.

Si raccomanda di **mantenere l'architettura attuale** e rivalutare Spring AI solo in caso di refactoring strutturale del modulo di ricerca o di cambio dei requisiti funzionali.
