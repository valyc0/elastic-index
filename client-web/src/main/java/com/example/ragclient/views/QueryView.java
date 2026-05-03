package com.example.ragclient.views;

import com.example.ragclient.dto.QueryResponse;
import com.example.ragclient.service.RagApiService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Chat RAG | RAG Client")
@Slf4j
public class QueryView extends VerticalLayout {

    private final RagApiService ragApiService;
    private TextField messageField;
    private Button sendButton;
    private final VerticalLayout chatContainer;
    private final Scroller chatScroller;
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private String sessionId;

    // Inner class per memorizzare i messaggi
    private record ChatMessage(String role, String content, LocalDateTime timestamp, QueryResponse response) {}

    public QueryView(RagApiService ragApiService) {
        this.ragApiService = ragApiService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // Header
        HorizontalLayout header = createHeader();
        add(header);

        // Chat container con scroll
        chatContainer = new VerticalLayout();
        chatContainer.setWidthFull();
        chatContainer.setPadding(true);
        chatContainer.setSpacing(true);
        chatContainer.getStyle()
            .set("background", "var(--lumo-contrast-5pct)");

        chatScroller = new Scroller(chatContainer);
        chatScroller.setSizeFull();
        chatScroller.setScrollDirection(Scroller.ScrollDirection.VERTICAL);
        add(chatScroller);
        setFlexGrow(1, chatScroller);

        initializeSession();

        // Messaggio di benvenuto
        addWelcomeMessage();

        // Input area fissata in basso
        HorizontalLayout inputArea = createInputArea();
        add(inputArea);

        // Focus sul campo messaggio
        messageField.focus();
    }

    private HorizontalLayout createHeader() {
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(true);
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.getStyle()
            .set("background", "var(--lumo-base-color)")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        H2 title = new H2("💬 RAG Chat");
        title.getStyle().set("margin", "0");

        Button clearButton = new Button("🗑️ Clear Chat", e -> clearChat());
        clearButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        header.add(title, clearButton);
        return header;
    }

    private HorizontalLayout createInputArea() {
        HorizontalLayout inputArea = new HorizontalLayout();
        inputArea.setWidthFull();
        inputArea.setPadding(true);
        inputArea.setSpacing(true);
        inputArea.setAlignItems(Alignment.CENTER);
        inputArea.getStyle()
            .set("background", "var(--lumo-base-color)")
            .set("border-top", "1px solid var(--lumo-contrast-10pct)");

        messageField = new TextField();
        messageField.setWidthFull();
        messageField.setPlaceholder("Scrivi la tua domanda sui documenti...");
        messageField.setClearButtonVisible(true);
        messageField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, e -> sendMessage());

        sendButton = new Button(new Icon(VaadinIcon.PAPERPLANE), e -> sendMessage());
        sendButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        sendButton.getElement().setAttribute("aria-label", "Invia messaggio");

        inputArea.add(messageField, sendButton);
        inputArea.setFlexGrow(1, messageField);

        return inputArea;
    }

    private void addWelcomeMessage() {
        Div welcomeMessage = createAssistantBubble(
            "👋 Ciao! Sono il tuo assistente RAG.\n\n" +
            "Puoi farmi domande sui documenti caricati nel sistema. " +
            "Cercherò le informazioni rilevanti e ti fornirò una risposta basata sui contenuti indicizzati.\n\n" +
            "💡 Suggerimenti:\n" +
            "• Fai domande specifiche per ottenere risposte migliori\n" +
            "• Puoi chiedere riassunti o dettagli specifici\n" +
            "• Le fonti delle risposte vengono mostrate sotto ogni risposta",
            null
        );
        chatContainer.add(welcomeMessage);
    }

    private void sendMessage() {
        String question = messageField.getValue();
        
        if (question == null || question.trim().isEmpty()) {
            return;
        }

        // Pulisci il campo
        messageField.clear();

        // Aggiungi messaggio utente
        Div userBubble = createUserBubble(question.trim());
        chatContainer.add(userBubble);
        
        // Salva nella cronologia
        chatHistory.add(new ChatMessage("user", question.trim(), LocalDateTime.now(), null));

        // Mostra indicatore di caricamento
        Div loadingBubble = createLoadingBubble();
        chatContainer.add(loadingBubble);
        scrollToBottom();

        // Disabilita input durante elaborazione
        sendButton.setEnabled(false);
        messageField.setEnabled(false);

        // Capture UI for async access
        var ui = getUI().orElse(null);
        if (ui == null) {
            return;
        }

        // Esegui query in background
        new Thread(() -> {
            try {
                log.info("🔍 Invio query al backend: {}", question.trim());
                QueryResponse response = ragApiService.query(question.trim(), sessionId);
                log.info("✅ Risposta ricevuta: {} caratteri", response != null && response.getAnswer() != null ? response.getAnswer().length() : 0);
                
                ui.access(() -> {
                    try {
                        // Rimuovi indicatore caricamento
                        chatContainer.remove(loadingBubble);
                        log.debug("✅ Loading bubble rimosso");
                        
                        // Verifica risposta
                        if (response == null || response.getAnswer() == null || response.getAnswer().trim().isEmpty()) {
                            log.warn("⚠️ Risposta vuota o null dal backend");
                            Div errorBubble = createAssistantBubble(
                                "⚠️ Il sistema non ha generato una risposta. Riprova.", null);
                            chatContainer.add(errorBubble);
                        } else {
                            if (response.getSessionId() != null && !response.getSessionId().isBlank()) {
                                sessionId = response.getSessionId();
                            }

                            // Aggiungi risposta
                            log.info("📝 Creazione bubble con risposta");
                            Div assistantBubble = createAssistantBubble(response.getAnswer(), response);
                            chatContainer.add(assistantBubble);
                            log.info("✅ Bubble aggiunto al container");
                            
                            // Salva nella cronologia
                            chatHistory.add(new ChatMessage("assistant", response.getAnswer(), LocalDateTime.now(), response));
                        }
                        
                        // Riabilita input
                        sendButton.setEnabled(true);
                        messageField.setEnabled(true);
                        messageField.focus();
                        
                        scrollToBottom();
                        ui.push(); // Forza l'aggiornamento UI
                    } catch (Exception uiEx) {
                        log.error("❌ Errore durante l'aggiornamento UI", uiEx);
                    }
                });
                
            } catch (Exception e) {
                log.error("Errore durante la query", e);
                ui.access(() -> {
                    chatContainer.remove(loadingBubble);
                    
                    Div errorBubble = createAssistantBubble(
                        "❌ Si è verificato un errore: " + e.getMessage(), null);
                    chatContainer.add(errorBubble);
                    
                    sendButton.setEnabled(true);
                    messageField.setEnabled(true);
                    messageField.focus();
                    
                    scrollToBottom();
                });
            }
        }).start();
    }

    private Div createUserBubble(String message) {
        Div bubble = new Div();
        bubble.getStyle()
            .set("background", "var(--lumo-primary-color)")
            .set("color", "var(--lumo-primary-contrast-color)")
            .set("padding", "var(--lumo-space-m)")
            .set("border-radius", "var(--lumo-border-radius-l)")
            .set("border-bottom-right-radius", "4px")
            .set("max-width", "80%")
            .set("margin-left", "auto")
            .set("white-space", "pre-wrap")
            .set("word-break", "break-word");
        
        bubble.setText(message);
        
        // Container per allineamento a destra
        Div container = new Div(bubble);
        container.setWidthFull();
        container.getStyle()
            .set("display", "flex")
            .set("justify-content", "flex-end")
            .set("margin-bottom", "var(--lumo-space-s)");
        
        return container;
    }

    private Div createAssistantBubble(String message, QueryResponse response) {
        log.debug("🎨 Creazione assistant bubble - Messaggio: {} chars, Response: {}", 
                  message != null ? message.length() : 0, 
                  response != null ? "presente" : "null");
        
        VerticalLayout bubbleContent = new VerticalLayout();
        bubbleContent.setPadding(false);
        bubbleContent.setSpacing(true);
        
        // Testo della risposta
        Div messageText = new Div();
        messageText.setText(message);
        messageText.getStyle()
            .set("white-space", "pre-wrap")
            .set("word-break", "break-word")
            .set("line-height", "1.5");
        bubbleContent.add(messageText);
        
        // Se ci sono fonti, mostrale in modo compatto
        if (response != null && response.getSources() != null && !response.getSources().isEmpty()) {
            // Statistiche
            String statsText = "📊 " + response.getSources().size() + " fonti usate";
            if (response.getProcessingTimeMs() != null) {
                statsText += " • " + response.getProcessingTimeMs() + " ms";
            }

            Span stats = new Span(statsText);
            stats.getStyle()
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)");
            bubbleContent.add(stats);
            
            // Fonti collapsibili
            Details sourcesDetails = new Details("📚 Mostra fonti (" + response.getSources().size() + ")");
            sourcesDetails.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin-top", "var(--lumo-space-s)");
            
            VerticalLayout sourcesContent = new VerticalLayout();
            sourcesContent.setPadding(false);
            sourcesContent.setSpacing(true);
            
            int num = 1;
            for (QueryResponse.Source source : response.getSources()) {
                Div sourceCard = createCompactSourceCard(num++, source);
                sourcesContent.add(sourceCard);
            }
            
            sourcesDetails.add(sourcesContent);
            bubbleContent.add(sourcesDetails);
        }

        if (response != null && response.getFollowUpQuestions() != null && !response.getFollowUpQuestions().isEmpty()) {
            Details followUps = new Details(
                response.isNeedsClarification() ? "❓ Domande di chiarimento" : "➡️ Domande suggerite"
            );
            VerticalLayout followUpContent = new VerticalLayout();
            followUpContent.setPadding(false);
            followUpContent.setSpacing(false);

            for (String followUp : response.getFollowUpQuestions()) {
                Paragraph question = new Paragraph(followUp);
                question.getStyle().set("margin", "0");
                followUpContent.add(question);
            }

            followUps.add(followUpContent);
            bubbleContent.add(followUps);
        }
        
        Div bubble = new Div(bubbleContent);
        bubble.getStyle()
            .set("background", "var(--lumo-base-color)")
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("padding", "var(--lumo-space-m)")
            .set("border-radius", "var(--lumo-border-radius-l)")
            .set("border-bottom-left-radius", "4px")
            .set("max-width", "85%");
        
        // Emoji assistente
        Span avatar = new Span("🤖");
        avatar.getStyle()
            .set("font-size", "var(--lumo-font-size-xl)")
            .set("margin-right", "var(--lumo-space-s)");
        
        HorizontalLayout row = new HorizontalLayout(avatar, bubble);
        row.setAlignItems(Alignment.START);
        row.setSpacing(false);
        
        Div container = new Div(row);
        container.setWidthFull();
        container.getStyle().set("margin-bottom", "var(--lumo-space-s)");
        
        return container;
    }

    private Div createCompactSourceCard(int num, QueryResponse.Source source) {
        Div card = new Div();
        card.getStyle()
            .set("background", "var(--lumo-contrast-5pct)")
            .set("border-radius", "var(--lumo-border-radius-m)")
            .set("padding", "var(--lumo-space-s)")
            .set("font-size", "var(--lumo-font-size-s)");
        
        // Header con filename e score
        String fileName = source.getFileName() != null ? source.getFileName() : "documento";
        float relevanceScore = source.getRelevanceScore() != null ? source.getRelevanceScore() : 0F;
        String chapterTitle = source.getChapterTitle();

        Span header = new Span(String.format("#%d 📄 %s (⭐ %.2f)", 
            num, fileName, relevanceScore));
        header.getStyle()
            .set("font-weight", "500")
            .set("display", "block")
            .set("margin-bottom", "var(--lumo-space-xs)");

        if (chapterTitle != null && !chapterTitle.isBlank()) {
            Span chapter = new Span("§ " + chapterTitle);
            chapter.getStyle()
                .set("display", "block")
                .set("margin-bottom", "var(--lumo-space-xs)")
                .set("color", "var(--lumo-secondary-text-color)");
            card.add(header, chapter);
        } else {
            card.add(header);
        }
        
        // Testo troncato
        String text = source.getContent() != null ? source.getContent() : "";
        if (text.length() > 300) {
            text = text.substring(0, 300) + "...";
        }
        Span textSpan = new Span(text);
        textSpan.getStyle()
            .set("color", "var(--lumo-secondary-text-color)")
            .set("display", "block")
            .set("white-space", "pre-wrap");
        
        card.add(textSpan);
        return card;
    }

    private Div createLoadingBubble() {
        Div bubble = new Div();
        bubble.getStyle()
            .set("background", "var(--lumo-base-color)")
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("padding", "var(--lumo-space-m)")
            .set("border-radius", "var(--lumo-border-radius-l)")
            .set("border-bottom-left-radius", "4px")
            .set("max-width", "85%");
        
        Span loadingText = new Span("⏳ Sto cercando nei documenti...");
        loadingText.getStyle()
            .set("color", "var(--lumo-secondary-text-color)")
            .set("font-style", "italic");
        bubble.add(loadingText);
        
        Span avatar = new Span("🤖");
        avatar.getStyle()
            .set("font-size", "var(--lumo-font-size-xl)")
            .set("margin-right", "var(--lumo-space-s)");
        
        HorizontalLayout row = new HorizontalLayout(avatar, bubble);
        row.setAlignItems(Alignment.START);
        row.setSpacing(false);
        
        Div container = new Div(row);
        container.setWidthFull();
        container.getStyle().set("margin-bottom", "var(--lumo-space-s)");
        
        return container;
    }

    private void clearChat() {
        chatContainer.removeAll();
        chatHistory.clear();
        resetSession();
        addWelcomeMessage();
    }

    private void initializeSession() {
        try {
            sessionId = ragApiService.createSession();
            log.info("🧠 Sessione RAG inizializzata: {}", sessionId);
        } catch (Exception e) {
            log.warn("Impossibile creare la sessione RAG", e);
            sessionId = null;
        }
    }

    private void resetSession() {
        try {
            ragApiService.deleteSession(sessionId);
        } catch (Exception e) {
            log.debug("Errore nella chiusura della sessione precedente", e);
        }
        initializeSession();
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        try {
            ragApiService.deleteSession(sessionId);
        } catch (Exception e) {
            log.debug("Errore durante la cancellazione sessione", e);
        }
        super.onDetach(detachEvent);
    }

    private void scrollToBottom() {
        chatScroller.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }
}
