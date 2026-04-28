package io.bootify.my_app.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Implementazione di {@link EmbeddingProvider} basata su Ollama.
 *
 * <p>Utilizza il modello {@code nomic-embed-text} (768 dims, Apache-2.0)
 * in esecuzione locale. Zero costi, nessuna dipendenza da cloud.
 *
 * <p>Attivata quando {@code embedding.provider=ollama} (default).
 */
@Service
@ConditionalOnProperty(name = "embedding.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final String ollamaUrl;
    private final String embedModel;
    private final int dimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingProvider(
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ollama.embed.model:nomic-embed-text}") String embedModel,
            @Value("${ollama.embed.dimensions:768}") int dimensions) {
        this.ollamaUrl = ollamaUrl;
        this.embedModel = embedModel;
        this.dimensions = dimensions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Il testo da embeddare non può essere vuoto");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", embedModel);
            body.put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "Ollama embed error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new EmbeddingException(
                        "Risposta Ollama priva del campo 'embedding': " + response.body());
            }

            List<Float> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode v : embeddingNode) {
                vector.add(v.floatValue());
            }
            log.debug("Embedding generato: model={}, dims={}", embedModel, vector.size());
            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Errore nella chiamata a Ollama: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return embedModel;
    }
}
