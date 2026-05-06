package com.example.ragclient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Riepilogo di un documento parsato (senza sezioni complete).
 * Corrisponde a GET /api/documents/parsed.
 */
@Data
@NoArgsConstructor
public class ParsedDocumentSummary {
    private String id;
    private String fileName;
    /** TRANSCRIBED | INDEXED | ERROR */
    private String state;
    private Integer pageCount;
    private Integer sectionCount;
    private Integer chunks;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
