package io.bootify.my_app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestisce le sessioni conversazionali server-side.
 *
 * <p>Ogni sessione è identificata da un UUID e mantiene lo storico
 * dei messaggi (coppie user/assistant) in memoria con una sliding window
 * configurabile. Le sessioni inattive vengono rimosse automaticamente.
 *
 * <p>Flusso client:
 * <pre>
 *   1. Apertura pagina  →  POST /api/rag/session          → sessionId
 *   2. Prima domanda    →  POST /api/rag/ask  {sessionId, query}  → risposta
 *   3. Domanda success. →  POST /api/rag/ask  {sessionId, query}  → risposta con storico
 *   4. Nuova ricerca    →  POST /api/rag/session (nuovo ID) oppure DELETE /api/rag/session/{id}
 * </pre>
 */
@Service
public class ConversationSessionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionService.class);

    @Value("${rag.conversation.max-turns:10}")
    private int maxTurns;

    @Value("${rag.conversation.ttl-minutes:60}")
    private int ttlMinutes;

    private record Session(List<Map<String, String>> history, Instant lastAccess) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Crea una nuova sessione vuota e restituisce il suo ID.
     */
    public String createSession() {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(new ArrayList<>(), Instant.now()));
        log.info("▶ SESSION   : creata sessione '{}'  |  sessioni attive: {}", id, sessions.size());
        return id;
    }

    /**
     * Restituisce lo storico della sessione (già troncato a max-turns).
     * Se il sessionId è null o non esiste, restituisce lista vuota.
     */
    public List<Map<String, String>> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Session session = sessions.get(sessionId);
        if (session == null) {
            log.warn("▶ SESSION   : sessionId '{}' non trovata", sessionId);
            return List.of();
        }
        return List.copyOf(session.history());
    }

    /**
     * Aggiunge un turno (domanda utente + risposta AI) allo storico,
     * applicando la sliding window e aggiornando il timestamp di accesso.
     */
    public void addTurn(String sessionId, String userQuery, String assistantAnswer) {
        if (sessionId == null || sessionId.isBlank()) return;
        sessions.compute(sessionId, (id, existing) -> {
            List<Map<String, String>> history = existing != null
                    ? new ArrayList<>(existing.history())
                    : new ArrayList<>();

            history.add(Map.of("role", "user", "content", userQuery));
            if (assistantAnswer != null && !assistantAnswer.isBlank()) {
                history.add(Map.of("role", "assistant", "content", assistantAnswer));
            }

            // Sliding window: mantieni solo gli ultimi maxTurns turni (ogni turno = 2 entry)
            int maxEntries = maxTurns * 2;
            if (history.size() > maxEntries) {
                int dropped = history.size() - maxEntries;
                history = history.subList(dropped, history.size());
                log.debug("▶ SESSION   : '{}' storico troncato, rimossi {} msg vecchi", id, dropped);
            }

            log.debug("▶ SESSION   : '{}' storico aggiornato → {} messaggi", id, history.size());
            return new Session(history, Instant.now());
        });
    }

    /**
     * Elimina esplicitamente una sessione (es. quando l'utente chiude la chat).
     */
    public boolean deleteSession(String sessionId) {
        boolean removed = sessions.remove(sessionId) != null;
        if (removed) log.info("▶ SESSION   : eliminata sessione '{}'", sessionId);
        return removed;
    }

    /**
     * Pulizia automatica ogni 15 minuti delle sessioni scadute.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void evictExpiredSessions() {
        Instant cutoff = Instant.now().minusSeconds(ttlMinutes * 60L);
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().lastAccess().isBefore(cutoff));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("▶ SESSION   : pulizia automatica, rimosse {} sessioni scadute (>{} min)",
                    removed, ttlMinutes);
        }
    }
}
