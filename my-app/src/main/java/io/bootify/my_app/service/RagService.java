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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   [LlmProvider] ──→ risposta + domande di approfondimento
 *     │
 *     ▼
 *   [RagAnswer] ──→ risposta + fonti + followUpQuestions + metadati
 * </pre>
 *
 * <p><b>Chiarimento su score basso:</b>
 * Quando il punteggio di rilevanza dei chunk recuperati è sotto la soglia
 * {@code rag.followup.min-score}, il sistema NON risponde con conoscenza
 * generale ma genera esclusivamente domande di chiarimento (configurabili
 * con {@code rag.followup.questions-count}) per aiutare l'utente a
 * riformulare la domanda in modo più preciso.
 *
 * <p><b>Follow-up questions:</b>
 * Al termine di ogni risposta l'AI genera domande di approfondimento
 * che l'utente può inviare nella request successiva (con il campo
 * {@code conversationHistory}) per ottenere risposte più precise.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /**
     * Prompt diretto senza struttura rigida di output.
     * Compatibile con modelli di dimensioni ridotte.
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Sei un assistente che risponde ESCLUSIVAMENTE usando le informazioni \
            del CONTESTO DOCUMENTALE fornito.

            REGOLE:
            1. Usa SOLO ciò che è scritto nel contesto. Non inventare nulla.
            2. Cita le fonti con [FONTE N] quando possibile.
            3. Se il contesto non è pertinente scrivi: \
               "Non ho trovato informazioni pertinenti nel documento."
            4. Rispondi in modo chiaro e conciso.
            %s
            """;

    private static final String LANGUAGE_INSTRUCTION =
            "5. Rispondi nella lingua: %s.";

    /** Frasi che indicano che l'LLM non ha trovato informazioni nel contesto. */
    private static final List<String> NO_INFO_SIGNALS = List.of(
            "non ho trovato", "nessuna informazione", "non sono presenti",
            "non è presente", "non posso rispondere", "non ho informazioni",
            "il contesto non contiene", "non trovo", "assente nel contesto"
    );

    private final HybridSearchService hybridSearchService;
    private final LlmProvider llmProvider;
    private final EmbeddingProvider embeddingProvider;
    private final ConversationSessionService sessionService;

    @Value("${rag.context.max-tokens:3000}")
    private int maxContextTokens;

    @Value("${rag.context.top-k:5}")
    private int defaultTopK;

    public RagService(HybridSearchService hybridSearchService,
                      LlmProvider llmProvider,
                      EmbeddingProvider embeddingProvider,
                      ConversationSessionService sessionService) {
        this.hybridSearchService = hybridSearchService;
        this.llmProvider = llmProvider;
        this.embeddingProvider = embeddingProvider;
        this.sessionService = sessionService;
    }

    /**
     * Esegue la pipeline RAG completa e restituisce una risposta arricchita con fonti
     * e domande di approfondimento per conversazioni multi-turno.
     *
     * @param request parametri della query RAG (query, topK, sessionId, ...)
     * @return risposta generata dall'LLM con fonti e follow-up questions
     */
    public RagAnswer ask(RagRequest request) {
        long start = System.currentTimeMillis();
        String query = request.getQuery();
        String sessionId = request.getSessionId();
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;

        // Carica lo storico dalla sessione server-side
        List<Map<String, String>> history = sessionService.getHistory(sessionId);

        log.info("━━━ RAG PIPELINE START ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("▶ QUERY     : '{}'", query);
        log.info("▶ SESSION   : '{}'  |  topK: {}  |  storico: {} msg", sessionId, topK, history.size());
        if (!history.isEmpty()) {
            log.debug("▶ STORICO CONVERSAZIONALE ({} messaggi):", history.size());
            history.forEach(entry ->
                log.debug("   [{}] '{}'",
                        entry.getOrDefault("role", "?"),
                        truncate(entry.getOrDefault("content", ""), 120)));
        }

        // 1. Retrieval: hybrid search (BM25 + kNN)
        Map<String, String> filter = buildFilter(request);
        if (!filter.isEmpty()) {
            log.debug("▶ FILTRI METADATI: {}", filter);
        }
        List<SearchResult> retrieved = hybridSearchService.search(
                query, topK, filter.isEmpty() ? null : filter);

        float bestScore = retrieved.isEmpty() ? 0f
                : (retrieved.get(0).getScore() != null ? retrieved.get(0).getScore() : 0f);

        log.info("▶ RETRIEVAL : {} chunk trovati  |  best score: {}", retrieved.size(), bestScore);
        if (!retrieved.isEmpty()) {
            log.debug("▶ CHUNK RECUPERATI:");
            for (int i = 0; i < retrieved.size(); i++) {
                SearchResult r = retrieved.get(i);
                log.debug("   [{}] score={} | doc='{}' | sez='{}' | testo: '{}'",
                        i + 1,
                        r.getScore() != null ? String.format("%.4f", r.getScore()) : "n/a",
                        r.getFileName(),
                        r.getChapterTitle(),
                        truncate(r.getContent(), 100));
            }
        }
        log.info("▶ DECISIONE : delegata all'LLM (valuta autonomamente se il contesto è sufficiente)");

        // 2. Context building: passa sempre i chunk all'LLM, anche se score basso
        String context = buildContext(retrieved);
        String userMessage = buildUserMessage(query, context);
        String systemPrompt = buildSystemPrompt(request.getLanguage());

        log.debug("▶ SYSTEM PROMPT:\n{}", systemPrompt);
        log.debug("▶ USER MESSAGE (con contesto):\n{}", userMessage);

        // 3. Costruisce la lista di messaggi con lo storico della sessione
        List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);

        // 4. Chiamata LLM — decide autonomamente se rispondere o chiedere chiarimenti
        log.info("▶ LLM CALL  : modello='{}' | contesto~{} parole | {} chunk | {} msg totali",
                llmProvider.modelName(),
                context.isBlank() ? 0 : context.split("\\s+").length,
                retrieved.size(),
                messages.size());

        String rawResponse = llmProvider.complete(messages);

        log.debug("▶ LLM RISPOSTA GREZZA ({} chars):\n{}", rawResponse.length(), rawResponse);

        // 5. Parsing robusto: gestisce risposte malformate dei modelli piccoli
        ParsedResponse parsed = parseResponse(rawResponse);

        // 6. Se il contesto non era sufficiente, riprova con query espansa (una sola volta)
        if (parsed.needsClarification) {
            String expandedQuery = expandQuery(query);
            if (!expandedQuery.equalsIgnoreCase(query.trim())) {
                log.info("▶ RETRY     : contesto insufficiente → query espansa: '{}'", expandedQuery);
                List<SearchResult> retrieved2 = hybridSearchService.search(
                        expandedQuery, topK, filter.isEmpty() ? null : filter);
                log.info("▶ RETRY     : {} chunk trovati con query espansa", retrieved2.size());
                if (!retrieved2.isEmpty()) {
                    String context2 = buildContext(retrieved2);
                    String userMessage2 = buildUserMessage(query, context2);
                    List<ChatMessage> messages2 = buildMessages(systemPrompt, userMessage2, history);
                    String rawResponse2 = llmProvider.complete(messages2);
                    log.debug("▶ RETRY LLM risposta ({} chars):\n{}", rawResponse2.length(), rawResponse2);
                    ParsedResponse parsed2 = parseResponse(rawResponse2);
                    if (!parsed2.needsClarification) {
                        parsed = parsed2;
                        retrieved = retrieved2;
                        log.info("▶ RETRY     : risposta ottenuta con query espansa");
                    } else {
                        log.info("▶ RETRY     : ancora contesto insufficiente dopo espansione");
                    }
                }
            }
        }

        boolean needsClarification = parsed.needsClarification;
        List<RagSource> sources = needsClarification ? List.of() : toSources(retrieved);
        long elapsed = System.currentTimeMillis() - start;

        log.info("▶ ESITO     : {} | {} chars | {} fonti",
                needsClarification ? "contesto insufficiente" : "risposta generata",
                parsed.mainAnswer != null ? parsed.mainAnswer.length() : 0,
                sources.size());
        log.info("━━━ RAG PIPELINE END  [{}ms] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", elapsed);

        // 7. Salva il turno nella sessione
        sessionService.addTurn(sessionId, query,
                parsed.mainAnswer != null ? parsed.mainAnswer : "");

        RagAnswer answer = new RagAnswer(
                parsed.mainAnswer, sources, llmProvider.modelName(),
                embeddingProvider.modelName(), query, elapsed);
        answer.setFollowUpQuestions(List.of());
        answer.setNeedsClarification(needsClarification);
        answer.setSessionId(sessionId);
        return answer;
    }

    /** Tronca una stringa per i log, aggiungendo "…" se necessario. */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    // ── Context builder ────────────────────────────────────────────────────────

    private String buildContext(List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        int totalWords = 0;

        for (int i = 0; i < chunks.size(); i++) {
            SearchResult chunk = chunks.get(i);
            String chunkText = chunk.getContent();
            int words = chunkText.split("\\s+").length;

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
            sb.append("\n").append(chunkText).append("\n\n");
            totalWords += words;
        }

        return sb.toString().trim();
    }

    private String buildUserMessage(String query, String context) {
        String contextBlock = context.isBlank()
                ? "(nessun documento trovato nella base documentale)"
                : context;
        return """
                CONTESTO DOCUMENTALE:
                %s

                ───────────────────────────────────────
                DOMANDA: %s
                """.formatted(contextBlock, query);
    }

    private String buildSystemPrompt(String language) {
        String langInstruction = (language != null && !language.isBlank())
                ? LANGUAGE_INSTRUCTION.formatted(language)
                : "";
        return SYSTEM_PROMPT_TEMPLATE.formatted(langInstruction);
    }

    // ── Conversation history ───────────────────────────────────────────────────

    /**
     * Costruisce la lista di ChatMessage includendo:
     * 1. Il system prompt
     * 2. Lo storico conversazionale precedente (se presente)
     * 3. Il messaggio utente corrente con contesto documentale
     */
    private List<ChatMessage> buildMessages(String systemPrompt, String userMessage,
                                             List<Map<String, String>> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));

        for (Map<String, String> entry : history) {
            String role    = entry.getOrDefault("role", "user");
            String content = entry.getOrDefault("content", "");
            if (!content.isBlank() && (role.equals("user") || role.equals("assistant"))) {
                messages.add(new ChatMessage(role, content));
                log.debug("   [storia] role={} content='{}'", role, truncate(content, 100));
            }
        }

        messages.add(new ChatMessage("user", userMessage));
        log.debug("▶ MESSAGGI INVIATI ALL'LLM: {} totali (1 system + {} storia + 1 user)",
                messages.size(), messages.size() - 2);
        return messages;
    }

    // ── Response parsing ───────────────────────────────────────────────────────

    /**
     * Parsing robusto della risposta LLM.
     *
     * <p>Gestisce:
     * <ul>
     *   <li>Risposta null / blank / testo "null" → chiarimento</li>
     *   <li>Presenza di "Approfondimenti:" → separa risposta da follow-up</li>
     *   <li>Segnali di mancanza info → {@code needsClarification=true}</li>
     *   <li>Risposta plain text senza struttura → restituita com'è</li>
     * </ul>
     */
    private ParsedResponse parseResponse(String rawResponse) {
        // 1. Risposta nulla o testo letterale "null" (modelli piccoli)
        if (rawResponse == null
                || rawResponse.isBlank()
                || rawResponse.strip().equalsIgnoreCase("null")) {
            log.warn("▶ PARSING   : risposta LLM nulla o 'null' literal");
            return new ParsedResponse(null, true);
        }

        String mainAnswer = rawResponse.trim();

        // 2. Rileva segnali di mancanza informazioni nel testo
        String lowerAnswer = mainAnswer.toLowerCase();
        boolean noInfo = NO_INFO_SIGNALS.stream().anyMatch(lowerAnswer::contains);
        if (noInfo) {
            log.info("▶ PARSING   : rilevato segnale 'nessuna informazione' nella risposta");
            return new ParsedResponse(mainAnswer, true);
        }

        log.debug("▶ PARSING   : risposta valida, {} chars", mainAnswer.length());
        return new ParsedResponse(mainAnswer, false);
    }

    /**
     * Espande la query estraendo solo le parole chiave significative.
     * Usato per il retry automatico quando il primo search non trova contesto sufficiente.
     */
    private String expandQuery(String originalQuery) {
        Set<String> stopWords = Set.of(
                "cosa", "dove", "come", "chi", "quando", "quale", "quali",
                "perché", "perche", "quante", "quanto", "quanti", "che",
                "sono", "hanno", "viene", "avere", "essere", "fare", "può",
                "what", "where", "how", "who", "when", "which", "why",
                "tell", "about", "the", "and", "for", "with"
        );
        String expanded = Arrays.stream(originalQuery.split("\\s+"))
                .map(w -> w.replaceAll("[?!.,;:\"'()]", "").toLowerCase())
                .filter(w -> w.length() > 3)
                .filter(w -> !stopWords.contains(w))
                .distinct()
                .collect(Collectors.joining(" "));
        return expanded.isBlank() ? originalQuery : expanded;
    }

    private record ParsedResponse(String mainAnswer, boolean needsClarification) {}

    // ── Utilities ──────────────────────────────────────────────────────────────

    private Map<String, String> buildFilter(RagRequest request) {
        Map<String, String> filter = new HashMap<>();
        if (request.getMetadataFilter() != null) {
            filter.putAll(request.getMetadataFilter());
        }
        if (request.getDocumentId() != null && !request.getDocumentId().isBlank()) {
            filter.put("documentId", request.getDocumentId());
        }
        if (request.getFileName() != null && !request.getFileName().isBlank()) {
            // Risolve il nome parziale/approssimativo al fileName esatto nell'indice
            String resolved = hybridSearchService.resolveFileName(request.getFileName())
                    .orElse(request.getFileName());
            if (!resolved.equals(request.getFileName())) {
                log.info("fileName '{}' risolto in '{}'", request.getFileName(), resolved);
            }
            filter.put("fileName", resolved);
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
}


