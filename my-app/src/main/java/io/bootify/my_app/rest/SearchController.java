package io.bootify.my_app.rest;

import io.bootify.my_app.model.SearchRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.ElasticsearchSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final ElasticsearchSearchService searchService;

    public SearchController(ElasticsearchSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/simple")
    public ResponseEntity<List<SearchResult>> simpleSearch(@RequestBody SearchRequest request) {
        try {
            log.info("Simple search request: query='{}', language='{}'", request.getQuery(), request.getLanguage());
            int size = request.getSize() != null ? request.getSize() : 10;
            List<SearchResult> results = searchService.search(
                request.getQuery(),
                request.getLanguage(),
                size
            );
            log.info("Simple search completed: {} results", results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error in simple search", e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/advanced")
    public ResponseEntity<List<SearchResult>> advancedSearch(@RequestBody SearchRequest request) {
        try {
            log.info("Advanced search request: query='{}', language='{}'", request.getQuery(), request.getLanguage());
            int size = request.getSize() != null ? request.getSize() : 10;
            List<SearchResult> results = searchService.searchAdvanced(
                request.getQuery(),
                request.getLanguage(),
                size
            );
            log.info("Advanced search completed: {} results", results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error in advanced search", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/quick")
    public ResponseEntity<List<SearchResult>> quickSearch(
            @RequestParam String q,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "10") int size) {
        try {
            log.info("Quick search request: query='{}', language='{}', size={}", q, lang, size);
            List<SearchResult> results = searchService.searchAdvanced(q, lang, size);
            log.info("Quick search completed: {} results", results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error in quick search", e);
            return ResponseEntity.status(500).build();
        }
    }
}
