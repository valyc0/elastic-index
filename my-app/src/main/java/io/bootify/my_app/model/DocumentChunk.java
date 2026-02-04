package io.bootify.my_app.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class DocumentChunk {

    @JsonProperty("id")
    private String id;

    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("chunkIndex")
    private Integer chunkIndex;

    @JsonProperty("content")
    private String content;

    @JsonProperty("language")
    private String language;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    public DocumentChunk() {
    }

    public DocumentChunk(String id, String documentId, Integer chunkIndex, String content, String language, String fileName) {
        this.id = id;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.language = language;
        this.fileName = fileName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
