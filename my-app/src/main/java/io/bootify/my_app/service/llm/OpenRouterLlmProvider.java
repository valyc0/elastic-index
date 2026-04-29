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
 * Implementazione di {@link LlmProvider} basata su OpenRouter.
 *
 * <p>OpenRouter espone un'API compatibile OpenAI ma instrada le richieste a decine
 * di modelli (Claude, Gemini, Llama, Mistral, ecc.) con un'unica chiave API.
 *
 * <p>Attivata quando {@code llm.provider=openrouter}.
 *
 * <p>Modelli consigliati (formato "provider/model"):
 * <ul>
 *   <li>{@code anthropic/claude-3-haiku}: veloce ed economico</li>
 *   <li>{@code google/gemini-flash-1.5}: ottimo rapporto qualità/costo</li>
 *   <li>{@code meta-llama/llama-3.1-8b-instruct:free}: gratuito</li>
 *   <li>{@code mistralai/mistral-7b-instruct:free}: gratuito</li>
 * </ul>
 *
 * @see <a href="https://openrouter.ai/docs">OpenRouter Docs</a>
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openrouter")
public class OpenRouterLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmProvider.class);

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String siteUrl;
    private final String siteName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenRouterLlmProvider(
            @Value("${openrouter.api.url:https://openrouter.ai/api/v1/chat/completions}") String apiUrl,
            @Value("${openrouter.api.key}") String apiKey,
            @Value("${openrouter.chat.model:meta-llama/llama-3.1-8b-instruct:free}") String model,
            @Value("${openrouter.chat.max-tokens:1024}") int maxTokens,
            @Value("${openrouter.site.url:http://localhost:8080}") String siteUrl,
            @Value("${openrouter.site.name:my-app}") String siteName) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.siteUrl = siteUrl;
        this.siteName = siteName;
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
            log.debug("OpenRouter chat request: model={}, messages={}", model, messages.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", siteUrl)
                    .header("X-OpenRouter-Title", siteName)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException(
                        "OpenRouter chat error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText();
            if (answer.isBlank()) {
                throw new LlmException("Risposta OpenRouter vuota: " + response.body());
            }
            log.debug("OpenRouter risposta ricevuta: {} chars, model={}", answer.length(), model);
            return answer;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Errore nella chiamata a OpenRouter: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}
