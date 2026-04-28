package io.bootify.my_app.rest;

import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.RagAnswer;
import io.bootify.my_app.model.RagRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.DoclingClient;
import io.bootify.my_app.service.DoclingException;
import io.bootify.my_app.service.RagService;
import io.bootify.my_app.service.SemanticIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller per la pipeline Docling-RAG.
 *
 * <p>Flusso completo:
 * <pre>
 *   POST /api/docling/index
 *     → DoclingClient  (parsing strutturato: sezioni, tabelle, metadati)
 *     → SemanticIndexService  (chunking semantico + embedding + ES index)
 *
 *   POST /api/docling/ask
 *     → RagService  (hybrid search BM25+kNN + LLM)
 *
 *   GET  /api/docling/health
 *     → verifica disponibilità servizio Docling Python
 * </pre>
 *
 * <p>A differenza di {@code /api/semantic/index} (che usa Apache Tika),
 * questo endpoint utilizza Docling per un parsing strutturalmente più ricco:
 * titoli gerarchici, tabelle strutturate, layout preservato.
 */
@RestController
@RequestMapping("/api/docling")
public class DoclingController {

    private static final Logger log = LoggerFactory.getLogger(DoclingController.class);

    private final DoclingClient doclingClient;
    private final SemanticIndexService semanticIndexService;
    private final RagService ragService;

    public DoclingController(DoclingClient doclingClient,
                              SemanticIndexService semanticIndexService,
                              RagService ragService) {
        this.doclingClient = doclingClient;
        this.semanticIndexService = semanticIndexService;
        this.ragService = ragService;
    }

    /**
     * Parsing strutturato con Docling + indicizzazione semantica.
     *
     * <p>Accetta: PDF, DOCX, HTML, PPTX, XLSX, Markdown, AsciiDoc
     *
     * <pre>
     * POST /api/docling/index
     * Content-Type: multipart/form-data
     * file: &lt;binary&gt;
     * </pre>
     */
    @PostMapping(value = "/index", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> indexDocument(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File vuoto"));
        }

        log.info("Docling index: file={}, size={} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            // 1. Parsing strutturato con Docling
            DocumentExtractionResult extracted = doclingClient.parse(file);

            // 2. Indicizzazione semantica (chunking + embedding + ES)
            String documentId = UUID.randomUUID().toString();
            int chunks = semanticIndexService.indexDocument(documentId, extracted);

            int sectionCount = extracted.getChapters() != null
                    ? extracted.getChapters().size() : 0;

            log.info("Docling index completato: documentId={}, sezioni={}, chunks={}",
                    documentId, sectionCount, chunks);

            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "fileName", extracted.getFileName(),
                    "sections", sectionCount,
                    "chunks", chunks,
                    "parser", "docling",
                    "message", "Documento indicizzato con Docling"
            ));

        } catch (DoclingException e) {
            log.error("Docling service error per file={}: {}", file.getOriginalFilename(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", e.getMessage(),
                            "hint", "Verificare che il servizio Docling sia avviato su http://localhost:8001"
                    ));
        } catch (Exception e) {
            log.error("Errore indicizzazione Docling: file={}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Pipeline RAG su documenti indicizzati con Docling.
     *
     * <pre>
     * POST /api/docling/ask
     * {
     *   "query": "Quali sono i dati nella tabella dei risultati?",
     *   "topK": 5,
     *   "language": "it"
     * }
     * </pre>
     */
    @PostMapping("/ask")
    public ResponseEntity<RagAnswer> ask(@RequestBody RagRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            log.info("Docling RAG ask: query='{}'", request.getQuery());
            RagAnswer answer = ragService.ask(request);
            return ResponseEntity.ok(answer);
        } catch (Exception e) {
            log.error("Errore nella pipeline RAG Docling", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Health check: verifica che il servizio Python Docling sia raggiungibile.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean available = doclingClient.isAvailable();
        if (available) {
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "service", "docling-service"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "service", "docling-service",
                            "message", "Il servizio Docling Python non risponde"
                    ));
        }
    }
}
