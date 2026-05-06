package io.bootify.my_app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.ChapterSection;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.ParsedDocument;
import io.bootify.my_app.repository.ParsedDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Gestisce la persistenza dei documenti parsati su H2 e l'indicizzazione su Elasticsearch.
 */
@Service
@Transactional
public class ParsedDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ParsedDocumentService.class);

    private final ParsedDocumentRepository repository;
    private final SemanticIndexService semanticIndexService;
    private final ObjectMapper objectMapper;

    public ParsedDocumentService(ParsedDocumentRepository repository,
                                  SemanticIndexService semanticIndexService,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.semanticIndexService = semanticIndexService;
        this.objectMapper = objectMapper;
    }

    /**
     * Crea un record placeholder con stato PROCESSING (chiamato al momento del submit).
     *
     * @param fileName nome del file in elaborazione
     * @return id del ParsedDocument creato
     */
    public String createPending(String fileName) {
        ParsedDocument doc = new ParsedDocument();
        doc.setFileName(fileName);
        doc.setState("PROCESSING");
        doc.setParsedJson("{}");
        ParsedDocument saved = repository.save(doc);
        log.info("Record PROCESSING creato: id={}, file={}", saved.getId(), fileName);
        return saved.getId();
    }

    /**
     * Aggiorna un record PROCESSING con il risultato del parsing → stato TRANSCRIBED.
     *
     * @param pendingId id del record placeholder
     * @param result    risultato del parsing
     */
    public void updateToTranscribed(String pendingId, DocumentExtractionResult result) {
        ParsedDocument doc = repository.findById(pendingId)
                .orElseThrow(() -> new IllegalArgumentException("Documento non trovato: " + pendingId));
        doc.setState("TRANSCRIBED");
        doc.setFileName(result.getFileName());
        doc.setSectionCount(result.getChapters() != null ? result.getChapters().size() : 0);
        try {
            doc.setParsedJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Errore serializzazione risultato Docling", e);
        }
        repository.save(doc);
        log.info("Documento aggiornato a TRANSCRIBED: id={}, file={}, sezioni={}",
                pendingId, doc.getFileName(), doc.getSectionCount());
    }

    /**
     * Salva il risultato del parsing su H2 con stato TRANSCRIBED.
     *
     * @return id del ParsedDocument creato
     */
    public String save(DocumentExtractionResult result) {
        ParsedDocument doc = new ParsedDocument();
        doc.setFileName(result.getFileName());
        doc.setState("TRANSCRIBED");
        doc.setSectionCount(result.getChapters() != null ? result.getChapters().size() : 0);

        try {
            doc.setParsedJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Errore serializzazione risultato Docling", e);
        }

        ParsedDocument saved = repository.save(doc);
        log.info("Documento salvato su H2: id={}, file={}, sezioni={}", saved.getId(), saved.getFileName(), saved.getSectionCount());
        return saved.getId();
    }

    /**
     * Lista tutti i documenti ordinati per data creazione decrescente.
     */
    @Transactional(readOnly = true)
    public List<ParsedDocument> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Carica un documento con il JSON completo delle sezioni.
     */
    @Transactional(readOnly = true)
    public ParsedDocument findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Documento non trovato: " + id));
    }

    /**
     * Deserializza le sezioni dal JSON per l'editor.
     */
    @Transactional(readOnly = true)
    public DocumentExtractionResult loadExtractionResult(String id) {
        ParsedDocument doc = findById(id);
        try {
            return objectMapper.readValue(doc.getParsedJson(), DocumentExtractionResult.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Errore deserializzazione documento: " + id, e);
        }
    }

    /**
     * Aggiorna le sezioni del documento (salvate come JSON aggiornato).
     *
     * @param id       id del documento
     * @param chapters sezioni modificate dall'utente
     */
    public void updateSections(String id, List<ChapterSection> chapters) {
        ParsedDocument doc = findById(id);
        try {
            DocumentExtractionResult result = objectMapper.readValue(doc.getParsedJson(), DocumentExtractionResult.class);
            result.setChapters(chapters);
            doc.setParsedJson(objectMapper.writeValueAsString(result));
            doc.setSectionCount(chapters.size());
            repository.save(doc);
            log.info("Sezioni aggiornate per documento id={}: {} sezioni", id, chapters.size());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Errore aggiornamento sezioni: " + id, e);
        }
    }

    /**
     * Indicizza il documento su Elasticsearch e aggiorna lo stato a INDEXED.
     *
     * @return numero di chunk creati
     */
    public int indexDocument(String id) {
        ParsedDocument doc = findById(id);
        if ("INDEXED".equals(doc.getState())) {
            log.warn("Documento {} già indicizzato, re-indicizzazione", id);
        }

        DocumentExtractionResult result;
        try {
            result = objectMapper.readValue(doc.getParsedJson(), DocumentExtractionResult.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Errore deserializzazione per indicizzazione: " + id, e);
        }

        String documentId = UUID.randomUUID().toString();
        try {
            int chunks = semanticIndexService.indexDocument(documentId, result);
            doc.setState("INDEXED");
            doc.setDocumentId(documentId);
            doc.setChunks(chunks);
            doc.setErrorMessage(null);
            repository.save(doc);
            log.info("Documento indicizzato: id={}, documentId={}, chunks={}", id, documentId, chunks);
            return chunks;
        } catch (Exception e) {
            doc.setState("ERROR");
            doc.setErrorMessage("Errore indicizzazione: " + e.getMessage());
            repository.save(doc);
            throw new RuntimeException("Errore indicizzazione documento: " + id, e);
        }
    }

    /**
     * Versione asincrona: imposta stato INDEXING, processa in background, aggiorna INDEXED/ERROR.
     * Deve essere chiamata da un altro bean (non da questo stesso service) per rispettare il proxy Spring.
     */
    @Async
    @Transactional
    public void indexDocumentAsync(String id) {
        ParsedDocument doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Documento non trovato: " + id));

        DocumentExtractionResult result;
        try {
            result = objectMapper.readValue(doc.getParsedJson(), DocumentExtractionResult.class);
        } catch (JsonProcessingException e) {
            doc.setState("ERROR");
            doc.setErrorMessage("Errore deserializzazione: " + e.getMessage());
            repository.save(doc);
            return;
        }

        String documentId = UUID.randomUUID().toString();
        try {
            int chunks = semanticIndexService.indexDocument(documentId, result);
            doc.setState("INDEXED");
            doc.setDocumentId(documentId);
            doc.setChunks(chunks);
            doc.setErrorMessage(null);
            repository.save(doc);
            log.info("(async) Documento indicizzato: id={}, chunks={}", id, chunks);
        } catch (Exception e) {
            log.error("(async) Errore indicizzazione id={}: {}", id, e.getMessage(), e);
            doc.setState("ERROR");
            doc.setErrorMessage("Errore indicizzazione: " + e.getMessage());
            repository.save(doc);
        }
    }

    /**
     * Imposta lo stato INDEXING (chiamato prima di avviare l'operazione asincrona).
     */
    public void markIndexing(String id) {
        ParsedDocument doc = findById(id);
        doc.setState("INDEXING");
        doc.setErrorMessage(null);
        repository.save(doc);
    }

    /**
     * Imposta lo stato ERROR con messaggio.
     */
    public void markError(String id, String message) {
        repository.findById(id).ifPresent(doc -> {
            doc.setState("ERROR");
            doc.setErrorMessage(message);
            repository.save(doc);
        });
    }

    /**
     * Elimina il documento da H2 (e da ES se indicizzato).
     */
    public void delete(String id) {
        ParsedDocument doc = findById(id);
        if ("INDEXED".equals(doc.getState()) && doc.getDocumentId() != null) {
            try {
                semanticIndexService.deleteChunksByFileName(doc.getFileName());
                log.info("Chunk eliminati da ES per file={}", doc.getFileName());
            } catch (Exception e) {
                log.warn("Errore eliminazione chunk ES per id={}: {}", id, e.getMessage());
            }
        }
        repository.deleteById(id);
        log.info("Documento eliminato da H2: id={}", id);
    }
}
