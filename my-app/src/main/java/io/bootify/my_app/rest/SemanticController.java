package io.bootify.my_app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.SearchRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.DocumentExtractionService;
import io.bootify.my_app.service.SemanticIndexService;
import io.bootify.my_app.service.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API per indicizzazione semantica di PDF e ricerca per similitudine.
 *
 * <p>Flusso indicizzazione:
 * <pre>
 *   POST /api/semantic/index  (multipart file)
 *     → estrae testo con Tika
 *     → suddivide in chunk
 *     → indicizza in Elasticsearch tramite ingest pipeline
 *       che genera automaticamente l'embedding ELSER sul campo "content"
 * </pre>
 *
 * <p>Flusso ricerca:
 * <pre>
 *   POST /api/semantic/search  { "query": "...", "size": 10 }
 *     → text_expansion query con ELSER
 *     → restituisce chunk ordinati per score di similitudine
 * </pre>
 */
@RestController
@RequestMapping("/api/semantic")
public class SemanticController {

    private static final Logger log = LoggerFactory.getLogger(SemanticController.class);

    private static final String EXTRACTED_DIR = "extracted-documents";

    private final DocumentExtractionService documentExtractionService;
    private final SemanticIndexService semanticIndexService;
    private final SemanticSearchService semanticSearchService;
    private final ObjectMapper objectMapper;

    public SemanticController(DocumentExtractionService documentExtractionService,
                              SemanticIndexService semanticIndexService,
                              SemanticSearchService semanticSearchService,
                              ObjectMapper objectMapper) {
        this.documentExtractionService = documentExtractionService;
        this.semanticIndexService = semanticIndexService;
        this.semanticSearchService = semanticSearchService;
        this.objectMapper = objectMapper;
    }

    /**
     * Carica un PDF, ne estrae il testo e lo indicizza con embedding ELSER.
     *
     * @param file file PDF da caricare
     * @return documentId, nome file e numero di chunk indicizzati
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, Object>> indexPdf(
            @RequestParam("file") MultipartFile file) {

        log.info("Semantic index request: {}", file.getOriginalFilename());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File vuoto"));
        }

        try {
            DocumentExtractionResult extracted =
                    documentExtractionService.extractTextAndMetadata(file);
            String documentId = UUID.randomUUID().toString();
            int chunks = semanticIndexService.indexDocument(documentId, extracted);

            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "fileName", extracted.getFileName(),
                    "chunks", chunks,
                    "index", "semantic_docs",
                    "message", "Documento indicizzato con embedding ELSER"
            ));

        } catch (Exception e) {
            log.error("Error during semantic indexing: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Ricerca per similitudine semantica.
     *
     * @param request { "query": "testo della domanda", "size": 10 }
     * @return lista di chunk ordinata per score decrescente
     */
    @PostMapping("/search")
    public ResponseEntity<List<SearchResult>> search(
            @RequestBody SearchRequest request) {

        log.info("Semantic search request: query='{}', size={}", request.getQuery(), request.getSize());

        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            int size = request.getSize() != null ? request.getSize() : 10;
            List<SearchResult> results = semanticSearchService.search(request.getQuery(), size);
            log.info("Semantic search completed: {} results", results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error in semantic search for query='{}'", request.getQuery(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Indicizza da un file JSON già estratto nella cartella extracted-documents.
     *
     * @param jsonFileName nome del file JSON
     */
    @PostMapping("/index/from-json")
    public ResponseEntity<Map<String, Object>> indexFromJson(
            @RequestParam("jsonFile") String jsonFileName) {

        log.info("Semantic index from-json request: {}", jsonFileName);
        Path jsonPath = Paths.get(EXTRACTED_DIR, jsonFileName);

        if (!Files.exists(jsonPath)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File JSON non trovato: " + jsonFileName));
        }

        try {
            DocumentExtractionResult result =
                    objectMapper.readValue(jsonPath.toFile(), DocumentExtractionResult.class);
            String documentId = java.util.UUID.randomUUID().toString();
            int chunks = semanticIndexService.indexDocument(documentId, result);

            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "fileName", result.getFileName(),
                    "chunks", chunks,
                    "index", "semantic_docs",
                    "message", "Documento indicizzato con embedding ELSER"
            ));
        } catch (Exception e) {
            log.error("Error during semantic indexing from JSON: {}", jsonFileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
