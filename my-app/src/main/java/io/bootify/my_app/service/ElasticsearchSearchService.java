package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.DocumentChunk;
import io.bootify.my_app.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchSearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearchService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ElasticsearchClient elasticsearchClient;
    private static final String ES_URL = "http://localhost:9200";

    public ElasticsearchSearchService(ObjectMapper objectMapper, ElasticsearchClient elasticsearchClient) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.elasticsearchClient = elasticsearchClient;
    }

    public List<SearchResult> search(String queryText, String language, int size) {
        log.info("Simple search: query='{}', language='{}', size={}", queryText, language, size);
        String indexName = resolveIndexName(language);
        
        Map<String, Object> searchQuery = new HashMap<>();
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> multiMatch = new HashMap<>();
        multiMatch.put("query", queryText);
        multiMatch.put("fields", new String[]{"content", "fileName"});
        multiMatch.put("fuzziness", "AUTO");
        query.put("multi_match", multiMatch);
        searchQuery.put("query", query);
        searchQuery.put("size", size);
        
        return executeSearch(indexName, searchQuery);
    }

    public List<SearchResult> searchAdvanced(String queryText, String language, int size) {
        log.info("Advanced search: query='{}', language='{}', size={}", queryText, language, size);
        String indexName = resolveIndexName(language);
        
        return searchViaClient(indexName, queryText, size);
    }
    
    /**
     * Ricerca usando il client ufficiale Elasticsearch invece di REST diretto.
     * Metodo type-safe con API fluent.
     */
    private List<SearchResult> searchViaClient(String indexName, String queryText, int size) {
        try {
            log.debug("Searching via Elasticsearch Client in index: {}", indexName);
            
            // Costruisce la query con l'API fluent
            SearchRequest searchRequest = SearchRequest.of(s -> s
                .index(indexName)
                .size(size)
                .query(q -> q
                    .bool(b -> b
                        .should(Query.of(sq -> sq
                            .match(m -> m
                                .field("content")
                                .query(queryText)
                                .boost(3.0f)
                            )
                        ))
                        .should(Query.of(sq -> sq
                            .match(m -> m
                                .field("content")
                                .query(queryText)
                                .fuzziness("AUTO")
                                .boost(1.0f)
                            )
                        ))
                        .should(Query.of(sq -> sq
                            .match(m -> m
                                .field("fileName")
                                .query(queryText)
                                .boost(5.0f)
                            )
                        ))
                    )
                )
                .highlight(h -> h
                    .fields("content", f -> f
                        .fragmentSize(150)
                        .numberOfFragments(1)
                    )
                )
            );
            
            SearchResponse<DocumentChunk> response = elasticsearchClient.search(searchRequest, DocumentChunk.class);
            
            log.info("Search completed: {} hits found", response.hits().total().value());
            
            return parseClientSearchResults(response);
            
        } catch (Exception e) {
            log.error("Error during client search: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    private List<SearchResult> parseClientSearchResults(SearchResponse<DocumentChunk> response) {
        List<SearchResult> results = new ArrayList<>();
        
        for (Hit<DocumentChunk> hit : response.hits().hits()) {
            DocumentChunk chunk = hit.source();
            if (chunk == null) continue;
            
            SearchResult result = new SearchResult();
            result.setDocumentId(chunk.getDocumentId());
            result.setFileName(chunk.getFileName());
            result.setChunkIndex(chunk.getChunkIndex());
            result.setContent(chunk.getContent());
            result.setLanguage(chunk.getLanguage());
            result.setScore(hit.score() != null ? hit.score().floatValue() : 0.0f);
            
            // Extract highlights
            List<String> highlights = new ArrayList<>();
            if (hit.highlight() != null && hit.highlight().get("content") != null) {
                highlights.addAll(hit.highlight().get("content"));
            }
            result.setHighlights(highlights);
            
            results.add(result);
        }
        
        return results;
    }

    private List<SearchResult> executeSearch(String indexName, Map<String, Object> searchQuery) {
        try {
            String json = objectMapper.writeValueAsString(searchQuery);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            
            HttpEntity<String> request = new HttpEntity<>(json, headers);
            
            String url = ES_URL + "/" + indexName + "/_search";
            log.debug("Executing REST search to: {}", url);
            log.trace("Query: {}", json);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            
            return parseSearchResults(response.getBody());
            
        } catch (Exception e) {
            log.error("Search error: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private List<SearchResult> parseSearchResults(String jsonResponse) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode hits = root.path("hits").path("hits");
        
        List<SearchResult> results = new ArrayList<>();
        
        for (JsonNode hit : hits) {
            SearchResult result = new SearchResult();
            JsonNode source = hit.path("_source");
            
            result.setDocumentId(source.path("documentId").asText());
            result.setFileName(source.path("fileName").asText());
            result.setChunkIndex(source.path("chunkIndex").asInt());
            result.setContent(source.path("content").asText());
            result.setLanguage(source.path("language").asText());
            result.setScore(hit.path("_score").floatValue());
            
            // Extract highlights
            List<String> highlights = new ArrayList<>();
            JsonNode highlightNode = hit.path("highlight").path("content");
            if (highlightNode.isArray()) {
                for (JsonNode h : highlightNode) {
                    highlights.add(h.asText());
                }
            }
            result.setHighlights(highlights);
            
            results.add(result);
        }
        
        return results;
    }

    private String resolveIndexName(String language) {
        if (language == null || language.isEmpty()) {
            return "files_*";
        }
        
        return switch (language) {
            case "it" -> "files_it";
            case "en" -> "files_en";
            case "fr" -> "files_fr";
            case "de" -> "files_de";
            case "es" -> "files_es";
            default -> "files_*";
        };
    }
}
