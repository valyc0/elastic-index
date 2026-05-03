package com.example.ragclient.views;

import com.example.ragclient.service.RagApiService;
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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Route(value = "documents", layout = MainLayout.class)
@PageTitle("Documents | RAG Client")
@Slf4j
public class DocumentListView extends VerticalLayout implements BeforeEnterObserver {

    private final RagApiService ragApiService;
    private final Grid<DocumentItem> grid;
    private final Paragraph statsLabel;

    @Data
    @AllArgsConstructor
    public static class DocumentItem {
        private String filename;
    }

    public DocumentListView(RagApiService ragApiService) {
        this.ragApiService = ragApiService;

        setSpacing(true);
        setPadding(true);
        setMaxWidth("1200px");
        getStyle().set("margin", "0 auto");

        // Header
        H2 title = new H2("📚 Indexed Documents");
        
        // Refresh button
        Button refreshButton = new Button("🔄 Refresh", VaadinIcon.REFRESH.create(), 
            event -> loadDocuments());
        refreshButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        
        HorizontalLayout headerLayout = new HorizontalLayout(title, refreshButton);
        headerLayout.setWidthFull();
        headerLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);
        headerLayout.setAlignItems(Alignment.CENTER);
        add(headerLayout);

        // Stats label
        statsLabel = new Paragraph();
        statsLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(statsLabel);

        // Grid
        grid = new Grid<>(DocumentItem.class, false);
        grid.setHeight("600px");

        grid.addColumn(DocumentItem::getFilename)
            .setHeader("📄 Filename")
            .setFlexGrow(3)
            .setSortable(true);

        grid.addColumn(item -> "INDEXED")
            .setHeader("🔄 Stato")
            .setFlexGrow(1);

        grid.addComponentColumn(item -> {
            Button deleteButton = new Button("Delete", VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            deleteButton.addClickListener(event -> confirmDelete(item.getFilename()));
            return deleteButton;
        }).setHeader("Actions").setFlexGrow(1);

        add(grid);

        // Load initial data
        loadDocuments();
    }

    private void loadDocuments() {
        try {
            List<String> documents = ragApiService.getDocumentList().getDocuments();

            List<DocumentItem> items = new ArrayList<>();

            if (documents != null) {
                for (String filename : documents) {
                    items.add(new DocumentItem(filename));
                }
            }

            items.sort(Comparator.comparing(DocumentItem::getFilename, String.CASE_INSENSITIVE_ORDER));

            grid.setItems(items);

            statsLabel.setText(String.format("📊 Documenti indicizzati disponibili: %d", items.size()));

        } catch (Exception e) {
            log.error("Error loading documents", e);
            Notification notification = Notification.show(
                "❌ Error loading documents: " + e.getMessage(),
                5000,
                Notification.Position.MIDDLE
            );
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Ricarica sempre quando si entra nella vista
        loadDocuments();
    }

    private void confirmDelete(String filename) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete Document");
        dialog.setText("Are you sure you want to delete \"" + filename + "\"? This action cannot be undone.");
        
        dialog.setCancelable(true);
        dialog.setCancelText("Cancel");
        
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");
        
        dialog.addConfirmListener(event -> deleteDocument(filename));
        
        dialog.open();
    }

    private void deleteDocument(String filename) {
        try {
            Map<String, Object> response = ragApiService.deleteDocument(filename);

            Number deleted = response != null ? (Number) response.get("deleted") : null;
            String message = response != null ? (String) response.get("message") : "Documento eliminato";

            if (deleted != null && deleted.longValue() >= 0) {
                Notification notification = Notification.show(
                    message,
                    3000,
                    Notification.Position.MIDDLE
                );
                notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                
                // Reload documents
                loadDocuments();
            } else {
                Notification notification = Notification.show(
                    "❌ " + message,
                    5000,
                    Notification.Position.MIDDLE
                );
                notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }

        } catch (Exception e) {
            log.error("Error deleting document", e);
            Notification notification = Notification.show(
                "❌ Error deleting document: " + e.getMessage(),
                5000,
                Notification.Position.MIDDLE
            );
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
