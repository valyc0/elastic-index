package io.bootify.my_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.config.IndexNameResolver;
import io.bootify.my_app.model.DocumentChunk;
import io.bootify.my_app.util.ChunkingUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class ElasticsearchIndexService {

    private final LanguageDetectionService languageDetectionService;
    private final IndexNameResolver indexNameResolver;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String ES_URL = "http://localhost:9200";

    public ElasticsearchIndexService(LanguageDetectionService languageDetectionService,
                                     IndexNameResolver indexNameResolver,
                                     ObjectMapper objectMapper) {
        this.languageDetectionService = languageDetectionService;
        this.indexNameResolver = indexNameResolver;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public void indexDocument(String documentId, String fileName, String extractedText) {
        List<String> chunks = ChunkingUtils.chunk(extractedText);

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            String language = languageDetectionService.detect(chunkText);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setLanguage(language);
            chunk.setFileName(fileName);

            String indexName = indexNameResolver.resolve(chunk);
            
            try {
                indexChunkViaRest(indexName, chunk);
            } catch (Exception e) {
                throw new RuntimeException("Failed to index chunk", e);
            }
        }
    }

    private void indexChunkViaRest(String indexName, DocumentChunk chunk) throws Exception {
        String json = objectMapper.writeValueAsString(chunk);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        
        HttpEntity<String> request = new HttpEntity<>(json, headers);
        
        String url = ES_URL + "/" + indexName + "/_doc/" + chunk.getId();
        System.out.println("DEBUG: Posting to: " + url);
        System.out.println("DEBUG: JSON: " + json.substring(0, Math.min(200, json.length())));
        
        String response = restTemplate.postForObject(url, request, String.class);
        System.out.println("DEBUG: Response: " + response);
    }
}
