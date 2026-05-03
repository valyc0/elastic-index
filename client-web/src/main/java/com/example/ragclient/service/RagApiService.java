package com.example.ragclient.service;

import com.example.ragclient.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagApiService {

    private final WebClient webClient;

    // ============ DOCUMENT ENDPOINTS ============

    /**
     * Upload a document to the RAG system
     */
    public UploadResponse uploadDocument(String filename, byte[] content) {
        log.info("📤 Uploading document: {}", filename);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        return webClient.post()
            .uri("/api/docling/index")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(UploadResponse.class)
                .block();
    }

    /**
     * Get list of all indexed documents
     */
    public DocumentListResponse getDocumentList() {
        log.info("📋 Fetching document list");

        List<String> documents = webClient.get()
            .uri("/api/rag/documents")
                .retrieve()
            .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                .block();

        return new DocumentListResponse(documents);
    }

    /**
     * Get status of a specific document
     */
    public Map<String, Object> deleteDocument(String filename) {
        log.info("🗑️ Deleting document: {}", filename);

        return webClient.delete()
            .uri(uriBuilder -> uriBuilder
                .path("/api/semantic/document")
                .queryParam("fileName", filename)
                .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /**
     * Health check for document API
     */
    public HealthResponse getDocumentHealth() {
        return webClient.get()
                .uri("/api/docling/health")
                .retrieve()
                .bodyToMono(HealthResponse.class)
                .block();
    }

    // ============ QUERY ENDPOINTS ============

    /**
     * Query the RAG system (GET method)
     */
    public QueryResponse query(String question, String sessionId) {
        log.info("❓ Querying: {}", question);

        Map<String, Object> request = new HashMap<>();
        request.put("query", question);
        request.put("topK", 5);
        if (sessionId != null && !sessionId.isBlank()) {
            request.put("sessionId", sessionId);
        }

        return webClient.post()
                .uri("/api/rag/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(QueryResponse.class)
                .block();
    }

    public String createSession() {
        Map<String, String> response = webClient.post()
                .uri("/api/rag/session")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                .block();

        return response != null ? response.get("sessionId") : null;
    }

    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        webClient.delete()
                .uri("/api/rag/session/{sessionId}", sessionId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /**
     * Health check for query API
     */
    public HealthResponse getQueryHealth() {
        return webClient.get()
                .uri("/api/rag/health")
                .retrieve()
                .bodyToMono(HealthResponse.class)
                .block();
    }
}
