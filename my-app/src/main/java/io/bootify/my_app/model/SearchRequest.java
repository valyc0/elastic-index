package io.bootify.my_app.model;

import java.util.List;

public class SearchRequest {
    
    private String query;
    private String language;
    private Integer size;

    public SearchRequest() {
    }

    public SearchRequest(String query, String language, Integer size) {
        this.query = query;
        this.language = language;
        this.size = size;
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
}
