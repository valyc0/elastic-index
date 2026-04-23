package io.bootify.my_app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Chunk di documento destinato all'indice semantico con embedding ELSER.
 * Il campo content_embedding viene popolato automaticamente dall'ingest pipeline di Elasticsearch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticChunk {

    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("chunkIndex")
    private Integer chunkIndex;

    @JsonProperty("content")
    private String content;

    @JsonProperty("chapterTitle")
    private String chapterTitle;

    @JsonProperty("chapterIndex")
    private Integer chapterIndex;

    public SemanticChunk() {
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public Integer getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(Integer chapterIndex) {
        this.chapterIndex = chapterIndex;
    }
}
