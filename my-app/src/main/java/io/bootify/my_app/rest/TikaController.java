package io.bootify.my_app.rest;

import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.DocumentExtractionService;
import io.bootify.my_app.service.SemanticIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;
/**
 * Controller per la pipeline Tika-RAG.
 *
 * <p>Flusso:
 * <pre>
 *   POST /api/tika/index
 *     → DocumentExtractionService  (estrazione testo con Apache Tika; per PDF tenta
 *                                   anche l'outline via PDFBox per ottenere i capitoli)
 *     → SemanticIndexService        (identico a Docling: chunking semantico + embedding + ES index)
 *
 *   POST /api/tika/ask
 *     → RagService  (hybrid search BM25+kNN + LLM — stesso indice di Docling)
 * </pre>
 *
 * <p>Rispetto a {@code /api/docling/index}, questa pipeline è più veloce (nessun
 * microservizio esterno, nessun modello ML di layout) ma produce struttura di
 * sezioni meno precisa su documenti complessi o con layout non lineari.
 * Entrambe le pipeline indicizzano nello stesso indice {@code semantic_docs}.
 * Per la RAG/ricerca usare {@code /api/rag/ask} o {@code /api/rag/search}.
 */
@RestController
@RequestMapping("/api/tika")
public class TikaController {

    private static final Logger log = LoggerFactory.getLogger(TikaController.class);

    private final DocumentExtractionService documentExtractionService;
    private final SemanticIndexService semanticIndexService;

    public TikaController(DocumentExtractionService documentExtractionService,
                          SemanticIndexService semanticIndexService) {
        this.documentExtractionService = documentExtractionService;
        this.semanticIndexService = semanticIndexService;
    }

    /**
     * Estrazione con Apache Tika + indicizzazione semantica.
     *
     * <p>Accetta tutti i formati supportati da Tika: PDF, DOCX, PPTX, XLSX,
     * HTML, TXT, RTF, ODF e molti altri.
     *
     * <pre>
     * POST /api/tika/index
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

        log.info("Tika index: file={}, size={} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            // 1. Estrazione testo e metadati con Apache Tika
            //    (per PDF tenta anche outline capitoli via PDFBox)
            DocumentExtractionResult extracted =
                    documentExtractionService.extractTextAndMetadata(file);

            // 2. Indicizzazione semantica (chunking + embedding + ES)
            //    — identica al percorso Docling
            String documentId = UUID.randomUUID().toString();
            int chunks = semanticIndexService.indexDocument(documentId, extracted);

            int sectionCount = extracted.getChapters() != null
                    ? extracted.getChapters().size() : 0;

            log.info("Tika index completato: documentId={}, sezioni={}, chunks={}",
                    documentId, sectionCount, chunks);

            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "fileName", extracted.getFileName(),
                    "sections", sectionCount,
                    "chunks", chunks,
                    "parser", "tika",
                    "message", "Documento indicizzato con Apache Tika"
            ));

        } catch (Exception e) {
            log.error("Errore indicizzazione Tika: file={}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
