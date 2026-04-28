package io.bootify.my_app.service.llm;

import java.util.List;

/**
 * Interfaccia astratta per provider LLM.
 *
 * <p>Permette di switchare tra modelli locali (Ollama) e cloud (OpenAI, Anthropic)
 * senza modificare la pipeline RAG.
 */
public interface LlmProvider {

    /**
     * Genera una risposta in linguaggio naturale dato un contesto e una domanda.
     *
     * @param systemPrompt istruzioni di sistema per il modello
     * @param userMessage  messaggio dell'utente (domanda + contesto inclusi)
     * @return testo della risposta generata
     * @throws LlmException in caso di errori di comunicazione
     */
    String complete(String systemPrompt, String userMessage);

    /**
     * Genera una risposta con storico conversazionale.
     *
     * @param messages lista di messaggi in ordine cronologico
     * @return testo della risposta
     */
    String complete(List<ChatMessage> messages);

    /**
     * Nome identificativo del modello (es. "llama3", "gpt-4o-mini").
     */
    String modelName();

    /**
     * Rappresenta un messaggio nella conversazione.
     *
     * @param role    ruolo: "system", "user", "assistant"
     * @param content contenuto testuale del messaggio
     */
    record ChatMessage(String role, String content) {}
}
