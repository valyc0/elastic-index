package io.bootify.my_app.service.llm;

/**
 * Eccezione unchecked per errori nel processo di generazione LLM.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
