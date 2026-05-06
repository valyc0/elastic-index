package io.bootify.my_app.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entità JPA che rappresenta un documento parsato da Docling in attesa di indicizzazione.
 *
 * <p>Stati possibili:
 * <ul>
 *   <li>TRANSCRIBED – parsing completato, salvato su H2, non ancora in Elasticsearch</li>
 *   <li>INDEXED     – indicizzato in Elasticsearch, ricercabile</li>
 *   <li>ERROR       – errore durante il parsing o l'indicizzazione</li>
 * </ul>
 */
@Entity
@Table(name = "parsed_document")
public class ParsedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String fileName;

    /** TRANSCRIBED | INDEXED | ERROR */
    @Column(nullable = false)
    private String state;

    /**
     * JSON serializzato di {@link DocumentExtractionResult}.
     * Colonna CLOB per supportare documenti grandi.
     */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String parsedJson;

    private Integer pageCount;
    private Integer sectionCount;

    /** UUID del documento in Elasticsearch. Valorizzato solo quando state=INDEXED. */
    private String documentId;

    /** Numero di chunk in Elasticsearch. Valorizzato solo quando state=INDEXED. */
    private Integer chunks;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public String getId() { return id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getParsedJson() { return parsedJson; }
    public void setParsedJson(String parsedJson) { this.parsedJson = parsedJson; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

    public Integer getSectionCount() { return sectionCount; }
    public void setSectionCount(Integer sectionCount) { this.sectionCount = sectionCount; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public Integer getChunks() { return chunks; }
    public void setChunks(Integer chunks) { this.chunks = chunks; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
