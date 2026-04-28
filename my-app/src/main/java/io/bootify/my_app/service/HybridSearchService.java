package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.model.SemanticChunk;
import io.bootify.my_app.service.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final String EMBEDDING_FIELD = "content_embedding";
    private static final int RRF_K = 60;

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingProvider embeddingProvider;

    @Value("${semantic.index.name:semantic_docs}")
    private String semanticIndex;

    @Value("${hybrid.search.bm25-weight:1.0}")
    private double bm25Weight;

    @Value("${hybrid.search.vector-weight:1.0}")
    private double vectorWeight;

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
     * @param queryText      testo della query
     * @param size           numero di risultati
     * @param metadataFilter coppia chiave/valore per filtrare i risultati (es. fileName)
     * @return lista di risultati ordinati per score RRF (desc)
     */
    public List<SearchResult> search(String queryText, int size, Map<String, String> metadataFilter) {
        log.info("Hybrid search: query='{}', size={}, filter={}", queryText, size, metadataFilter);

        // Esegui BM25 e kNN in parallelo (candidati ampi per il merge)
        int candidateSize = Math.min(size * 3, 150);

        List<SearchResult> bm25Results = executeBm25Search(queryText, candidateSize, metadataFilter);
        List<SearchResult> knnResults = executeKnnSearch(queryText, candidateSize, metadataFilter);

        log.debug("BM25: {} risultati, kNN: {} risultati", bm25Results.size(), knnResults.size());

        // Applica RRF per fondere i risultati
        List<SearchResult> merged = reciprocalRankFusion(bm25Results, knnResults, size);
        log.info("Hybrid RRF: {} risultati finali", merged.size());
        return merged;
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
                            metadataFilter.forEach((k, v) ->
                                    b.filter(f -> f.term(t -> t.field(k).value(v))));
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
                            metadataFilter.forEach((key, value) ->
                                    k.filter(f -> f.term(t -> t.field(key).value(value))));
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
     * Implementazione di RRF standard (k=60).
     * Identifica i documenti tramite documentId+chunkIndex come chiave composita.
     */
    private List<SearchResult> reciprocalRankFusion(List<SearchResult> bm25List,
                                                     List<SearchResult> knnList,
                                                     int topK) {
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

        // Ordina per RRF score decrescente e prendi i top-K
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

    private static String docKey(SearchResult r) {
        return r.getDocumentId() + ":" + r.getChunkIndex();
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
