package io.bootify.my_app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera embedding densi tramite Ollama (100% free, nessuna licenza richiesta).
 * Usa il modello nomic-embed-text (768 dims, Apache-2.0).
 *
 * API Ollama: POST /api/embeddings
 *   request:  { "model": "nomic-embed-text", "prompt": "testo" }
 *   response: { "embedding": [0.1, 0.2, ...] }
 */
@Service
public class OllamaEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingService.class);

    private final String ollamaUrl;
    private final String embedModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingService(
            @Value("${ollama.url}") String ollamaUrl,
            @Value("${ollama.embed.model}") String embedModel) {
        this.ollamaUrl = ollamaUrl;
        this.embedModel = embedModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Genera un embedding denso per il testo fornito.
     *
     * @param text testo da embeddare
     * @return lista di float che rappresenta il vettore di embedding
     */
    public List<Float> embed(String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", embedModel);
            body.put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama embed error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new RuntimeException("Risposta Ollama non contiene il campo 'embedding': " + response.body());
            }

            List<Float> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode val : embeddingNode) {
                vector.add((float) val.asDouble());
            }
            return vector;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Errore chiamata Ollama embeddings: " + e.getMessage(), e);
        }
    }
}
