package io.bootify.my_app.service;

import io.bootify.my_app.model.RagAnswer;
import io.bootify.my_app.model.RagAnswer.RagSource;
import io.bootify.my_app.model.RagRequest;
import io.bootify.my_app.model.SearchResult;
import io.bootify.my_app.service.embedding.EmbeddingProvider;
import io.bootify.my_app.service.llm.LlmProvider;
import io.bootify.my_app.service.llm.LlmProvider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestratore della pipeline RAG (Retrieval-Augmented Generation).
 *
 * <p>Flusso end-to-end:
 * <pre>
 *   [Query]
 *     │
 *     ▼
 *   [EmbeddingProvider] ──→ query vector
 *     │
 *     ▼
 *   [HybridSearchService] ──→ BM25 + kNN → RRF merge → top-K chunks
 *     │
 *     ▼
 *   [ContextBuilder] ──→ prompt con contesto formattato + citazioni
 *     │
 *     ▼
 *   [LlmProvider] ──→ risposta in linguaggio naturale
 *     │
 *     ▼
 *   [RagAnswer] ──→ risposta + fonti + metadati esecuzione
 * </pre>
 *
 * <p><b>Prompt engineering:</b>
 * Il system prompt instilla nel modello:
 * <ul>
 *   <li>Istruzione a rispondere SOLO dal contesto fornito</li>
 *   <li>Istruzione a dichiarare esplicitamente quando l'informazione manca</li>
 *   <li>Formato di risposta chiaro e conciso</li>
 * </ul>
 * Questo approccio riduce drasticamente le allucinazioni.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Sei un assistente esperto che risponde alle domande basandosi ESCLUSIVAMENTE \
            sul contesto documentale fornito di seguito.
            
            REGOLE TASSATIVE:
            1. Rispondi SOLO usando le informazioni presenti nel contesto.
            2. Se il contesto non contiene informazioni sufficienti, dichiaralo esplicitamente \
            con: "Le informazioni richieste non sono presenti nei documenti analizzati."
            3. NON inventare, NON aggiungere informazioni non presenti nel contesto.
            4. Cita la sezione o il documento di riferimento quando possibile.
            5. Rispondi in modo chiaro, strutturato e conciso.
            %s
            """;

    private static final String LANGUAGE_INSTRUCTION =
            "6. Rispondi nella lingua: %s.";

    private final HybridSearchService hybridSearchService;
    private final LlmProvider llmProvider;
    private final EmbeddingProvider embeddingProvider;

    @Value("${rag.context.max-tokens:3000}")
    private int maxContextTokens;

    @Value("${rag.context.top-k:5}")
    private int defaultTopK;

    public RagService(HybridSearchService hybridSearchService,
                      LlmProvider llmProvider,
                      EmbeddingProvider embeddingProvider) {
        this.hybridSearchService = hybridSearchService;
        this.llmProvider = llmProvider;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Esegue la pipeline RAG completa e restituisce una risposta arricchita con fonti.
     *
     * @param request parametri della query RAG
     * @return risposta generata dall'LLM con riferimento alle fonti
     */
    public RagAnswer ask(RagRequest request) {
        long start = System.currentTimeMillis();
        String query = request.getQuery();
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;

        log.info("RAG pipeline avviata: query='{}', topK={}", query, topK);

        // 1. Retrieval: hybrid search (BM25 + kNN)
        Map<String, String> filter = buildFilter(request);
        List<SearchResult> retrieved = hybridSearchService.search(query, topK, filter.isEmpty() ? null : filter);

        if (retrieved.isEmpty()) {
            log.warn("Nessun chunk trovato per la query: '{}'", query);
            return buildEmptyAnswer(query, start);
        }

        // 2. Context building: costruisce il prompt con i chunk recuperati
        String context = buildContext(retrieved);
        String userMessage = buildUserMessage(query, context);

        // 3. System prompt con eventuali istruzioni lingua
        String systemPrompt = buildSystemPrompt(request.getLanguage());

        // 4. Generazione risposta tramite LLM
        log.info("Chiamata LLM ({}): contesto ~{} parole, {} fonti",
                llmProvider.modelName(), context.split("\\s+").length, retrieved.size());

        String answer = llmProvider.complete(List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", userMessage)
        ));

        // 5. Costruisce risposta finale con metadati
        List<RagSource> sources = toSources(retrieved);
        long elapsed = System.currentTimeMillis() - start;

        log.info("RAG pipeline completata in {}ms, risposta: {} chars", elapsed, answer.length());

        return new RagAnswer(answer, sources, llmProvider.modelName(),
                embeddingProvider.modelName(), query, elapsed);
    }

    // ── Context builder ────────────────────────────────────────────────────────

    /**
     * Costruisce il blocco contesto da inserire nel prompt.
     * Ogni chunk è numerato e annotato con documento e sezione per le citazioni.
     * Il contesto viene troncato per non superare la token window del modello.
     */
    private String buildContext(List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        int totalWords = 0;

        for (int i = 0; i < chunks.size(); i++) {
            SearchResult chunk = chunks.get(i);
            String chunkWords = chunk.getContent();
            int words = chunkWords.split("\\s+").length;

            if (totalWords + words > maxContextTokens) {
                log.debug("Contesto troncato a {} chunk per rispettare token window", i);
                break;
            }

            sb.append("[FONTE ").append(i + 1).append("]");
            if (chunk.getFileName() != null) {
                sb.append(" Documento: ").append(chunk.getFileName());
            }
            if (chunk.getChapterTitle() != null && !chunk.getChapterTitle().isBlank()) {
                sb.append(" | Sezione: ").append(chunk.getChapterTitle());
            }
            sb.append("\n").append(chunk.getContent()).append("\n\n");
            totalWords += words;
        }

        return sb.toString().trim();
    }

    private String buildUserMessage(String query, String context) {
        return """
                CONTESTO DOCUMENTALE:
                %s
                
                ---
                DOMANDA: %s
                
                Rispondi basandoti esclusivamente sul contesto fornito sopra.
                """.formatted(context, query);
    }

    private String buildSystemPrompt(String language) {
        String langInstruction = (language != null && !language.isBlank())
                ? LANGUAGE_INSTRUCTION.formatted(language)
                : "";
        return SYSTEM_PROMPT_TEMPLATE.formatted(langInstruction);
    }

    private Map<String, String> buildFilter(RagRequest request) {
        Map<String, String> filter = new HashMap<>();
        if (request.getMetadataFilter() != null) {
            filter.putAll(request.getMetadataFilter());
        }
        if (request.getDocumentId() != null && !request.getDocumentId().isBlank()) {
            filter.put("documentId", request.getDocumentId());
        }
        return filter;
    }

    private List<RagSource> toSources(List<SearchResult> results) {
        List<RagSource> sources = new ArrayList<>();
        for (SearchResult r : results) {
            sources.add(new RagSource(
                    r.getDocumentId(),
                    r.getFileName(),
                    r.getChapterTitle(),
                    r.getChapterIndex(),
                    r.getChunkIndex(),
                    r.getContent(),
                    r.getScore() != null ? r.getScore() : 0f
            ));
        }
        return sources;
    }

    private RagAnswer buildEmptyAnswer(String query, long start) {
        String noContextMsg = "Le informazioni richieste non sono presenti nei documenti analizzati.";
        return new RagAnswer(noContextMsg, List.of(),
                llmProvider.modelName(), embeddingProvider.modelName(),
                query, System.currentTimeMillis() - start);
    }
}
