package io.bootify.my_app.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementazione di {@link EmbeddingProvider} basata su OpenAI Embeddings API.
 *
 * <p>Attivata quando {@code embedding.provider=openai}.
 *
 * <p>Modelli supportati:
 * <ul>
 *   <li>{@code text-embedding-3-small}: 1536 dims (consigliato, costo contenuto)</li>
 *   <li>{@code text-embedding-3-large}: 3072 dims (massima qualità)</li>
 *   <li>{@code text-embedding-ada-002}: 1536 dims (legacy)</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "embedding.provider", havingValue = "openai")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);
    private static final String OPENAI_EMBED_URL = "https://api.openai.com/v1/embeddings";

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingProvider(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.embed.model:text-embedding-3-small}") String model,
            @Value("${openai.embed.dimensions:1536}") int dimensions) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Il testo da embeddare non può essere vuoto");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("model", model, "input", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_EMBED_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "OpenAI embed error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray()) {
                throw new EmbeddingException("Risposta OpenAI non contiene embedding: " + response.body());
            }

            List<Float> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode v : embeddingNode) {
                vector.add(v.floatValue());
            }
            log.debug("OpenAI embedding generato: model={}, dims={}", model, vector.size());
            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Errore nella chiamata a OpenAI: " + e.getMessage(), e);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("model", model, "input", texts));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_EMBED_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "OpenAI batch embed error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataNode = root.path("data");
            List<List<Float>> results = new ArrayList<>();
            for (JsonNode item : dataNode) {
                List<Float> vector = new ArrayList<>();
                for (JsonNode v : item.path("embedding")) {
                    vector.add(v.floatValue());
                }
                results.add(vector);
            }
            return results;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Errore nel batch OpenAI: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return model;
    }
}
