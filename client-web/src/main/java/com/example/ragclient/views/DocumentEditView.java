package com.example.ragclient.views;

import com.example.ragclient.dto.ParsedDocumentDetail;
import com.example.ragclient.service.RagApiService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editor delle sezioni di un documento parsato.
 * Permette di modificare titoli e testi prima di indicizzare su Elasticsearch.
 *
 * <p>Route: /edit-document/:id
 */
@Route(value = "edit-document/:id", layout = MainLayout.class)
@PageTitle("Modifica Documento | RAG Client")
@Slf4j
public class DocumentEditView extends VerticalLayout implements BeforeEnterObserver {

    private final RagApiService ragApiService;

    private String documentId;
    private String documentState;

    private final H2 titleLabel = new H2();
    private final Paragraph metaLabel = new Paragraph();
    private final VerticalLayout sectionsContainer = new VerticalLayout();
    private final Button saveButton;
    private final Button indexButton;
    private final ProgressBar progressBar;

    /** Lista dei componenti di editing per leggere i valori al salvataggio. */
    private final List<SectionEditor> sectionEditors = new ArrayList<>();

    public DocumentEditView(RagApiService ragApiService) {
        this.ragApiService = ragApiService;

        setSpacing(true);
        setPadding(true);
        setMaxWidth("1000px");
        getStyle().set("margin", "0 auto");

        // Header
        Button backButton = new Button("← Torna alla lista");
        backButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        backButton.addClickListener(e -> UI.getCurrent().navigate(DocumentListView.class));

        add(backButton);
        add(titleLabel);

        metaLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(metaLabel);

        // Progress bar (nascosta di default)
        progressBar = new ProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        add(progressBar);

        // Bottoni azione
        saveButton = new Button("💾 Salva modifiche");
        saveButton.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
        saveButton.addClickListener(e -> saveSections());

        indexButton = new Button("📦 Indicizza documento");
        indexButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        indexButton.addClickListener(e -> indexDocument());

        HorizontalLayout toolbar = new HorizontalLayout(saveButton, indexButton);
        toolbar.setSpacing(true);
        add(toolbar);

        // Container sezioni
        sectionsContainer.setSpacing(false);
        sectionsContainer.setPadding(false);
        add(sectionsContainer);

        // Duplica toolbar in fondo per comodità
        Button saveBottom = new Button("💾 Salva modifiche");
        saveBottom.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
        saveBottom.addClickListener(e -> saveSections());

        Button indexBottom = new Button("📦 Indicizza documento");
        indexBottom.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        indexBottom.addClickListener(e -> indexDocument());

        HorizontalLayout toolbarBottom = new HorizontalLayout(saveBottom, indexBottom);
        toolbarBottom.setSpacing(true);
        add(toolbarBottom);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        documentId = event.getRouteParameters().get("id").orElse(null);
        if (documentId == null) {
            event.forwardTo(DocumentListView.class);
            return;
        }
        loadDocument();
    }

    private void loadDocument() {
        try {
            ParsedDocumentDetail detail = ragApiService.getParsedDocument(documentId);
            if (detail == null) {
                Notification.show("Documento non trovato", 3000, Notification.Position.MIDDLE);
                UI.getCurrent().navigate(DocumentListView.class);
                return;
            }

            documentState = detail.getState();

            titleLabel.setText("✏️ " + detail.getFileName());
            metaLabel.setText(String.format(
                    "Stato: %s  |  Pagine: %s  |  Sezioni: %d",
                    stateLabel(documentState),
                    detail.getPageCount() != null ? detail.getPageCount() : "—",
                    detail.getSectionCount() != null ? detail.getSectionCount() : 0
            ));

            // Disabilita editing se già indicizzato
            boolean editable = !"INDEXED".equals(documentState);
            saveButton.setEnabled(editable);
            indexButton.setEnabled("TRANSCRIBED".equals(documentState));
            if (!editable) {
                Notification.show("ℹ️ Documento già indicizzato — sola lettura", 3000, Notification.Position.BOTTOM_START);
            }

            // Costruisce editor per ogni sezione
            sectionEditors.clear();
            sectionsContainer.removeAll();

            List<ParsedDocumentDetail.ChapterSectionDto> chapters = detail.getChapters();
            if (chapters == null || chapters.isEmpty()) {
                sectionsContainer.add(new Paragraph("Nessuna sezione estratta."));
                return;
            }

            for (ParsedDocumentDetail.ChapterSectionDto section : chapters) {
                SectionEditor editor = new SectionEditor(section, editable);
                sectionEditors.add(editor);
                sectionsContainer.add(editor);
            }

        } catch (Exception e) {
            log.error("Errore caricamento documento id={}", documentId, e);
            Notification.show("❌ Errore: " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void saveSections() {
        try {
            List<ParsedDocumentDetail.ChapterSectionDto> updated = sectionEditors.stream()
                    .map(SectionEditor::toDto)
                    .toList();

            ragApiService.updateSections(documentId, updated);
            Notification.show("✅ Modifiche salvate (" + updated.size() + " sezioni)", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            log.error("Errore salvataggio sezioni id={}", documentId, e);
            Notification.show("❌ " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void indexDocument() {
        try {
            progressBar.setVisible(true);
            indexButton.setEnabled(false);
            saveButton.setEnabled(false);

            // Prima salva le modifiche correnti
            List<ParsedDocumentDetail.ChapterSectionDto> updated = sectionEditors.stream()
                    .map(SectionEditor::toDto)
                    .toList();
            ragApiService.updateSections(documentId, updated);

            // Avvia indicizzazione asincrona (il backend risponde 202 subito)
            ragApiService.indexParsedDocument(documentId);

            Notification.show("⏳ Indicizzazione avviata — torna alla lista per vedere lo stato",
                    4000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

            UI.getCurrent().navigate(DocumentListView.class);

        } catch (Exception e) {
            progressBar.setVisible(false);
            indexButton.setEnabled(true);
            saveButton.setEnabled(true);
            log.error("Errore avvio indicizzazione id={}", documentId, e);
            Notification.show("❌ " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    // ── SectionEditor ─────────────────────────────────────────────────────────

    private static class SectionEditor extends VerticalLayout {

        private final int chapterIndex;
        private final TextField titleField;
        private final TextArea textArea;

        SectionEditor(ParsedDocumentDetail.ChapterSectionDto section, boolean editable) {
            this.chapterIndex = section.getChapterIndex();

            setSpacing(false);
            setPadding(true);
            getStyle()
                    .set("border", "1px solid var(--lumo-contrast-20pct)")
                    .set("border-radius", "var(--lumo-border-radius-m)")
                    .set("margin-bottom", "var(--lumo-space-m)");

            // Badge livello (ricostruito dal titolo se disponibile)
            H3 sectionHeader = new H3("#" + section.getChapterIndex() + " — " + section.getTitle());
            sectionHeader.getStyle().set("margin", "0 0 var(--lumo-space-s) 0");
            add(sectionHeader);

            titleField = new TextField("Titolo");
            titleField.setValue(section.getTitle() != null ? section.getTitle() : "");
            titleField.setWidthFull();
            titleField.setReadOnly(!editable);
            add(titleField);

            textArea = new TextArea("Testo");
            textArea.setValue(section.getText() != null ? section.getText() : "");
            textArea.setWidthFull();
            textArea.setMinHeight("120px");
            textArea.setMaxHeight("400px");
            textArea.setReadOnly(!editable);
            add(textArea);
        }

        ParsedDocumentDetail.ChapterSectionDto toDto() {
            ParsedDocumentDetail.ChapterSectionDto dto = new ParsedDocumentDetail.ChapterSectionDto();
            dto.setChapterIndex(chapterIndex);
            dto.setTitle(titleField.getValue());
            dto.setText(textArea.getValue());
            return dto;
        }
    }

    private static String stateLabel(String state) {
        return switch (state != null ? state : "") {
            case "TRANSCRIBED" -> "🟡 Trascritto";
            case "INDEXED"     -> "🟢 Indicizzato";
            case "ERROR"       -> "🔴 Errore";
            default            -> state != null ? state : "—";
        };
    }
}
