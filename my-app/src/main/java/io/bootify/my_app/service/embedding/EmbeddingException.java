package io.bootify.my_app.service.embedding;

/**
 * Eccezione unchecked per errori nel processo di generazione embedding.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
