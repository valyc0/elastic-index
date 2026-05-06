package io.bootify.my_app.rest;

import io.bootify.my_app.model.ChapterSection;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.ParsedDocument;
import io.bootify.my_app.service.ParsedDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Controller per la gestione dei documenti parsati (stato TRANSCRIBED/INDEXED/ERROR).
 *
 * <pre>
 * GET    /api/documents/parsed          → lista documenti con stato
 * GET    /api/documents/parsed/{id}     → dettaglio con sezioni per editor
 * PUT    /api/documents/parsed/{id}/sections → salva sezioni modificate
 * POST   /api/documents/parsed/{id}/index   → chunking + embedding + ES
 * DELETE /api/documents/parsed/{id}    → elimina da H2 e opzionalmente da ES
 * </pre>
 */
@RestController
@RequestMapping("/api/documents/parsed")
public class ParsedDocumentController {

    private static final Logger log = LoggerFactory.getLogger(ParsedDocumentController.class);

    private final ParsedDocumentService service;

    public ParsedDocumentController(ParsedDocumentService service) {
        this.service = service;
    }

    /** DTO di risposta per la lista (senza parsedJson per non appesantire). */
    public record ParsedDocumentSummary(
            String id,
            String fileName,
            String state,
            Integer pageCount,
            Integer sectionCount,
            Integer chunks,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** DTO di risposta con sezioni per l'editor. */
    public record ParsedDocumentDetail(
            String id,
            String fileName,
            String state,
            Integer pageCount,
            Integer sectionCount,
            Integer chunks,
            String errorMessage,
            List<ChapterSection> chapters,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** DTO per aggiornamento sezioni. */
    public record UpdateSectionsRequest(List<ChapterSection> chapters) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<ParsedDocumentSummary>> list() {
        List<ParsedDocumentSummary> result = service.findAll().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ParsedDocumentDetail> getDetail(@PathVariable String id) {
        try {
            ParsedDocument doc = service.findById(id);
            DocumentExtractionResult result = service.loadExtractionResult(id);
            return ResponseEntity.ok(new ParsedDocumentDetail(
                    doc.getId(), doc.getFileName(), doc.getState(),
                    doc.getPageCount(), doc.getSectionCount(), doc.getChunks(),
                    doc.getErrorMessage(),
                    result.getChapters(),
                    doc.getCreatedAt(), doc.getUpdatedAt()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/sections")
    public ResponseEntity<Map<String, Object>> updateSections(
            @PathVariable String id,
            @RequestBody UpdateSectionsRequest request) {
        try {
            service.updateSections(id, request.chapters());
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "sections", request.chapters().size(),
                    "message", "Sezioni aggiornate"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Errore aggiornamento sezioni id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<Map<String, Object>> indexDocument(@PathVariable String id) {
        try {
            // Imposta subito INDEXING e lancia in background
            service.markIndexing(id);
            service.indexDocumentAsync(id);
            return ResponseEntity.accepted().body(Map.of(
                    "id", id,
                    "state", "INDEXING",
                    "message", "Indicizzazione avviata in background"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Errore avvio indicizzazione id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("id", id, "message", "Documento eliminato"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Errore eliminazione id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ParsedDocumentSummary toSummary(ParsedDocument doc) {
        return new ParsedDocumentSummary(
                doc.getId(), doc.getFileName(), doc.getState(),
                doc.getPageCount(), doc.getSectionCount(), doc.getChunks(),
                doc.getErrorMessage(), doc.getCreatedAt(), doc.getUpdatedAt()
        );
    }
}
