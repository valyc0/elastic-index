package io.bootify.my_app.model;

import java.util.List;
import java.util.Map;

public class SearchRequest {
    
    private String query;
    private String language;
    private Integer size;
    private Boolean explain;
    private Map<String, String> metadataFilters;

    public SearchRequest() {
    }

    public SearchRequest(String query, String language, Integer size) {
        this.query = query;
        this.language = language;
        this.size = size;
    }

    public SearchRequest(String query, String language, Integer size, Boolean explain) {
        this.query = query;
        this.language = language;
        this.size = size;
        this.explain = explain;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public Boolean getExplain() {
        return explain;
    }

    public void setExplain(Boolean explain) {
        this.explain = explain;
    }

    public Map<String, String> getMetadataFilters() {
        return metadataFilters;
    }

    public void setMetadataFilters(Map<String, String> metadataFilters) {
        this.metadataFilters = metadataFilters;
    }
}
