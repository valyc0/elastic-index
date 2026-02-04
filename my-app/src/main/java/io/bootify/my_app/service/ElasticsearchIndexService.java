package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.config.IndexNameResolver;
import io.bootify.my_app.model.DocumentChunk;
import io.bootify.my_app.util.ChunkingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class ElasticsearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexService.class);
    
    private final LanguageDetectionService languageDetectionService;
    private final IndexNameResolver indexNameResolver;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ElasticsearchClient elasticsearchClient;
    private static final String ES_URL = "http://localhost:9200";

    public ElasticsearchIndexService(LanguageDetectionService languageDetectionService,
                                     IndexNameResolver indexNameResolver,
                                     ObjectMapper objectMapper,
                                     ElasticsearchClient elasticsearchClient) {
        this.languageDetectionService = languageDetectionService;
        this.indexNameResolver = indexNameResolver;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.elasticsearchClient = elasticsearchClient;
    }

    public void indexDocument(String documentId, String fileName, String extractedText, java.util.Map<String, String> metadata) {
        List<String> chunks = ChunkingUtils.chunk(extractedText);
        
        // Estrae la lingua dai metadati Tika se disponibile, altrimenti usa il rilevamento automatico
        String documentLanguage = extractLanguageFromMetadata(metadata);

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            
            // Usa la lingua dai metadati se disponibile, altrimenti rileva per chunk
            String language = documentLanguage != null ? documentLanguage : languageDetectionService.detect(chunkText);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setLanguage(language);
            chunk.setFileName(fileName);
            chunk.setMetadata(metadata);

            String indexName = indexNameResolver.resolve(chunk);
            
            try {
                indexChunkViaClient(indexName, chunk);
            } catch (Exception e) {
                throw new RuntimeException("Failed to index chunk", e);
            }
        }
    }
    
    /**
     * Estrae la lingua dai metadati Tika.
     * Cerca vari campi comuni dove Tika può mettere l'informazione sulla lingua.
     */
    private String extractLanguageFromMetadata(java.util.Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        
        // Controlla vari possibili campi di metadati per la lingua
        String[] languageFields = {"language", "Language", "dc:language", "Content-Language"};
        
        for (String field : languageFields) {
            String lang = metadata.get(field);
            if (lang != null && !lang.trim().isEmpty()) {
                log.info("Language found in metadata ({}): {}", field, lang);
                return lang;
            }
        }
        
        log.info("No language found in metadata, will use automatic detection");
        return null;
    }

    private void indexChunkViaRest(String indexName, DocumentChunk chunk) throws Exception {
        String json = objectMapper.writeValueAsString(chunk);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        
        HttpEntity<String> request = new HttpEntity<>(json, headers);
        
        String url = ES_URL + "/" + indexName + "/_doc/" + chunk.getId();
        log.info("Posting to: {}", url);
        log.debug("JSON: {}", json.substring(0, Math.min(200, json.length())));
        
        String response = restTemplate.postForObject(url, request, String.class);
        log.info("Response: {}", response);
    }

    /**
     * Metodo alternativo che usa il client ufficiale di Elasticsearch invece di REST diretto.
     * Questo approccio è type-safe e più robusto rispetto alle chiamate REST dirette.
     */
    private void indexChunkViaClient(String indexName, DocumentChunk chunk) throws Exception {
        log.info("Indexing via Elasticsearch Client to index: {} - Chunk ID: {}", indexName, chunk.getId());
        
        IndexRequest<DocumentChunk> request = IndexRequest.of(builder -> builder
            .index(indexName)
            .id(chunk.getId())
            .document(chunk)
        );
        
        IndexResponse response = elasticsearchClient.index(request);
        
        log.info("Document indexed successfully - Result: {} - Version: {}", 
                 response.result().name(), response.version());
    }
}
