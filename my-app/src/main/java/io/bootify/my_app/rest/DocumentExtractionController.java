package io.bootify.my_app.rest;

import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.DocumentExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentExtractionController {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionController.class);
    
    private final DocumentExtractionService documentExtractionService;

    public DocumentExtractionController(DocumentExtractionService documentExtractionService) {
        this.documentExtractionService = documentExtractionService;
    }

    @PostMapping("/extract")
    public ResponseEntity<DocumentExtractionResult> extractDocument(
            @RequestParam("file") MultipartFile file) {
        
        log.info("Received file upload request: {}", file.getOriginalFilename());
        
        if (file.isEmpty()) {
            log.warn("Received empty file");
            return ResponseEntity.badRequest().build();
        }

        try {
            DocumentExtractionResult result = documentExtractionService.extractTextAndMetadata(file);
            log.info("Document extracted successfully: {} ({} chars)", 
                     result.getFileName(), result.getText().length());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error processing document: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
