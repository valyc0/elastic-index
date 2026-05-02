package io.bootify.my_app.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import io.bootify.my_app.service.llm.LlmProvider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Gestisce il budgeting del contesto in token usando una tokenizzazione reale.
 *
 * <p>Per modelli OpenAI noti usa il tokenizer specifico del modello tramite jtokkit.
 * Per modelli non riconosciuti (molti modelli Ollama/OpenRouter) usa il fallback
 * {@code cl100k_base}, che resta una tokenizzazione reale ma non perfettamente
 * coincidente con il tokenizer nativo del provider.
 */
@Service
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);

    private final EncodingRegistry encodingRegistry = Encodings.newDefaultEncodingRegistry();

    public int estimateTextTokens(String text, String modelName) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Encoding encoding = resolveEncoding(modelName);
        return encoding.countTokens(text);
    }

    public int estimateMessagesTokens(List<ChatMessage> messages, String modelName) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        Encoding encoding = resolveEncoding(modelName);
        int total = 0;
        for (ChatMessage message : messages) {
            total += 4;
            total += encoding.countTokens(message.role());
            total += encoding.countTokens(message.content());
        }
        return total + 2;
    }

    private Encoding resolveEncoding(String modelName) {
        ModelType modelType = resolveModelType(modelName);
        if (modelType != null) {
            return encodingRegistry.getEncodingForModel(modelType);
        }
        return encodingRegistry.getEncoding(EncodingType.CL100K_BASE);
    }

    private ModelType resolveModelType(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String normalized = modelName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("openai/")) {
            normalized = normalized.substring("openai/".length());
        }
        if (normalized.contains("gpt-4o-mini")) {
            return ModelType.GPT_4O_MINI;
        }
        if (normalized.contains("gpt-4o")) {
            return ModelType.GPT_4O;
        }
        if (normalized.contains("gpt-4-turbo")) {
            return ModelType.GPT_4_TURBO;
        }
        if (normalized.contains("gpt-4")) {
            return ModelType.GPT_4;
        }
        if (normalized.contains("gpt-3.5-turbo")) {
            return ModelType.GPT_3_5_TURBO;
        }
        if (normalized.contains("text-embedding-3-large")) {
            return ModelType.TEXT_EMBEDDING_3_LARGE;
        }
        if (normalized.contains("text-embedding-3-small")) {
            return ModelType.TEXT_EMBEDDING_3_SMALL;
        }
        if (normalized.contains("text-embedding-ada-002")) {
            return ModelType.TEXT_EMBEDDING_ADA_002;
        }
        log.debug("Tokenizer specifico non disponibile per model='{}', uso cl100k_base", modelName);
        return null;
    }

}