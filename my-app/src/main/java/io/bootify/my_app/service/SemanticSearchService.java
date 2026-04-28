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
import java.util.List;

/**
 * Esegue ricerche semantiche kNN sull'indice semantico usando dense vector.
 *
 * <p>Delega la generazione del query embedding a {@link EmbeddingProvider},
 * rendendo il servizio indipendente dal modello di embedding specifico.
 *
 * <p>Per la ricerca ibrida (BM25 + kNN con RRF), usare {@link HybridSearchService}.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final String EMBEDDING_FIELD = "content_embedding";

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingProvider embeddingProvider;

    @Value("${semantic.index.name:semantic_docs}")
    private String semanticIndex;

    public SemanticSearchService(ElasticsearchClient elasticsearchClient,
                                 EmbeddingProvider embeddingProvider) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Ricerca semantica kNN pura.
     *
     * @param queryText testo della query in linguaggio naturale
     * @param size      numero massimo di risultati
     * @return lista di risultati ordinati per cosine similarity (desc)
     */
    public List<SearchResult> search(String queryText, int size) {
        log.info("Semantic kNN search: query='{}', size={}, model={}",
                queryText, size, embeddingProvider.modelName());

        try {
            List<Float> queryVector = embeddingProvider.embed(queryText);

            SearchRequest request = SearchRequest.of(s -> s
                    .index(semanticIndex)
                    .size(size)
                    .source(src -> src.filter(f -> f.excludes(EMBEDDING_FIELD)))
                    .knn(k -> k
                            .field(EMBEDDING_FIELD)
                            .queryVector(queryVector)
                            .numCandidates(Math.min(size * 5, 500))
                            .k(size)
                    )
            );

            SearchResponse<SemanticChunk> response =
                    elasticsearchClient.search(request, SemanticChunk.class);

            List<SearchResult> results = new ArrayList<>();
            for (Hit<SemanticChunk> hit : response.hits().hits()) {
                if (hit.source() == null) continue;
                SemanticChunk chunk = hit.source();
                SearchResult result = new SearchResult();
                result.setDocumentId(chunk.getDocumentId());
                result.setFileName(chunk.getFileName());
                result.setContent(chunk.getContent());
                result.setChunkIndex(chunk.getChunkIndex());
                result.setChapterTitle(chunk.getChapterTitle());
                result.setChapterIndex(chunk.getChapterIndex());
                result.setScore(hit.score() != null ? hit.score().floatValue() : null);
                results.add(result);
            }

            log.info("kNN search completata: {} risultati per query='{}'", results.size(), queryText);
            return results;

        } catch (Exception e) {
            log.error("Errore nella ricerca kNN per query='{}'", queryText, e);
            throw new RuntimeException("Ricerca semantica fallita: " + e.getMessage(), e);
        }
    }
}
