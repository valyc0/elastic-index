package com.example.ragclient.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String jobId;
    private String status;
    private String fileName;
    private String message;
    private String error;
    // campi legacy (mantenuti per retrocompatibilità con eventuali risposte sincrone)
    private String documentId;
    private Integer sections;
    private Integer chunks;
    private String parser;
}
