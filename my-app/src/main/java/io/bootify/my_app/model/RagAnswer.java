package io.bootify.my_app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Risposta della pipeline RAG: include la risposta generata dall'LLM,
 * i chunk di contesto usati come fonti e le domande di approfondimento
 * suggerite dall'AI per raffinare la conversazione.
 */
public class RagAnswer {

    /** Risposta in linguaggio naturale generata dall'LLM. */
    private String answer;

    /** Chunks usati come contesto per generare la risposta. */
    private List<RagSource> sources;

    /** Modello LLM usato per la generazione. */
    private String llmModel;

    /** Modello di embedding usato per il retrieval. */
    private String embeddingModel;

    /** Query originale dell'utente. */
    private String query;

    /** Tempo di elaborazione totale in ms. */
    private long processingTimeMs;

    /**
     * Domande di approfondimento suggerite dall'AI per ottenere risposte più precise.
     * L'utente può reinviare una nuova request con lo stesso {@code sessionId} e la
     * domanda scelta come {@code query}.
     */
    private List<String> followUpQuestions = new ArrayList<>();

    /**
     * True se l'AI ha rilevato che la domanda è ambigua o troppo generica
     * e ha generato domande di chiarimento in {@code followUpQuestions}.
     */
    private boolean needsClarification;

    /** ID della sessione conversazionale associata a questa risposta. */
    private String sessionId;

    public RagAnswer() {}

    public RagAnswer(String answer, List<RagSource> sources, String llmModel,
                     String embeddingModel, String query, long processingTimeMs) {
        this.answer = answer;
        this.sources = sources;
        this.llmModel = llmModel;
        this.embeddingModel = embeddingModel;
        this.query = query;
        this.processingTimeMs = processingTimeMs;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<RagSource> getSources() { return sources; }
    public void setSources(List<RagSource> sources) { this.sources = sources; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public List<String> getFollowUpQuestions() { return followUpQuestions; }
    public void setFollowUpQuestions(List<String> followUpQuestions) {
        this.followUpQuestions = followUpQuestions != null ? followUpQuestions : new ArrayList<>();
    }

    public boolean isNeedsClarification() { return needsClarification; }
    public void setNeedsClarification(boolean needsClarification) {
        this.needsClarification = needsClarification;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /**
     * Fonte usata nella risposta RAG (singolo chunk con metadati).
     */
    public static class RagSource {

        private String documentId;
        private String fileName;
        private String chapterTitle;
        private Integer chapterIndex;
        private Integer chunkIndex;
        private String content;
        private float relevanceScore;

        public RagSource() {}

        public RagSource(String documentId, String fileName, String chapterTitle,
                         Integer chapterIndex, Integer chunkIndex, String content,
                         float relevanceScore) {
            this.documentId = documentId;
            this.fileName = fileName;
            this.chapterTitle = chapterTitle;
            this.chapterIndex = chapterIndex;
            this.chunkIndex = chunkIndex;
            this.content = content;
            this.relevanceScore = relevanceScore;
        }

        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getChapterTitle() { return chapterTitle; }
        public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

        public Integer getChapterIndex() { return chapterIndex; }
        public void setChapterIndex(Integer chapterIndex) { this.chapterIndex = chapterIndex; }

        public Integer getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public float getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(float relevanceScore) { this.relevanceScore = relevanceScore; }
    }
}
