package io.bootify.my_app.rest;

import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.DocumentExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentExtractionController {

    private final DocumentExtractionService documentExtractionService;

    public DocumentExtractionController(DocumentExtractionService documentExtractionService) {
        this.documentExtractionService = documentExtractionService;
    }

    @PostMapping("/extract")
    public ResponseEntity<DocumentExtractionResult> extractDocument(
            @RequestParam("file") MultipartFile file) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            DocumentExtractionResult result = documentExtractionService.extractTextAndMetadata(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
