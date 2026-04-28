package io.bootify.my_app.model;

import java.util.Map;

/**
 * Request per la pipeline RAG.
 */
public class RagRequest {

    /** Domanda dell'utente in linguaggio naturale. */
    private String query;

    /** Numero di chunk da recuperare per il contesto (default: 5). */
    private Integer topK;

    /** Filtri opzionali sui metadati (es. fileName, documentId). */
    private Map<String, String> metadataFilter;

    /**
     * Lingua attesa della risposta (es. "it", "en").
     * Se null, il modello risponde nella lingua della domanda.
     */
    private String language;

    /**
     * Limita la ricerca a un singolo documento tramite il suo ID.
     * Se null, la ricerca è corpus-wide.
     */
    private String documentId;

    public RagRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public Map<String, String> getMetadataFilter() { return metadataFilter; }
    public void setMetadataFilter(Map<String, String> metadataFilter) { this.metadataFilter = metadataFilter; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
}
