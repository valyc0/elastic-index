package io.bootify.my_app.rest;

import io.bootify.my_app.model.RagAnswer;
import io.bootify.my_app.model.RagRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.ConversationSessionService;
import io.bootify.my_app.service.HybridSearchService;
import io.bootify.my_app.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller REST per la pipeline RAG e la ricerca ibrida.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/rag/session}        – crea una nuova sessione (chiamare all'apertura pagina)</li>
 *   <li>{@code DELETE /api/rag/session/{id}} – elimina esplicitamente una sessione</li>
 *   <li>{@code POST /api/rag/ask}            – domanda RAG completa (retrieval + LLM)</li>
 *   <li>{@code POST /api/rag/search}         – hybrid search senza generazione LLM</li>
 *   <li>{@code GET  /api/rag/documents}      – lista file indicizzati (per autocomplete)</li>
 *   <li>{@code GET  /api/rag/health}         – verifica disponibilità servizi</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final HybridSearchService hybridSearchService;
    private final ConversationSessionService sessionService;

    public RagController(RagService ragService,
                         HybridSearchService hybridSearchService,
                         ConversationSessionService sessionService) {
        this.ragService = ragService;
        this.hybridSearchService = hybridSearchService;
        this.sessionService = sessionService;
    }

    /**
     * Crea una nuova sessione conversazionale vuota.
     * Il client deve chiamare questo endpoint all'apertura della pagina di ricerca
     * e conservare il {@code sessionId} restituito per le richieste successive.
     *
     * <p>Esempio:
     * <pre>
     * POST /api/rag/session
     * → { "sessionId": "550e8400-e29b-41d4-a716-446655440000" }
     * </pre>
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = sessionService.createSession();
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * Elimina esplicitamente una sessione (es. utente chiude la chat o fa logout).
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pipeline RAG completa: retrieval ibrido + generazione risposta LLM.
     *
     * <p>Esempio:
     * <pre>
     * POST /api/rag/ask
     * {
     *   "query": "Quali sono le sorelle protagoniste?",
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
            log.info("RAG ask: sessionId='{}' query='{}'",
                    request.getSessionId(), request.getQuery());
            RagAnswer answer = ragService.ask(request);
            log.info("RAG risposta generata: needsClarification={}, followUpQuestions={}",
                    answer.isNeedsClarification(), answer.getFollowUpQuestions().size());
            return ResponseEntity.ok(answer);
        } catch (Exception e) {
            log.error("Errore nella pipeline RAG", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Ricerca ibrida (BM25 + kNN con RRF) senza generazione LLM.
     * Utile per debug del retrieval o per UI che mostrano i chunk direttamente.
     *
     * <p>Esempio:
     * <pre>
     * POST /api/rag/search
     * {
     *   "query": "sorelle che crescono insieme",
     *   "topK": 10,
     *   "metadataFilter": {"fileName": "Piccole donne.pdf"}
     * }
     * </pre>
     */
    @PostMapping("/search")
    public ResponseEntity<List<SearchResult>> hybridSearch(@RequestBody RagRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            int topK = request.getTopK() != null ? request.getTopK() : 10;
            log.info("Hybrid search: query='{}', topK={}", request.getQuery(), topK);
            Map<String, String> filter = ragService.resolveFilter(request);
            List<SearchResult> results = hybridSearchService.search(
                    request.getQuery(), topK, filter.isEmpty() ? null : filter);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Errore nella ricerca ibrida", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lista tutti i fileName distinti presenti nell'indice semantico.
     *
     * <p>Utile per costruire una UI con autocomplete o dropdown per filtrare
     * la ricerca per documento. Il campo {@code fileName} della {@code RagRequest}
     * accetta anche nomi parziali/approssimativi: il server li risolve automaticamente.
     *
     * <pre>
     * GET /api/rag/documents
     * → ["ventimila-leghe.pdf", "Zanna Bianca (1).pdf", ...]
     * </pre>
     */
    @GetMapping("/documents")
    public ResponseEntity<List<String>> listDocuments() {
        return ResponseEntity.ok(hybridSearchService.listDocuments());
    }

    /**
     * Health check della pipeline RAG.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "RAG Pipeline"
        ));
    }
}
