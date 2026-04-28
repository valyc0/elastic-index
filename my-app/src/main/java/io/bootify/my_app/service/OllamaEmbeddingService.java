package io.bootify.my_app.service;

import io.bootify.my_app.service.embedding.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @deprecated Usare {@link EmbeddingProvider} tramite iniezione diretta.
 * Mantenuto per retrocompatibilità con eventuali bean esterni.
 * Delega a {@link EmbeddingProvider} attivo nel contesto Spring.
 */
@Deprecated(since = "2.0", forRemoval = true)
@Service
public class OllamaEmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    public OllamaEmbeddingService(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    /** @deprecated Usare {@link EmbeddingProvider#embed(String)} direttamente. */
    @Deprecated
    public List<Float> embed(String text) {
        return embeddingProvider.embed(text);
    }
}

