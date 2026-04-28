package io.bootify.my_app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Risposta del microservizio Docling.
 * Mappa la struttura JSON restituita da {@code POST /parse}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingParseResponse {

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("full_text")
    private String fullText;

    @JsonProperty("sections")
    private List<DoclingSection> sections;

    @JsonProperty("tables")
    private List<DoclingTable> tables;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    @JsonProperty("page_count")
    private Integer pageCount;

    public DoclingParseResponse() {}

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public List<DoclingSection> getSections() { return sections; }
    public void setSections(List<DoclingSection> sections) { this.sections = sections; }

    public List<DoclingTable> getTables() { return tables; }
    public void setTables(List<DoclingTable> tables) { this.tables = tables; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

    /**
     * Sezione strutturata estratta da Docling (titolo + testo + livello gerarchico).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoclingSection {

        @JsonProperty("title")
        private String title;

        @JsonProperty("chapter_index")
        private int chapterIndex;

        @JsonProperty("text")
        private String text;

        /** 1 = H1 (capitolo), 2 = H2 (sezione), 3 = H3 (sottosezione), 0 = testo pre-titolo */
        @JsonProperty("level")
        private int level;

        @JsonProperty("page_number")
        private Integer pageNumber;

        /** Titolo del capitolo H1 padre. Null se questa sezione è già un H1. */
        @JsonProperty("parent_chapter_title")
        private String parentChapterTitle;

        /** chapter_index del capitolo H1 padre. Null se questa sezione è già un H1. */
        @JsonProperty("parent_chapter_index")
        private Integer parentChapterIndex;

        public DoclingSection() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public int getChapterIndex() { return chapterIndex; }
        public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = level; }

        public Integer getPageNumber() { return pageNumber; }
        public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }

        public String getParentChapterTitle() { return parentChapterTitle; }
        public void setParentChapterTitle(String parentChapterTitle) { this.parentChapterTitle = parentChapterTitle; }

        public Integer getParentChapterIndex() { return parentChapterIndex; }
        public void setParentChapterIndex(Integer parentChapterIndex) { this.parentChapterIndex = parentChapterIndex; }

        /** Restituisce true se questa sezione è un capitolo H1. */
        public boolean isChapter() { return level == 1; }

        /** Restituisce true se questa sezione è una sottosezione (H2 o più profonda). */
        public boolean isSubsection() { return level >= 2; }
    }

    /**
     * Tabella estratta da Docling (testo rappresentativo per embedding).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoclingTable {

        @JsonProperty("caption")
        private String caption;

        @JsonProperty("text_representation")
        private String textRepresentation;

        @JsonProperty("page_number")
        private Integer pageNumber;

        public DoclingTable() {}

        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }

        public String getTextRepresentation() { return textRepresentation; }
        public void setTextRepresentation(String textRepresentation) { this.textRepresentation = textRepresentation; }

        public Integer getPageNumber() { return pageNumber; }
        public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    }
}
