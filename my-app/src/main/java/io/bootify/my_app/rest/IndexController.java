package io.bootify.my_app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.ElasticsearchIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    private final ElasticsearchIndexService elasticsearchIndexService;
    private final ObjectMapper objectMapper;
    private static final String EXTRACTED_DIR = "extracted-documents";

    public IndexController(ElasticsearchIndexService elasticsearchIndexService, ObjectMapper objectMapper) {
        this.elasticsearchIndexService = elasticsearchIndexService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/from-json")
    public ResponseEntity<String> indexFromJson(@RequestParam("jsonFile") String jsonFileName) {
        try {
            log.info("Received indexing request for file: {}", jsonFileName);
            Path jsonPath = Paths.get(EXTRACTED_DIR, jsonFileName);
            
            if (!Files.exists(jsonPath)) {
                log.warn("JSON file not found: {}", jsonFileName);
                return ResponseEntity.badRequest().body("File JSON non trovato: " + jsonFileName);
            }

            // Legge il file JSON
            DocumentExtractionResult result = objectMapper.readValue(jsonPath.toFile(), DocumentExtractionResult.class);
            log.info("JSON loaded, text length: {} chars", result.getText().length());
            
            // Genera ID documento
            String documentId = UUID.randomUUID().toString();
            
            log.info("Starting indexing with document ID: {}", documentId);
            // Indicizza su Elasticsearch
            elasticsearchIndexService.indexDocument(
                documentId,
                result.getFileName(),
                result.getText()
            );

            log.info("Document indexed successfully with ID: {}", documentId);
            return ResponseEntity.ok("Documento indicizzato con ID: " + documentId);

        } catch (Exception e) {
            log.error("Error during indexing for file: {}", jsonFileName, e);
            return ResponseEntity.status(500).body("Errore durante l'indicizzazione: " + e.getMessage());
        }
    }

    @PostMapping("/from-extraction")
    public ResponseEntity<String> indexFromExtraction(@RequestBody DocumentExtractionResult extraction) {
        try {
            log.info("Received indexing request for document: {}", extraction.getFileName());
            
            String documentId = UUID.randomUUID().toString();
            log.info("Starting indexing with document ID: {}", documentId);
            
            elasticsearchIndexService.indexDocument(
                documentId,
                extraction.getFileName(),
                extraction.getText()
            );

            log.info("Document indexed successfully with ID: {}", documentId);
            return ResponseEntity.ok("Documento indicizzato con ID: " + documentId);

        } catch (Exception e) {
            log.error("Error during indexing for document: {}", extraction.getFileName(), e);
            return ResponseEntity.status(500).body("Errore durante l'indicizzazione: " + e.getMessage());
        }
    }
}
