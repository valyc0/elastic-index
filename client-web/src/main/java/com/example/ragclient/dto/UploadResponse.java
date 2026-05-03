package com.example.ragclient.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String documentId;
    private String fileName;
    private Integer sections;
    private Integer chunks;
    private String parser;
    private String message;
    private String error;
}
