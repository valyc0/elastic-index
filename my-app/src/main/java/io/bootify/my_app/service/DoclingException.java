package io.bootify.my_app.service;

/**
 * Eccezione unchecked per errori nel client Docling.
 */
public class DoclingException extends RuntimeException {

    public DoclingException(String message) {
        super(message);
    }

    public DoclingException(String message, Throwable cause) {
        super(message, cause);
    }
}
