# Analisi del sistema RAG

## Sintesi esecutiva

Il progetto implementa un **vero sistema RAG** e non una semplice chat con search.
Il flusso principale di ingestion e retrieval è corretto:

1. parsing documentale strutturato con Docling o estrazione con Tika
2. chunking semantico del contenuto
3. generazione degli embedding e indicizzazione su Elasticsearch
4. retrieval ibrido con BM25 + kNN
5. costruzione del contesto e generazione della risposta con LLM

La classificazione più corretta oggi è:

- **good RAG in consolidamento** sul piano funzionale
- **non ancora production RAG** sul piano qualitativo e operativo

Il cuore retrieval-augmented è presente e coerente. Una parte del secondo strato di maturità è già stata introdotta:

- reranking di secondo stadio sopra i candidati RRF
- grounding più rigido con soglie di evidenza e citazioni obbligatorie
- budgeting del contesto basato su tokenizzazione reale con fallback controllato

Restano ancora da completare soprattutto evaluation sistematica, test di qualità e ulteriore hardening del controllo della risposta.

---

## Perché questo codice è davvero un RAG

Un sistema può essere chiamato RAG se la risposta finale dell'LLM dipende da un retrieval esplicito su una base documentale esterna, eseguito prima della generazione. In questo repository questa proprietà c'è.

### 1. I documenti vengono trasformati in unità recuperabili

Il contenuto non viene passato interamente al modello, ma viene:

- estratto e strutturato
- suddiviso in chunk
- arricchito con metadati
- trasformato in embedding
- indicizzato per retrieval successivo

Questo comportamento è implementato soprattutto in:

- [my-app/src/main/java/io/bootify/my_app/service/SemanticChunkingService.java](my-app/src/main/java/io/bootify/my_app/service/SemanticChunkingService.java)
- [my-app/src/main/java/io/bootify/my_app/service/SemanticIndexService.java](my-app/src/main/java/io/bootify/my_app/service/SemanticIndexService.java)
- [docling-service/main.py](docling-service/main.py)

### 2. Il retrieval avviene davvero prima della generazione

La query dell'utente viene usata per cercare chunk rilevanti nel corpus tramite due segnali distinti:

- full-text BM25
- similarità vettoriale kNN

I risultati vengono poi fusi con RRF. Questo è retrieval reale, non un semplice prompt con testo allegato manualmente.

Il cuore di questa parte è in:

- [my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java](my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java)

### 3. L'LLM risponde usando il contesto recuperato

Il contesto documentale recuperato viene serializzato in prompt e inoltrato al modello come base per la risposta. Questo avviene in:

- [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java)

La risposta finale restituisce anche l'elenco delle fonti usate, tramite:

- [my-app/src/main/java/io/bootify/my_app/model/RagAnswer.java](my-app/src/main/java/io/bootify/my_app/model/RagAnswer.java)

---

## Architettura attuale

### Pipeline di ingestion

Il lato ingestion è coerente e abbastanza pulito:

1. il file viene caricato via endpoint HTTP
2. Docling estrae sezioni, tabelle e struttura gerarchica
3. le sezioni vengono convertite in chunk semanticamente più piccoli
4. ogni chunk viene embedded
5. i chunk vengono indicizzati nell'indice semantico Elasticsearch

Punti forti:

- preservazione della struttura del documento
- chunking con overlap
- deduplicazione per fileName prima della reindicizzazione
- modello di embedding astratto e sostituibile

### Pipeline di retrieval e generation

Il lato query è anch'esso coerente:

1. viene ricevuta la domanda utente
2. viene eseguito retrieval ibrido sui chunk indicizzati
3. vengono selezionati i risultati top-K
4. viene costruito un contesto testuale con metadati e contenuto
5. il contesto viene passato all'LLM
6. la risposta viene restituita insieme alle fonti

Punti forti:

- retrieval hybrid invece di solo vector search
- fusione RRF con secondo stadio di reranking
- fallback parziale se uno dei due canali di search fallisce
- risoluzione del filtro `fileName` più robusta anche per hint parziali o alfanumerici come `a52s`
- supporto a sessione conversazionale server-side
- guardie di grounding prima e dopo la chiamata LLM
- contesto tagliato in base a token invece che a parole

---

## Cosa sono reranking e grounding

Questi due concetti sono importanti perché rappresentano il passaggio da un RAG semplicemente funzionante a un RAG più affidabile.

### Cos'è il reranking

Il reranking è un **secondo ordinamento dei risultati recuperati**.

In un RAG il primo retrieval produce di solito una lista di chunk candidati. Anche se questa lista è buona, spesso non è ancora l'ordinamento migliore da dare all'LLM. Il reranking serve proprio a questo: prendere i candidati già recuperati e riordinarli in modo più preciso.

In altre parole:

- il retrieval risponde alla domanda: "quali chunk potrebbero essere rilevanti?"
- il reranking risponde alla domanda: "tra questi chunk, quali sono davvero i migliori da mettere davanti all'LLM?"

### A cosa serve il reranking

Serve a migliorare la qualità del contesto finale che entra nel prompt.

I benefici attesi sono:

- ridurre chunk marginali o rumorosi nel top finale
- dare priorità ai chunk che rispondono più direttamente alla query
- migliorare precisione e grounding della risposta
- usare meglio il budget di contesto, perché i primi chunk sono mediamente più forti

In pratica il reranking non cambia il corpus e non cambia la logica dell'LLM: migliora la scelta delle fonti che l'LLM vede.

### Come è stato implementato il reranking in questo progetto

L'implementazione è in [my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java](my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java).

Il flusso attuale è questo:

1. la query viene cercata con BM25
2. la stessa query viene cercata con kNN vettoriale
3. i due insiemi vengono fusi con RRF
4. i migliori candidati RRF passano in un **secondo stadio di reranking**
5. solo il top finale viene restituito a RagService

Il reranker attuale è intenzionalmente leggero e senza dipendenze pesanti. Usa tre segnali principali:

- score RRF normalizzato
- overlap tra i termini significativi della query e il contenuto del chunk
- match tra i termini della query e il titolo della sezione o il nome file

È presente anche un piccolo boost per match di frase quasi esatta.

Questa scelta ha un obiettivo pragmatico: migliorare subito il top finale senza introdurre un cross-encoder o una chiamata LLM aggiuntiva nel retrieval path.

### Limite attuale del reranking

Il reranker attuale è utile, ma resta euristico.

Non è ancora un reranker semantico forte come:

- cross-encoder dedicato query-document
- reranker transformer addestrato
- reranker LLM-based sui top candidati

Quindi oggi il suo ruolo è:

- ripulire il top finale
- non sostituire un vero secondo stadio semantico di alta qualità

### Cos'è il grounding

Il grounding è il meccanismo con cui il sistema si assicura che la risposta sia **ancorata alle fonti recuperate** e non a conoscenza inventata o generica del modello.

In un RAG il retrieval da solo non basta. Anche se recuperi chunk giusti, un LLM può comunque:

- generalizzare troppo
- completare con conoscenza esterna
- mescolare parti corrette e parti inventate

Il grounding serve a ridurre questo rischio.

In altre parole:

- il retrieval dice "ecco le fonti candidate"
- il grounding dice "la risposta finale deve dipendere davvero da queste fonti"

### A cosa serve il grounding

Serve a rendere la risposta più affidabile.

I benefici attesi sono:

- meno hallucination
- maggiore tracciabilità della risposta
- comportamento più prudente quando le fonti sono deboli
- distinzione più netta tra risposta supportata e risposta non supportata

### Come è stato implementato il grounding in questo progetto

L'implementazione è in [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java).

Il grounding oggi avviene in due momenti.

#### 1. Grounding prima della chiamata LLM

Prima di invocare il modello il servizio valuta se il retrieval ha prodotto abbastanza evidenza.

I controlli introdotti sono:

- soglia minima di score del miglior chunk
- numero minimo di fonti non vuote
- retry retrieval con query expansion solo se l'evidenza iniziale è debole

Se anche dopo questo controllo l'evidenza resta insufficiente, il sistema **non chiama l'LLM** e restituisce direttamente un fallback esplicito.

Questo è importante perché evita una categoria classica di errori RAG: lasciare il modello libero di rispondere anche quando il retrieval non ha davvero trovato abbastanza supporto.

#### 2. Grounding dopo la chiamata LLM

Dopo la generazione il servizio controlla anche il formato minimo della risposta.

Se la configurazione richiede citazioni, la risposta deve contenere riferimenti nel formato `[FONTE N]`. Se la risposta non contiene citazioni, viene trattata come non sufficientemente grounded e degradata a caso di evidenza insufficiente.

Questa non è ancora una verifica semantica profonda, ma è già un vincolo concreto sul comportamento dell'LLM.

### Parametri di grounding introdotti

I parametri principali sono in [my-app/src/main/resources/application.properties](my-app/src/main/resources/application.properties):

- `rag.grounding.min-score`
- `rag.grounding.min-sources`
- `rag.grounding.require-citations`

Questi parametri permettono di rendere il sistema più permissivo o più severo senza cambiare codice.

### Limite attuale del grounding

Il grounding attuale è più forte di prima, ma non è ancora completo.

Oggi il sistema verifica soprattutto:

- che ci sia abbastanza evidenza retrieval-side
- che la risposta contenga citazioni nel formato atteso

Non verifica ancora in modo semantico che:

- ogni affermazione importante della risposta sia supportata
- ogni citazione punti davvero a una fonte pertinente
- la risposta non abbia aggiunto dettagli non presenti nei chunk

Questo è il passo successivo verso uno strong RAG.

---

## Valutazione per area

## Verifica runtime eseguita

La verifica non è stata solo statica o di compilazione. Il sistema è stato provato a runtime contro il backend attivo tramite `query-rag.sh`, `/api/rag/search`, `/api/rag/health` e `/api/rag/documents`.

### Documenti effettivamente indicizzati

Al momento della verifica risultavano presenti almeno questi documenti:

- `Zanna Bianca (1).pdf`
- `a52s.pdf`

### Casi verificati su corpus narrativo

Test eseguiti:

- query positiva: `Chi è Zanna Bianca?`
- query positiva: `Chi è Kiche?`
- query negativa intenzionale: `Qual è il numero di telaio dell'automobile di Zanna Bianca?`

Esito:

- le query positive hanno restituito risposte corrette e supportate da fonti
- la query negativa è stata bloccata correttamente con fallback di evidenza insufficiente

Questo conferma che il grounding non è solo configurato, ma sta davvero filtrando i casi in cui il documento non supporta la risposta.

### Casi verificati su corpus tecnico

Test eseguiti:

- `Come si disconnette il Samsung account?`
- `Come si attiva il Wi-Fi Direct?`

Esito iniziale:

- con filtro esatto `a52s.pdf` il sistema rispondeva correttamente
- con filtro parziale `a52s` il sistema andava in fallback, non perché il RAG fosse sbagliato, ma perché la risoluzione del nome file era troppo debole

Correzione introdotta:

- in [my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java](my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java) `resolveFileName()` ora prova prima una risoluzione locale sui documenti effettivamente indicizzati usando matching normalizzato, e usa Elasticsearch solo come fallback

Esito finale dopo il riavvio dell'applicazione:

- la query `Come si disconnette il Samsung account?` con filtro `a52s` restituisce correttamente chunk da `a52s.pdf` e risposta grounded
- il search endpoint con `fileName="a52s"` restituisce risultati reali del manuale Samsung

Questa parte è importante perché dimostra che il problema osservato non era nel retrieval ibrido, nel reranking o nel grounding, ma in una fase a monte di risoluzione del filtro documentale. Dopo la fix, anche questo tratto del flusso è coerente.

### Conclusione della verifica pratica

I test reali confermano che oggi il sistema si comporta correttamente su quattro scenari distinti:

- query positiva con fonti supportanti
- query negativa con fallback prudente
- retrieval con reranking attivo
- grounding operativo anche con filtro documentale parziale, una volta corretta la risoluzione del fileName

### Chunking e context budgeting: 7.5/10

Il chunking in [my-app/src/main/java/io/bootify/my_app/service/SemanticChunkingService.java](my-app/src/main/java/io/bootify/my_app/service/SemanticChunkingService.java) è buono per un RAG classico:

- divide per frasi e paragrafi
- conserva il titolo della sezione di origine
- usa overlap per non perdere contesto ai bordi

Miglioramento già introdotto:

- il budgeting del contesto ora usa tokenizzazione reale tramite tokenizer model-aware dove disponibile, con fallback controllato per modelli non riconosciuti

Limite principale residuo:

- il fallback token-based per modelli Ollama e molti modelli OpenRouter non coincide sempre con il tokenizer nativo del provider

### Indexing: 7/10

La pipeline in [my-app/src/main/java/io/bootify/my_app/service/SemanticIndexService.java](my-app/src/main/java/io/bootify/my_app/service/SemanticIndexService.java) è sensata:

- genera gli embedding prima di toccare l'indice
- filtra chunk di bassa qualità o frontmatter
- indicizza in bulk

Limiti principali:

- non emerge una validazione forte del mapping indice rispetto alle dimensioni embedding
- la gestione dei fallimenti parziali del bulk non è ancora realmente robusta

### Retrieval: 8/10

La parte più forte del progetto è [my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java](my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java):

- BM25 + kNN è una scelta solida
- RRF è corretto e robusto
- i filtri metadata sono presenti
- esiste anche un boost dedicato a query sul finale narrativo
- è presente un reranker leggero di secondo stadio che ripulisce il top finale con segnali lessicali e strutturali

Limite principale:

- il reranker attuale è euristico e leggero, non ancora un cross-encoder o reranker semantico forte

### Prompting e generation: 6.5/10

In [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java) il prompt è chiaro e impone alcune regole utili, ma la strategia resta abbastanza semplice.

Miglioramenti già introdotti:

- il servizio rifiuta di chiamare l'LLM se l'evidenza retrieval è troppo debole
- se richiesto, una risposta senza citazioni viene degradata a caso di evidenza insufficiente

Limiti principali residui:

- il parsing dell'output resta ancora euristico
- il controllo di fedeltà è più forte ma non ancora verificato da un post-check semantico della risposta

### Grounding: 7/10

Le fonti non sono più solo restituite: il sistema ora verifica che ci sia una soglia minima di evidenza retrieval e può richiedere citazioni nella risposta.

In pratica oggi il sistema è:

- source-aware
- parzialmente source-enforced

Il confine residuo verso uno strong RAG è che manca ancora una verifica semantica esplicita che ogni affermazione importante sia davvero supportata dalle fonti citate.

### Evaluation: 2/10

Non emerge una suite strutturata di valutazione del retrieval e della risposta finale.

Senza evaluation non puoi sapere se:

- il top-K recuperato è davvero quello giusto
- il reranking migliora davvero
- il modello sta rispondendo con fedeltà alle fonti
- una modifica al prompt peggiora o migliora la qualità

---

## Cosa manca rispetto a un RAG maturo

### 1. Reranker di secondo stadio

Questa parte è stata introdotta in forma leggera in [my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java](my-app/src/main/java/io/bootify/my_app/service/HybridSearchService.java).

Oggi il sistema:

- recupera candidati con BM25 + kNN
- li fonde con RRF
- applica un secondo stadio di reranking sui migliori candidati usando score RRF normalizzato, overlap query-contenuto e match su titolo o file

In termini pratici, questo significa che il sistema non si fida ciecamente del ranking prodotto dalla sola fusione iniziale, ma prova a migliorare il top finale prima che i chunk entrino nel prompt.

Il passo successivo per rendere il reranking molto più forte è passare a un secondo stadio semantico, per esempio:

- cross-encoder reranker
- reranker LLM-based sui top N chunk
- rescoring dedicato query-chunk

Effetto atteso:

- migliore precisione delle fonti passate al modello
- meno chunk marginali nel contesto finale
- maggiore qualità su query ambigue o verbose

### 2. Groundedness enforcement

In [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java) il sistema ora applica guardie di grounding più rigide.

Adesso il servizio:

- valuta una soglia minima di score e numero minimo di fonti prima della chiamata LLM
- prova un retry retrieval con query expansion solo se necessario
- può imporre la presenza di citazioni nel formato [FONTE N]
- restituisce fallback esplicito se l'evidenza resta insufficiente

In termini pratici, questo significa che il sistema non accetta più automaticamente una risposta solo perché il modello ha prodotto testo fluido: prima controlla se c'è abbastanza base documentale, e poi controlla che la risposta mostri almeno un segnale minimo di ancoraggio alle fonti.

Un RAG ancora più maturo dovrebbe aggiungere:

- richiedere citazioni obbligatorie per le affermazioni fattuali
- rifiutare o attenuare la risposta se l'evidenza è insufficiente
- distinguere chiaramente tra risposta supportata e risposta non supportata

Effetto atteso:

- meno hallucination
- maggiore affidabilità percepita
- risposta più auditabile

### 3. Budgeting del contesto basato su token reali

Questo punto è stato corretto in [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java) e [my-app/src/main/java/io/bootify/my_app/service/TokenBudgetService.java](my-app/src/main/java/io/bootify/my_app/service/TokenBudgetService.java).

Adesso il servizio:

- stima i token del contesto usando tokenizzazione reale
- usa tokenizzazione model-aware per i modelli OpenAI noti
- usa un fallback coerente su `cl100k_base` per modelli non riconosciuti

Il limite residuo è questo:

- il fallback è robusto ma non perfettamente identico ai tokenizer nativi di molti modelli Ollama o OpenRouter

Effetto atteso del miglioramento:

- prompt più stabili
- meno overflow di contesto
- scelta più controllata delle fonti incluse

### 4. Policy di retrieval più rigida sul caso low-evidence

Oggi la risposta finale dipende ancora molto dall'LLM. In [my-app/src/main/java/io/bootify/my_app/service/RagService.java](my-app/src/main/java/io/bootify/my_app/service/RagService.java) il sistema prova anche una query expansion, ma la decisione finale è ancora abbastanza soft.

Manca una policy più netta su casi come:

- risultati troppo deboli
- risultati quasi duplicati
- troppi chunk della stessa sezione
- mismatch tra domanda e contesto

Un RAG più maturo dovrebbe introdurre:

- soglie minime di evidenza
- deduplica dei chunk simili
- diversificazione per capitolo o documento
- abort controllato prima della generazione

### 5. Evaluation suite

Questa è la mancanza più strutturale.

Senza una batteria di query di riferimento con risultati attesi non puoi misurare seriamente:

- retrieval recall@k
- precisione delle fonti
- ranking quality
- answer faithfulness
- regressioni dopo modifiche al prompting o al retrieval

---

## Miglioramenti prioritari consigliati

Di seguito l'ordine consigliato, per impatto reale sul sistema.

### Priorità 1 — rendere il reranker semantico

Stato attuale:

- completato in forma leggera

Intervento suggerito:

- sostituire il reranker euristico con un cross-encoder o un reranker semantico vero
- mantenere l'attuale secondo stadio come fallback economico

### Priorità 2 — rendere il grounding verificabile

Stato attuale:

- completato in prima forma con soglie retrieval-side e citazioni obbligatorie

Intervento suggerito:

- aggiungere un post-check di faithfulness tra risposta e fonti
- distinguere meglio tra citazione presente e citazione davvero pertinente

### Priorità 3 — affinare il token budgeting per provider non OpenAI

Stato attuale:

- completato in prima forma con tokenizzazione reale e trimming su token

Intervento suggerito:

- aggiungere tokenizer provider-specifici dove disponibili
- allineare il budget anche ai token di output del modello

### Priorità 4 — rafforzare la policy retrieval-side

Motivo:

- il retrieval deve decidere meglio quando ha abbastanza evidenza

Intervento suggerito:

- soglie minime
- deduplica chunk simili
- diversificazione del contesto
- gestione esplicita dei casi low-evidence prima del prompt

### Priorità 5 — introdurre evaluation automatica

Motivo:

- senza benchmark interni ogni miglioramento resta impressionistico

Intervento suggerito:

- creare un set di query gold
- associare fonti attese
- misurare retrieval e answer quality a ogni modifica importante

---

## Cosa migliorerei anche fuori dal puro RAG

Questi aspetti non decidono se il sistema è o non è un RAG, ma incidono sulla maturità complessiva.

### Deployment e runtime

Attualmente la topologia è mista:

- Elasticsearch, Ollama e Docling in container
- Spring Boot avviato fuori Compose

Per un sistema robusto conviene uniformare tutto sotto Compose.

File rilevanti:

- [docker-compose.yml](docker-compose.yml)
- [start-all.sh](start-all.sh)

### Security e hardening

Oggi la postura è da ambiente di sviluppo:

- Elasticsearch senza security attiva
- endpoint REST senza autenticazione
- gestione errori ancora troppo locale nei controller

File rilevanti:

- [docker-compose.yml](docker-compose.yml)
- [my-app/src/main/java/io/bootify/my_app/rest/RagController.java](my-app/src/main/java/io/bootify/my_app/rest/RagController.java)
- [my-app/src/main/java/io/bootify/my_app/rest/DoclingController.java](my-app/src/main/java/io/bootify/my_app/rest/DoclingController.java)
- [my-app/src/main/java/io/bootify/my_app/rest/IndexController.java](my-app/src/main/java/io/bootify/my_app/rest/IndexController.java)

### Observability

Per capire davvero un RAG in esercizio servono:

- metriche retrieval
- metriche latenza LLM
- tasso di low-evidence
- tasso di fallback
- log strutturati con correlation id

Senza questi segnali non puoi migliorare la qualità in modo disciplinato.

---

## Giudizio finale

### Risposta breve

Sì, il codice rispecchia un vero RAG.

### Risposta precisa

È un **good RAG in consolidamento** con retrieval ibrido corretto, pipeline di ingestion credibile, reranking di secondo stadio, grounding più stretto e contesto documentale realmente usato nella generazione.

Non è ancora un **strong RAG** perché mancano:

- reranking semantico forte
- verifica semantica della groundedness
- retrieval policy più severa
- evaluation sistematica

Non è ancora un **production RAG** perché, oltre ai punti sopra, mancano anche capability operative trasversali di sicurezza, resilienza, osservabilità e deployment coerente.

### Sintesi conclusiva

Il progetto ha una base tecnica buona.
La parte più riuscita oggi è il retrieval.
La parte più debole è il controllo della risposta finale.

Se dovessi scegliere una sola direzione di miglioramento, partirei da questa sequenza:

1. reranker
2. grounded generation
3. token budgeting reale
4. retrieval policy più rigorosa
5. evaluation suite