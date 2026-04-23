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
 * Esegue ricerche semantiche per similitudine sull'indice semantic_docs
 * usando la query text_expansion di Elasticsearch con il modello ELSER.
 * ELSER genera internamente i token sparsi sia al momento dell'indicizzazione
 * (via ingest pipeline) sia al momento della query.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private static final String ELSER_MODEL_ID = ".elser_model_2";
    private static final String EMBEDDING_FIELD = "content_embedding";

    private final ElasticsearchClient elasticsearchClient;

    public SemanticSearchService(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    /**
     * Ricerca semantica: la query testuale viene espansa da ELSER in token sparsi
     * e confrontata con gli embedding memorizzati nel campo content_embedding.
     *
     * @param queryText testo della query in linguaggio naturale
     * @param size      numero massimo di risultati
     * @return lista di risultati ordinati per score di similitudine (desc)
     */
    public List<SearchResult> search(String queryText, int size) {
        log.info("Semantic search: query='{}', size={}", queryText, size);

        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(SemanticIndexService.SEMANTIC_INDEX)
                    .size(size)
                    .source(src -> src
                            .filter(f -> f
                                    .excludes(EMBEDDING_FIELD)
                            )
                    )
                    .query(q -> q
                            .textExpansion(te -> te
                                    .field(EMBEDDING_FIELD)
                                    .modelId(ELSER_MODEL_ID)
                                    .modelText(queryText)
                            )
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

            log.info("Semantic search completed: {} results for query='{}'", results.size(), queryText);
            return results;

        } catch (Exception e) {
            log.error("Error during semantic search for query='{}'", queryText, e);
            throw new RuntimeException("Semantic search failed: " + e.getMessage(), e);
        }
    }
}
