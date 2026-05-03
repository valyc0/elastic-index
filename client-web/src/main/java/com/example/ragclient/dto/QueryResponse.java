package com.example.ragclient.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryResponse {
    private String answer;
    private List<Source> sources;
    private String query;
    private Long processingTimeMs;
    private List<String> followUpQuestions;
    private boolean needsClarification;
    private String sessionId;
    private String llmModel;
    private String embeddingModel;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String documentId;
        private String fileName;
        private String chapterTitle;
        private Integer chapterIndex;
        private Integer chunkIndex;
        private String content;
        private Float relevanceScore;
    }
}
