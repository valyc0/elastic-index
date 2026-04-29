package io.bootify.my_app.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.List;

/**
 * Implementazione di {@link LlmProvider} basata su Ollama Chat API.
 *
 * <p>Attivata quando {@code llm.provider=ollama} (default).
 *
 * <p>API: POST /api/chat
 * <pre>
 * {
 *   "model": "llama3",
 *   "messages": [{"role": "user", "content": "..."}],
 *   "stream": false
 * }
 * </pre>
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);

    private final String ollamaUrl;
    private final String chatModel;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaLlmProvider(
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ollama.chat.model:llama3}") String chatModel,
            @Value("${ollama.chat.timeout-seconds:300}") int timeoutSeconds) {
        this.ollamaUrl = ollamaUrl;
        this.chatModel = chatModel;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        return complete(List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", userMessage)
        ));
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", chatModel);
            body.put("stream", false);

            ArrayNode messagesNode = body.putArray("messages");
            for (ChatMessage msg : messages) {
                ObjectNode msgNode = messagesNode.addObject();
                msgNode.put("role", msg.role());
                msgNode.put("content", msg.content());
            }

            String requestBody = objectMapper.writeValueAsString(body);
            log.debug("Ollama chat request: model={}, messages={}", chatModel, messages.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException(
                        "Ollama chat error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("message").path("content").asText();
            if (answer.isBlank()) {
                throw new LlmException("Risposta Ollama vuota: " + response.body());
            }
            log.debug("Ollama risposta ricevuta: {} chars", answer.length());
            return answer;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Errore nella chiamata a Ollama chat: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return chatModel;
    }
}
