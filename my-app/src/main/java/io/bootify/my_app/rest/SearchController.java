package io.bootify.my_app.rest;

import io.bootify.my_app.model.SearchRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.ElasticsearchSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ElasticsearchSearchService searchService;

    public SearchController(ElasticsearchSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/simple")
    public ResponseEntity<List<SearchResult>> simpleSearch(@RequestBody SearchRequest request) {
        try {
            int size = request.getSize() != null ? request.getSize() : 10;
            List<SearchResult> results = searchService.search(
                request.getQuery(),
                request.getLanguage(),
                size
            );
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/advanced")
    public ResponseEntity<List<SearchResult>> advancedSearch(@RequestBody SearchRequest request) {
        try {
            int size = request.getSize() != null ? request.getSize() : 10;
            List<SearchResult> results = searchService.searchAdvanced(
                request.getQuery(),
                request.getLanguage(),
                size
            );
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/quick")
    public ResponseEntity<List<SearchResult>> quickSearch(
            @RequestParam String q,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<SearchResult> results = searchService.searchAdvanced(q, lang, size);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
