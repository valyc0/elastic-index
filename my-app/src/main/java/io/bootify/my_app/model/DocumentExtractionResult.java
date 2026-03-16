package io.bootify.my_app.model;

import java.util.List;
import java.util.Map;

public class DocumentExtractionResult {

    private String text;
    private Map<String, String> metadata;
    private String fileName;
    /** Capitoli estratti dall'outline PDFBox. Null se il PDF non ha outline (verrà usato il fallback regex). */
    private List<ChapterSection> chapters;

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

    public List<ChapterSection> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterSection> chapters) {
        this.chapters = chapters;
    }
}
