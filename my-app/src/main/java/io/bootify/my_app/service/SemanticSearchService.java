package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.model.SemanticChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Esegue ricerche semantiche kNN sull'indice semantic_docs usando dense vector.
 * La query viene embeddara tramite Ollama (nomic-embed-text) e confrontata con
 * i vettori salvati tramite cosine similarity – 100% free, nessuna licenza.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private static final String EMBEDDING_FIELD = "content_embedding";

    private final ElasticsearchClient elasticsearchClient;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public SemanticSearchService(ElasticsearchClient elasticsearchClient,
                                 OllamaEmbeddingService ollamaEmbeddingService) {
        this.elasticsearchClient = elasticsearchClient;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
    }

    /**
     * Ricerca semantica kNN: la query viene embeddara da Ollama e confrontata
     * con i vettori densi nell'indice tramite cosine similarity.
     *
     * @param queryText testo della query in linguaggio naturale
     * @param size      numero massimo di risultati
     * @return lista di risultati ordinati per score di similitudine (desc)
     */
    public List<SearchResult> search(String queryText, int size) {
        log.info("Semantic kNN search: query='{}', size={}", queryText, size);

        try {
            List<Float> queryVector = ollamaEmbeddingService.embed(queryText);

            SearchRequest request = SearchRequest.of(s -> s
                    .index(SemanticIndexService.SEMANTIC_INDEX)
                    .size(size)
                    .source(src -> src
                            .filter(f -> f
                                    .excludes(EMBEDDING_FIELD)
                            )
                    )
                    .knn(k -> k
                            .field(EMBEDDING_FIELD)
                            .queryVector(queryVector)
                            .numCandidates(100)
                            .k(size)
                    )
            );

            SearchResponse<SemanticChunk> response =
                    elasticsearchClient.search(request, SemanticChunk.class);

            List<SearchResult> results = new ArrayList<>();
            for (Hit<SemanticChunk> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
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

            log.info("Semantic kNN search completed: {} results for query='{}'", results.size(), queryText);
            return results;

        } catch (Exception e) {
            log.error("Error during semantic kNN search for query='{}'", queryText, e);
            throw new RuntimeException("Semantic search failed: " + e.getMessage(), e);
        }
    }
}
