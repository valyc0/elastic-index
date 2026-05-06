package com.example.ragclient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Dettaglio di un documento parsato con sezioni per l'editor.
 * Corrisponde a GET /api/documents/parsed/{id}.
 */
@Data
@NoArgsConstructor
public class ParsedDocumentDetail {
    private String id;
    private String fileName;
    private String state;
    private Integer pageCount;
    private Integer sectionCount;
    private Integer chunks;
    private String errorMessage;
    private List<ChapterSectionDto> chapters;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    public static class ChapterSectionDto {
        private String title;
        private int chapterIndex;
        private String text;
    }
}
