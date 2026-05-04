package com.example.ragclient.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stato di un job di indicizzazione Docling restituito da GET /api/docling/jobs/{jobId}.
 *
 * <p>Statuses: QUEUED → PARSING → INDEXING → DONE / ERROR
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DoclingJobStatusResponse {
    private String jobId;
    private String fileName;
    private String status;
    private String message;
    private Integer chunks;
    private Integer sections;
    private String error;
}
