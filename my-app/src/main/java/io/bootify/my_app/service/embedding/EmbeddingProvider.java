package io.bootify.my_app.service.embedding;

import java.util.List;

/**
 * Interfaccia astratta per provider di embedding vettoriale.
 *
 * <p>Consente di switchare tra provider senza modificare il codice
 * chiamante (Ollama, OpenAI, Cohere, HuggingFace, ecc.).
 *
 * <p>Dimensionalità attesa:
 * <ul>
 *   <li>nomic-embed-text (Ollama): 768 dims</li>
 *   <li>text-embedding-3-small (OpenAI): 1536 dims</li>
 *   <li>text-embedding-3-large (OpenAI): 3072 dims</li>
 * </ul>
 */
public interface EmbeddingProvider {

    /**
     * Genera un vettore di embedding per il testo fornito.
     *
     * @param text testo da embeddare (non null, non vuoto)
     * @return vettore denso normalizzato (cosine similarity ready)
     * @throws EmbeddingException in caso di errore di comunicazione o parsing
     */
    List<Float> embed(String text);

    /**
     * Genera embedding in batch per una lista di testi.
     * L'implementazione di default itera sequenzialmente;
     * le implementazioni concrete possono sovrascrivere con chiamate batch.
     *
     * @param texts lista di testi da embeddare
     * @return lista di vettori nello stesso ordine dei testi in input
     */
    default List<List<Float>> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * Restituisce il numero di dimensioni del vettore di output.
     * Deve corrispondere al mapping Elasticsearch {@code dims}.
     */
    int dimensions();

    /**
     * Nome identificativo del modello (es. "nomic-embed-text", "text-embedding-3-small").
     */
    String modelName();
}
