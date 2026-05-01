package io.bootify.my_app.model;

import java.util.Map;

/**
 * Request per la pipeline RAG.
 *
 * <p>Lo storico conversazionale è gestito interamente server-side tramite {@code sessionId}.
 * Il client non deve più inviare la history: ottiene un {@code sessionId} chiamando
 * {@code POST /api/rag/session} all'apertura della pagina e lo include in ogni richiesta.
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

    /**
     * Limita la ricerca ai chunk di uno specifico file (es. "Zanna Bianca (1).pdf").
     * Se null, la ricerca è corpus-wide.
     */
    private String fileName;

    /**
     * ID della sessione conversazionale, ottenuto da {@code POST /api/rag/session}.
     * Lo storico dei turni precedenti è mantenuto server-side e recuperato automaticamente.
     * Se null o non trovato, la richiesta viene trattata come primo turno senza storia.
     */
    private String sessionId;

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

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}

