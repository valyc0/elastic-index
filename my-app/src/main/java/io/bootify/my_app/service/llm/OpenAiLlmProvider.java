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
 * Implementazione di {@link LlmProvider} basata su OpenAI Chat Completions API.
 *
 * <p>Attivata quando {@code llm.provider=openai}.
 *
 * <p>Modelli consigliati:
 * <ul>
 *   <li>{@code gpt-4o-mini}: ottimo rapporto qualità/costo per RAG</li>
 *   <li>{@code gpt-4o}: massima qualità</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmProvider.class);
    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLlmProvider(
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.chat.model:gpt-4o-mini}") String model,
            @Value("${openai.chat.max-tokens:1024}") int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
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
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            ArrayNode messagesNode = body.putArray("messages");
            for (ChatMessage msg : messages) {
                ObjectNode msgNode = messagesNode.addObject();
                msgNode.put("role", msg.role());
                msgNode.put("content", msg.content());
            }

            String requestBody = objectMapper.writeValueAsString(body);
            log.debug("OpenAI chat request: model={}, messages={}", model, messages.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_CHAT_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException(
                        "OpenAI chat error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText();
            if (answer.isBlank()) {
                throw new LlmException("Risposta OpenAI vuota: " + response.body());
            }
            log.debug("OpenAI risposta ricevuta: {} chars", answer.length());
            return answer;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Errore nella chiamata a OpenAI: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}
