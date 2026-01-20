package io.bootify.my_app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.ElasticsearchIndexService;
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
            System.out.println("DEBUG Controller: Received request for file: " + jsonFileName);
            Path jsonPath = Paths.get(EXTRACTED_DIR, jsonFileName);
            
            if (!Files.exists(jsonPath)) {
                return ResponseEntity.badRequest().body("File JSON non trovato: " + jsonFileName);
            }

            // Legge il file JSON
            DocumentExtractionResult result = objectMapper.readValue(jsonPath.toFile(), DocumentExtractionResult.class);
            System.out.println("DEBUG Controller: JSON loaded, text length: " + result.getText().length());
            
            // Genera ID documento
            String documentId = UUID.randomUUID().toString();
            
            System.out.println("DEBUG Controller: Starting indexing...");
            // Indicizza su Elasticsearch
            elasticsearchIndexService.indexDocument(
                documentId,
                result.getFileName(),
                result.getText()
            );

            return ResponseEntity.ok("Documento indicizzato con ID: " + documentId);

        } catch (Exception e) {
            System.err.println("DEBUG Controller: Exception caught: " + e.getClass().getName());
            e.printStackTrace();
            return ResponseEntity.status(500).body("Errore durante l'indicizzazione: " + e.getMessage());
        }
    }

    @PostMapping("/from-extraction")
    public ResponseEntity<String> indexFromExtraction(@RequestBody DocumentExtractionResult extraction) {
        try {
            String documentId = UUID.randomUUID().toString();
            
            elasticsearchIndexService.indexDocument(
                documentId,
                extraction.getFileName(),
                extraction.getText()
            );

            return ResponseEntity.ok("Documento indicizzato con ID: " + documentId);

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Errore durante l'indicizzazione: " + e.getMessage());
        }
    }
}
