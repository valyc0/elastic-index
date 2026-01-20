package io.bootify.my_app.model;

import java.util.Map;

public class DocumentExtractionResult {

    private String text;
    private Map<String, String> metadata;
    private String fileName;

    public DocumentExtractionResult() {
    }

    public DocumentExtractionResult(String text, Map<String, String> metadata, String fileName) {
        this.text = text;
        this.metadata = metadata;
        this.fileName = fileName;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
