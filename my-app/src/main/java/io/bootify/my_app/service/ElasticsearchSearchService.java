package io.bootify.my_app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.SearchResult;
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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String ES_URL = "http://localhost:9200";

    public ElasticsearchSearchService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public List<SearchResult> search(String queryText, String language, int size) {
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
        String indexName = resolveIndexName(language);
        
        Map<String, Object> searchQuery = new HashMap<>();
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> bool = new HashMap<>();
        List<Map<String, Object>> should = new ArrayList<>();
        
        // Phrase query with high boost
        Map<String, Object> phraseQuery = new HashMap<>();
        Map<String, Object> match1 = new HashMap<>();
        match1.put("query", queryText);
        match1.put("boost", 3.0);
        phraseQuery.put("content", match1);
        should.add(Map.of("match", phraseQuery));
        
        // Fuzzy query
        Map<String, Object> fuzzyQuery = new HashMap<>();
        Map<String, Object> match2 = new HashMap<>();
        match2.put("query", queryText);
        match2.put("fuzziness", "AUTO");
        match2.put("boost", 1.0);
        fuzzyQuery.put("content", match2);
        should.add(Map.of("match", fuzzyQuery));
        
        // Filename query with highest boost
        Map<String, Object> fileQuery = new HashMap<>();
        Map<String, Object> match3 = new HashMap<>();
        match3.put("query", queryText);
        match3.put("boost", 5.0);
        fileQuery.put("fileName", match3);
        should.add(Map.of("match", fileQuery));
        
        bool.put("should", should);
        query.put("bool", bool);
        searchQuery.put("query", query);
        searchQuery.put("size", size);
        
        // Add highlight
        Map<String, Object> highlight = new HashMap<>();
        Map<String, Object> fields = new HashMap<>();
        Map<String, Object> contentField = new HashMap<>();
        contentField.put("fragment_size", 150);
        contentField.put("number_of_fragments", 1);
        fields.put("content", contentField);
        highlight.put("fields", fields);
        searchQuery.put("highlight", highlight);
        
        return executeSearch(indexName, searchQuery);
    }

    private List<SearchResult> executeSearch(String indexName, Map<String, Object> searchQuery) {
        try {
            String json = objectMapper.writeValueAsString(searchQuery);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            
            HttpEntity<String> request = new HttpEntity<>(json, headers);
            
            String url = ES_URL + "/" + indexName + "/_search";
            System.out.println("DEBUG Search: URL=" + url);
            System.out.println("DEBUG Search: Query=" + json);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            
            return parseSearchResults(response.getBody());
            
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            e.printStackTrace();
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
