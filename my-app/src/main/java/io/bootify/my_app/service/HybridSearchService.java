package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.model.SemanticChunk;
import io.bootify.my_app.service.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Servizio di ricerca ibrida che combina BM25 (full-text) e kNN (vector similarity).
 *
 * <p>Implementa Reciprocal Rank Fusion (RRF) per la fusione dei risultati:
 * <pre>
 *   RRF_score(d) = Σ  1 / (k + rank_i(d))
 * </pre>
 * dove {@code k=60} è la costante RRF standard e {@code rank_i} è la posizione
 * del documento nella lista ordinata del sistema i-esimo.
 *
 * <p>L'hybrid search supera sistematicamente sia BM25 sia kNN da soli:
 * <ul>
 *   <li>BM25 gestisce bene query con termini esatti e keyword matching</li>
 *   <li>kNN gestisce sinonimi, parafrasi e concetti semanticamente simili</li>
 *   <li>RRF combina i punti di forza di entrambi in modo robusto</li>
 * </ul>
 *
 * <p>BM25 e kNN vengono eseguiti in parallelo tramite {@link CompletableFuture}
 * per ridurre la latenza complessiva. Se uno dei due fallisce, l'altro viene
 * comunque usato (fallback graceful).
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final String EMBEDDING_FIELD = "content_embedding";
    private static final int RRF_K = 60;

    /** Campi text che hanno un sub-field .keyword da usare nei term filter. */
    private static final Set<String> KEYWORD_FIELDS = Set.of("fileName", "chapterTitle");

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingProvider embeddingProvider;

    @Value("${semantic.index.name:semantic_docs}")
    private String semanticIndex;

    @Value("${hybrid.search.bm25-weight:1.0}")
    private double bm25Weight;

    @Value("${hybrid.search.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${hybrid.search.position-weight:0.5}")
    private double positionWeight;

    @Value("${hybrid.search.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${hybrid.search.rerank.window:40}")
    private int rerankWindow;

    @Value("${hybrid.search.rerank.rrf-weight:0.65}")
    private double rerankRrfWeight;

    @Value("${hybrid.search.rerank.term-overlap-weight:0.25}")
    private double rerankTermOverlapWeight;

    @Value("${hybrid.search.rerank.title-match-weight:0.10}")
    private double rerankTitleMatchWeight;

    /**
     * Parole-chiave che segnalano una query sul finale/conclusione narrativa.
     * Quando presenti, attiva il position boost (chunk con indice alto risalgono).
     */
    private static final Set<String> ENDING_SIGNALS = Set.of(
            "fine", "finale", "conclud", "muore", "muor", "mort", "ultimo",
            "ultim", "infine", "sopravviv", "conclus", "ending", "dies", "death"
    );

        /** Stop words leggere per non far pesare troppo termini funzionali nel reranking. */
        private static final Set<String> RERANK_STOP_WORDS = Set.of(
            "a", "ad", "al", "alla", "alle", "allo", "ai", "agli",
            "da", "dal", "dalla", "dalle", "dello", "degli", "dei",
            "di", "e", "ed", "il", "la", "le", "lo", "gli", "i",
            "in", "nel", "nella", "nelle", "per", "su", "tra", "con",
            "un", "una", "uno", "the", "and", "for", "with", "what",
            "when", "where", "which", "who", "why", "how", "sono", "come",
            "cosa", "quale", "quali", "chi", "dove", "quando", "perche", "perché"
        );

    public HybridSearchService(ElasticsearchClient elasticsearchClient,
                               EmbeddingProvider embeddingProvider) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Ricerca ibrida con RRF: combina BM25 e kNN.
     *
     * @param queryText testo della query
     * @param size      numero di risultati da restituire
     * @return lista di risultati ordinati per score RRF (desc)
     */
    public List<SearchResult> search(String queryText, int size) {
        return search(queryText, size, null);
    }

    /**
     * Ricerca ibrida con filtri opzionali sui metadati.
     *
     * <p>BM25 e kNN vengono eseguiti in parallelo; se uno fallisce
     * vengono restituiti solo i risultati dell'altro.
     *
     * @param queryText      testo della query
     * @param size           numero di risultati
     * @param metadataFilter coppia chiave/valore per filtrare i risultati (es. fileName)
     * @return lista di risultati ordinati per score RRF (desc)
     */
    public List<SearchResult> search(String queryText, int size, Map<String, String> metadataFilter) {
        log.info("Hybrid search: query='{}', size={}, filter={}", queryText, size, metadataFilter);

        int candidateSize = Math.min(size * 3, 150);

        // Avvia BM25 e kNN in parallelo (ElasticsearchClient e HttpClient sono thread-safe)
        CompletableFuture<List<SearchResult>> bm25Future = CompletableFuture
                .supplyAsync(() -> executeBm25Search(queryText, candidateSize, metadataFilter));

        CompletableFuture<List<SearchResult>> knnFuture = CompletableFuture
                .supplyAsync(() -> executeKnnSearch(queryText, candidateSize, metadataFilter));

        List<SearchResult> bm25Results = bm25Future.join();
        List<SearchResult> knnResults  = knnFuture.join();

        log.debug("BM25: {} risultati, kNN: {} risultati", bm25Results.size(), knnResults.size());

        boolean applyPositionBoost = hasEndingSignal(queryText);
        // Quando la query riguarda il finale, inietta esplicitamente gli ultimi chunk
        // nel pool di candidati per garantirne la presenza prima della RRF.
        List<SearchResult> endingChunks = List.of();
        if (applyPositionBoost) {
            log.info("Position boost attivato (query sul finale narrativo)");
            endingChunks = fetchEndingChunks(metadataFilter, size);
            log.debug("Ending chunks iniettati: {}", endingChunks.size());
        }

        List<SearchResult> mergedCandidates = reciprocalRankFusion(
            bm25Results, knnResults, endingChunks, candidateSize, applyPositionBoost);

        List<SearchResult> finalResults = rerankEnabled
            ? rerankSecondStage(queryText, mergedCandidates, size)
            : mergedCandidates.stream().limit(size).toList();

        log.info("Hybrid search finale: {} risultati (rerank={})", finalResults.size(), rerankEnabled);
        return finalResults;
    }

    /**
     * Ricerca kNN pura (vettoriale) senza BM25.
     */
    public List<SearchResult> searchKnn(String queryText, int size) {
        return executeKnnSearch(queryText, size, null);
    }

    // ── BM25 full-text search ─────────────────────────────────────────────────

    private List<SearchResult> executeBm25Search(String queryText, int size,
                                                  Map<String, String> metadataFilter) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(semanticIndex)
                    .size(size)
                    .source(src -> src.filter(f -> f.excludes(EMBEDDING_FIELD)))
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(queryText)
                                .fields("content^3", "chapterTitle^2", "fileName")
                                .fuzziness("AUTO")
                        ));
                        if (metadataFilter != null) {
                            metadataFilter.forEach((k, v) -> {
                                String field = KEYWORD_FIELDS.contains(k) ? k + ".keyword" : k;
                                b.filter(f -> f.term(t -> t.field(field).value(v)));
                            });
                        }
                        return b;
                    }))
            );

            SearchResponse<SemanticChunk> response =
                    elasticsearchClient.search(request, SemanticChunk.class);
            return toSearchResults(response);

        } catch (Exception e) {
            log.warn("BM25 search fallita, uso solo kNN: {}", e.getMessage());
            return List.of();
        }
    }

    // ── kNN vector search ─────────────────────────────────────────────────────

    private List<SearchResult> executeKnnSearch(String queryText, int size,
                                                 Map<String, String> metadataFilter) {
        try {
            List<Float> queryVector = embeddingProvider.embed(queryText);

            SearchRequest request = SearchRequest.of(s -> s
                    .index(semanticIndex)
                    .size(size)
                    .source(src -> src.filter(f -> f.excludes(EMBEDDING_FIELD)))
                    .knn(k -> {
                        k.field(EMBEDDING_FIELD)
                                .queryVector(queryVector)
                                .numCandidates(Math.min(size * 5, 500))
                                .k(size);
                        if (metadataFilter != null) {
                            metadataFilter.forEach((key, value) -> {
                                String field = KEYWORD_FIELDS.contains(key) ? key + ".keyword" : key;
                                k.filter(f -> f.term(t -> t.field(field).value(value)));
                            });
                        }
                        return k;
                    })
            );

            SearchResponse<SemanticChunk> response =
                    elasticsearchClient.search(request, SemanticChunk.class);
            return toSearchResults(response);

        } catch (Exception e) {
            log.warn("kNN search fallita, uso solo BM25: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Reciprocal Rank Fusion ────────────────────────────────────────────────

    /**
     * Implementazione di RRF standard (k=60) con position boost opzionale.
     *
     * <p>Quando {@code applyPositionBoost=true} (query sul finale narrativo),
     * aggiunge un terzo segnale RRF basato sul {@code chunkIndex} discendente:
     * i chunk vicini alla fine del documento salgono di rank. Il peso è
     * configurabile tramite {@code hybrid.search.position-weight}.
     *
     * <p>{@code endingChunks} è una lista aggiuntiva di chunk finali recuperati
     * esplicitamente; vengono aggiunti al pool di candidati prima della fusione.
     */
    private List<SearchResult> reciprocalRankFusion(List<SearchResult> bm25List,
                                                     List<SearchResult> knnList,
                                                     List<SearchResult> endingChunks,
                                                     int topK,
                                                     boolean applyPositionBoost) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, SearchResult> byKey = new HashMap<>();

        // Score BM25
        for (int rank = 0; rank < bm25List.size(); rank++) {
            SearchResult r = bm25List.get(rank);
            String key = docKey(r);
            double score = bm25Weight / (RRF_K + rank + 1);
            rrfScores.merge(key, score, Double::sum);
            byKey.putIfAbsent(key, r);
        }

        // Score kNN
        for (int rank = 0; rank < knnList.size(); rank++) {
            SearchResult r = knnList.get(rank);
            String key = docKey(r);
            double score = vectorWeight / (RRF_K + rank + 1);
            rrfScores.merge(key, score, Double::sum);
            byKey.putIfAbsent(key, r);
        }

        // Aggiunge gli ending chunk al pool (senza score iniziale,
        // contribuiranno solo tramite il position boost sotto)
        for (SearchResult r : endingChunks) {
            byKey.putIfAbsent(docKey(r), r);
            rrfScores.putIfAbsent(docKey(r), 0.0);
        }

        // Position boost: aggiunge un terzo segnale RRF ordinato per chunkIndex desc
        if (applyPositionBoost && !byKey.isEmpty()) {
            List<SearchResult> byPosition = byKey.values().stream()
                    .sorted(Comparator.comparingInt(
                            r -> -(r.getChunkIndex() != null ? r.getChunkIndex() : 0)))
                    .toList();
            for (int rank = 0; rank < byPosition.size(); rank++) {
                String key = docKey(byPosition.get(rank));
                double score = positionWeight / (RRF_K + rank + 1);
                rrfScores.merge(key, score, Double::sum);
            }
        }

        // Ordina per RRF score decrescente e prendi i top-K candidati
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(e -> {
                    SearchResult r = byKey.get(e.getKey());
                    r.setScore(e.getValue().floatValue());
                    return r;
                })
                .toList();
    }

    /**
     * Secondo stadio di reranking sopra i candidati prodotti da RRF.
     *
     * <p>Usa tre segnali leggeri e stabili:
     * <ul>
     *   <li>score RRF normalizzato</li>
     *   <li>overlap tra termini significativi della query e del contenuto</li>
     *   <li>match dei termini nel titolo del capitolo o nel nome file</li>
     * </ul>
     *
     * <p>L'obiettivo non è sostituire un cross-encoder, ma ripulire il top-K finale
     * premiando i chunk che allineano meglio segnali semantici e lessicali.
     */
    private List<SearchResult> rerankSecondStage(String queryText, List<SearchResult> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        int effectiveWindow = Math.max(topK, Math.min(rerankWindow, candidates.size()));
        List<SearchResult> window = candidates.stream().limit(effectiveWindow).toList();
        Set<String> queryTerms = tokenizeForRerank(queryText);
        float maxOriginalScore = window.stream()
                .map(SearchResult::getScore)
                .filter(score -> score != null)
                .max(Float::compare)
                .orElse(1.0f);

        List<SearchResult> reranked = new ArrayList<>(window.size());
        for (SearchResult candidate : window) {
            double normalizedRrf = maxOriginalScore > 0f && candidate.getScore() != null
                    ? candidate.getScore() / maxOriginalScore
                    : 0.0;
            double contentOverlap = computeOverlap(queryTerms, candidate.getContent());
            double titleMatch = Math.max(
                    computeOverlap(queryTerms, candidate.getChapterTitle()),
                    computeOverlap(queryTerms, candidate.getFileName())
            );
            boolean exactPhrase = containsNormalized(candidate.getContent(), queryText)
                    || containsNormalized(candidate.getChapterTitle(), queryText);
            double exactPhraseBoost = exactPhrase ? 0.05 : 0.0;

            double finalScore = (normalizedRrf * rerankRrfWeight)
                    + (contentOverlap * rerankTermOverlapWeight)
                    + (titleMatch * rerankTitleMatchWeight)
                    + exactPhraseBoost;

            candidate.setExplanation(String.format(
                    "rerank(rrf=%.3f, overlap=%.3f, title=%.3f, exact=%s)",
                    normalizedRrf, contentOverlap, titleMatch, exactPhrase));
            candidate.setScore((float) finalScore);
            reranked.add(candidate);
        }

        reranked.sort(Comparator
                .comparing(SearchResult::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(result -> result.getChunkIndex() != null ? result.getChunkIndex() : Integer.MAX_VALUE));

        if (log.isDebugEnabled()) {
            log.debug("Second-stage rerank applicato: window={}, queryTerms={}", effectiveWindow, queryTerms);
            for (int i = 0; i < Math.min(topK, reranked.size()); i++) {
                SearchResult result = reranked.get(i);
                log.debug("   [rerank {}] score={} doc='{}' sez='{}' explain={}",
                        i + 1,
                        result.getScore() != null ? String.format("%.4f", result.getScore()) : "n/a",
                        result.getFileName(),
                        result.getChapterTitle(),
                        result.getExplanation());
            }
        }

        return reranked.stream().limit(topK).toList();
    }

    private Set<String> tokenizeForRerank(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase().split("\\s+"))
                .map(token -> token.replaceAll("[^\\p{L}\\p{Nd}]", ""))
                .filter(token -> token.length() >= 3)
                .filter(token -> !RERANK_STOP_WORDS.contains(token))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private double computeOverlap(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        Set<String> candidateTerms = tokenizeForRerank(text);
        if (candidateTerms.isEmpty()) {
            return 0.0;
        }
        long matches = queryTerms.stream().filter(candidateTerms::contains).count();
        return (double) matches / queryTerms.size();
    }

    private boolean containsNormalized(String text, String query) {
        if (text == null || text.isBlank() || query == null || query.isBlank()) {
            return false;
        }
        String normalizedText = text.toLowerCase().replaceAll("\\s+", " ");
        String normalizedQuery = query.toLowerCase().trim().replaceAll("\\s+", " ");
        return normalizedQuery.length() >= 6 && normalizedText.contains(normalizedQuery);
    }

    /**
     * Recupera esplicitamente gli ultimi {@code n*2} chunk del documento
     * (o dei documenti filtrati) ordinati per chunkIndex discendente.
     * Usato per garantire che il finale narrativo sia sempre nel pool RRF.
     */
    private List<SearchResult> fetchEndingChunks(Map<String, String> metadataFilter, int n) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(semanticIndex)
                    .size(n * 2)
                    .source(src -> src.filter(f -> f.excludes(EMBEDDING_FIELD)))
                    .query(q -> {
                        if (metadataFilter != null && !metadataFilter.isEmpty()) {
                            return q.bool(b -> {
                                metadataFilter.forEach((k, v) -> {
                                    String field = KEYWORD_FIELDS.contains(k) ? k + ".keyword" : k;
                                    b.filter(f -> f.term(t -> t.field(field).value(v)));
                                });
                                return b;
                            });
                        }
                        return q.matchAll(m -> m);
                    })
                    .sort(sort -> sort.field(f -> f
                            .field("chunkIndex")
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
            );
            SearchResponse<SemanticChunk> response =
                    elasticsearchClient.search(request, SemanticChunk.class);
            return toSearchResults(response);
        } catch (Exception e) {
            log.warn("fetchEndingChunks fallito: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Ritorna true se la query contiene segnali che indicano una domanda
     * sul finale/conclusione narrativa del documento.
     */
    private boolean hasEndingSignal(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase();
        return ENDING_SIGNALS.stream().anyMatch(lower::contains);
    }

    private static String docKey(SearchResult r) {
        return r.getDocumentId() + ":" + r.getChunkIndex();
    }

    // ── Documenti indicizzati ─────────────────────────────────────────────────

    /**
     * Restituisce tutti i fileName distinti presenti nell'indice.
     */
    public List<String> listDocuments() {
        try {
            var response = elasticsearchClient.search(s -> s
                    .index(semanticIndex)
                    .size(0)
                    .aggregations("files", a -> a
                            .terms(t -> t.field("fileName.keyword").size(500))),
                    SemanticChunk.class);

            return response.aggregations().get("files")
                    .sterms().buckets().array().stream()
                    .map(StringTermsBucket::key)
                    .map(k -> k.stringValue())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Impossibile listare i documenti: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Risolve un nome file parziale/approssimativo al fileName esatto presente nell'indice.
     *
     * <p>Usa una {@code match} query con fuzziness su {@code fileName} per trovare
     * il documento più simile, senza richiedere il nome esatto.
     *
     * @param hint stringa parziale fornita dall'utente (es. "zanna bianca", "ventimila")
     * @return fileName esatto se trovato, altrimenti {@code Optional.empty()}
     */
    public Optional<String> resolveFileName(String hint) {
        if (hint == null || hint.isBlank()) {
            return Optional.empty();
        }

        String trimmedHint = hint.trim();
        Optional<String> indexedMatch = listDocuments().stream()
            .max(Comparator.comparingInt(candidate -> scoreFileNameCandidate(trimmedHint, candidate)));

        if (indexedMatch.isPresent() && scoreFileNameCandidate(trimmedHint, indexedMatch.get()) > 0) {
            log.debug("Risolto fileName via indice locale: hint='{}' -> '{}'", trimmedHint, indexedMatch.get());
            return indexedMatch;
        }

        try {
            var response = elasticsearchClient.search(s -> s
                    .index(semanticIndex)
                    .size(1)
                    .source(src -> src.filter(f -> f.includes("fileName")))
                    .query(q -> q.match(m -> m
                            .field("fileName")
                    .query(trimmedHint)
                            .fuzziness("AUTO"))),
                    SemanticChunk.class);

            return response.hits().hits().stream()
                    .findFirst()
                    .map(Hit::source)
                    .map(SemanticChunk::getFileName);
        } catch (Exception e) {
            log.warn("Risoluzione fileName fallita per hint='{}': {}", hint, e.getMessage());
            return Optional.empty();
        }
    }

    private int scoreFileNameCandidate(String hint, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return 0;
        }
        if (candidate.equalsIgnoreCase(hint)) {
            return 1_000;
        }

        String normalizedHint = normalizeFileNameHint(hint);
        String normalizedCandidate = normalizeFileNameHint(candidate);
        String normalizedHintBase = removePdfSuffix(normalizedHint);
        String normalizedCandidateBase = removePdfSuffix(normalizedCandidate);

        if (normalizedHint.isBlank() || normalizedCandidate.isBlank()) {
            return 0;
        }
        if (normalizedCandidate.equals(normalizedHint)) {
            return 950;
        }
        if (normalizedCandidateBase.equals(normalizedHintBase)) {
            return 925;
        }
        if (normalizedCandidate.contains(normalizedHint) || normalizedCandidateBase.contains(normalizedHintBase)) {
            return 850;
        }
        if (normalizedHint.contains(normalizedCandidate) || normalizedHintBase.contains(normalizedCandidateBase)) {
            return 700;
        }

        Set<String> hintTokens = Arrays.stream(normalizedHintBase.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
        Set<String> candidateTokens = Arrays.stream(normalizedCandidateBase.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
        if (hintTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0;
        }

        long overlap = hintTokens.stream().filter(candidateTokens::contains).count();
        if (overlap == 0) {
            return 0;
        }
        return 500 + (int) (overlap * 100);
    }

    private String normalizeFileNameHint(String value) {
        return value.toLowerCase()
                .replaceAll("\\.[a-z0-9]{2,5}$", "")
                .replaceAll("[^\\p{L}\\p{Nd}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String removePdfSuffix(String value) {
        return value.replaceAll("\\bpdf$", "").trim();
    }

    // ── Conversione risposta ES → SearchResult ────────────────────────────────

    private List<SearchResult> toSearchResults(SearchResponse<SemanticChunk> response) {
        List<SearchResult> results = new ArrayList<>();
        for (Hit<SemanticChunk> hit : response.hits().hits()) {
            if (hit.source() == null) continue;
            SemanticChunk chunk = hit.source();
            SearchResult r = new SearchResult();
            r.setDocumentId(chunk.getDocumentId());
            r.setFileName(chunk.getFileName());
            r.setContent(chunk.getContent());
            r.setChunkIndex(chunk.getChunkIndex());
            r.setChapterTitle(chunk.getChapterTitle());
            r.setChapterIndex(chunk.getChapterIndex());
            r.setScore(hit.score() != null ? hit.score().floatValue() : 0f);
            results.add(r);
        }
        return results;
    }
}
