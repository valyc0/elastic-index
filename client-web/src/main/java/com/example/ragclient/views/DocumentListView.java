package com.example.ragclient.views;

import com.example.ragclient.dto.ParsedDocumentSummary;
import com.example.ragclient.service.RagApiService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Route(value = "documents", layout = MainLayout.class)
@PageTitle("Documents | RAG Client")
@Slf4j
public class DocumentListView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final int POLL_INTERVAL_SECONDS = 4;

    private final RagApiService ragApiService;
    private final Grid<ParsedDocumentSummary> grid;
    private final Paragraph statsLabel;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pollTask;

    public DocumentListView(RagApiService ragApiService) {
        this.ragApiService = ragApiService;

        setSpacing(true);
        setPadding(true);
        setMaxWidth("1400px");
        getStyle().set("margin", "0 auto");

        H2 title = new H2("📚 Documenti");

        Button refreshButton = new Button("🔄 Aggiorna", VaadinIcon.REFRESH.create(), e -> loadDocuments());
        refreshButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout headerLayout = new HorizontalLayout(title, refreshButton);
        headerLayout.setWidthFull();
        headerLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);
        headerLayout.setAlignItems(Alignment.CENTER);
        add(headerLayout);

        statsLabel = new Paragraph();
        statsLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(statsLabel);

        grid = new Grid<>(ParsedDocumentSummary.class, false);
        grid.setHeight("600px");

        grid.addComponentColumn(doc -> {
            Span badge = new Span(stateLabel(doc.getState()));
            badge.getStyle()
                    .set("padding", "2px 10px")
                    .set("border-radius", "12px")
                    .set("font-size", "var(--lumo-font-size-s)")
                    .set("font-weight", "bold")
                    .set("color", "white")
                    .set("background-color", stateColor(doc.getState()));
            return badge;
        }).setHeader("Stato").setWidth("150px").setFlexGrow(0);

        grid.addColumn(ParsedDocumentSummary::getFileName)
                .setHeader("📄 File")
                .setFlexGrow(3)
                .setSortable(true);

        grid.addColumn(doc -> doc.getSectionCount() != null ? doc.getSectionCount() : "-")
                .setHeader("Sezioni").setWidth("90px").setFlexGrow(0);

        grid.addColumn(doc -> doc.getChunks() != null ? doc.getChunks() : "-")
                .setHeader("Chunk").setWidth("80px").setFlexGrow(0);

        grid.addColumn(doc -> doc.getCreatedAt() != null ? DATE_FMT.format(doc.getCreatedAt()) : "-")
                .setHeader("Caricato").setWidth("140px").setFlexGrow(0).setSortable(true);

        grid.addComponentColumn(doc -> {
            HorizontalLayout actions = new HorizontalLayout();
            actions.setSpacing(true);

            if (!"ERROR".equals(doc.getState()) && !"INDEXING".equals(doc.getState()) && !"PROCESSING".equals(doc.getState())) {
                Button editBtn = new Button("✏️ Modifica", VaadinIcon.EDIT.create());
                editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                editBtn.addClickListener(e ->
                        UI.getCurrent().navigate(DocumentEditView.class,
                                new RouteParameters("id", doc.getId())));
                actions.add(editBtn);
            }

            if ("TRANSCRIBED".equals(doc.getState())) {
                Button indexBtn = new Button("📦 Indicizza", VaadinIcon.UPLOAD.create());
                indexBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
                indexBtn.addClickListener(e -> quickIndex(doc));
                actions.add(indexBtn);
            }

            Button deleteBtn = new Button(VaadinIcon.TRASH.create());
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            deleteBtn.addClickListener(e -> confirmDelete(doc));
            actions.add(deleteBtn);

            return actions;
        }).setHeader("Azioni").setWidth("300px").setFlexGrow(0);

        add(grid);
        loadDocuments();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        loadDocuments();
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        // Avvia polling solo se ci sono documenti in INDEXING
        UI ui = event.getUI();
        pollTask = scheduler.scheduleAtFixedRate(() -> {
            ui.access(() -> {
                try {
                    List<ParsedDocumentSummary> docs = ragApiService.getParsedDocuments();
                    boolean anyIndexing = docs != null && docs.stream()
                            .anyMatch(d -> "INDEXING".equals(d.getState()) || "PROCESSING".equals(d.getState()));
                    if (anyIndexing) {
                        grid.setItems(docs);
                        updateStats(docs);
                    }
                } catch (Exception ignored) { /* best effort */ }
            });
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void onDetach(DetachEvent event) {
        super.onDetach(event);
        if (pollTask != null) pollTask.cancel(true);
        scheduler.shutdownNow();
    }

    private void loadDocuments() {
        try {
            List<ParsedDocumentSummary> docs = ragApiService.getParsedDocuments();
            grid.setItems(docs != null ? docs : List.of());
            updateStats(docs);
        } catch (Exception e) {
            log.error("Errore caricamento documenti", e);
            Notification.show("❌ Errore: " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void updateStats(List<ParsedDocumentSummary> docs) {
        long processing  = docs != null ? docs.stream().filter(d -> "PROCESSING".equals(d.getState())).count() : 0;
        long transcribed = docs != null ? docs.stream().filter(d -> "TRANSCRIBED".equals(d.getState())).count() : 0;
        long indexing    = docs != null ? docs.stream().filter(d -> "INDEXING".equals(d.getState())).count() : 0;
        long indexed     = docs != null ? docs.stream().filter(d -> "INDEXED".equals(d.getState())).count() : 0;
        long errors      = docs != null ? docs.stream().filter(d -> "ERROR".equals(d.getState())).count() : 0;
        String stats = String.format(
                "📊 Totale: %d  |  🟡 Trascritti: %d  |  🟢 Indicizzati: %d  |  🔴 Errori: %d",
                docs != null ? docs.size() : 0, transcribed, indexed, errors);
        if (processing > 0) stats += "  |  ⏳ In trascrizione: " + processing;
        if (indexing > 0)   stats += "  |  ⏳ In indicizzazione: " + indexing;
        statsLabel.setText(stats);
    }

    private void quickIndex(ParsedDocumentSummary doc) {
        try {
            ragApiService.indexParsedDocument(doc.getId());
            Notification.show("✅ Indicizzato: " + doc.getFileName(), 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            loadDocuments();
        } catch (Exception e) {
            log.error("Errore indicizzazione id={}", doc.getId(), e);
            Notification.show("❌ " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void confirmDelete(ParsedDocumentSummary doc) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Elimina documento");
        dialog.setText("Eliminare \"" + doc.getFileName() + "\"? Se indicizzato verrà rimosso anche da Elasticsearch.");
        dialog.setCancelable(true);
        dialog.setCancelText("Annulla");
        dialog.setConfirmText("Elimina");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> deleteDocument(doc));
        dialog.open();
    }

    private void deleteDocument(ParsedDocumentSummary doc) {
        try {
            ragApiService.deleteParsedDocument(doc.getId());
            Notification.show("🗑️ Eliminato: " + doc.getFileName(), 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            loadDocuments();
        } catch (Exception e) {
            log.error("Errore eliminazione id={}", doc.getId(), e);
            Notification.show("❌ " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private static String stateLabel(String state) {
        return switch (state != null ? state : "") {
            case "PROCESSING"   -> "⏳ In trascrizione";
            case "TRANSCRIBED"  -> "🟡 Trascritto";
            case "INDEXING"     -> "⏳ Indicizzando";
            case "INDEXED"      -> "🟢 Indicizzato";
            case "ERROR"        -> "🔴 Errore";
            default             -> state != null ? state : "—";
        };
    }

    private static String stateColor(String state) {
        return switch (state != null ? state : "") {
            case "PROCESSING"   -> "#7f8c8d";
            case "TRANSCRIBED"  -> "#e6a817";
            case "INDEXING"     -> "#3498db";
            case "INDEXED"      -> "#2e8b57";
            case "ERROR"        -> "#c0392b";
            default             -> "#888";
        };
    }
}
