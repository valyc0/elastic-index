package com.example.ragclient.views;

import com.example.ragclient.dto.DoclingJobStatusResponse;
import com.example.ragclient.dto.UploadResponse;
import com.example.ragclient.service.RagApiService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

@Route(value = "upload", layout = MainLayout.class)
@PageTitle("Upload Document | RAG Client")
@Slf4j
public class UploadView extends VerticalLayout {

    private final RagApiService ragApiService;
    private final MemoryBuffer buffer;
    private final Upload upload;
    private final Paragraph statusLabel;
    private final ProgressBar progressBar;

    private String currentJobId;
    private Registration pollRegistration;

    public UploadView(RagApiService ragApiService) {
        this.ragApiService = ragApiService;
        this.buffer = new MemoryBuffer();

        setSpacing(true);
        setPadding(true);
        setMaxWidth("800px");
        getStyle().set("margin", "0 auto");

        H2 title = new H2("📤 Upload Document");
        add(title);

        Paragraph description = new Paragraph(
            "Upload PDF, Word, Excel, PowerPoint, TXT, or HTML files to index them in the RAG system."
        );
        description.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(description);

        upload = new Upload(buffer);
        upload.setAcceptedFileTypes(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/html",
            "application/xml"
        );
        upload.setMaxFileSize(100 * 1024 * 1024); // 100MB
        upload.setMaxFiles(1);
        upload.setDropLabel(new Paragraph("Drop file here or click to browse"));

        upload.addSucceededListener(event -> uploadFile(event.getFileName()));

        upload.addFailedListener(event -> {
            Notification notification = Notification.show(
                "❌ Upload failed: " + event.getReason().getMessage(),
                5000,
                Notification.Position.MIDDLE
            );
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        });

        add(upload);

        progressBar = new ProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        add(progressBar);

        statusLabel = new Paragraph();
        statusLabel.setVisible(false);
        add(statusLabel);
    }

    private void uploadFile(String filename) {
        try {
            showStatus("⏳ Uploading " + filename + "...", false);

            InputStream inputStream = buffer.getInputStream();
            byte[] content = inputStream.readAllBytes();

            UploadResponse response = ragApiService.uploadDocument(filename, content);

            if (response == null) {
                showError("Nessuna risposta dal server");
                return;
            }

            if (response.getError() != null) {
                showError(response.getError());
                return;
            }

            if (response.getJobId() != null) {
                currentJobId = response.getJobId();
                startPolling(filename);
            } else {
                // risposta sincrona (fallback)
                showSuccessAndNavigate(null);
            }

        } catch (Exception e) {
            log.error("Error uploading file", e);
            showError(e.getMessage());
        }
    }

    private void startPolling(String filename) {
        showStatus("⏳ In elaborazione: " + filename + "... (QUEUED)", false);
        UI ui = UI.getCurrent();
        upload.getElement().setEnabled(false);
        ui.setPollInterval(3000);

        pollRegistration = ui.addPollListener(event -> {
            try {
                DoclingJobStatusResponse status = ragApiService.getDoclingJobStatus(currentJobId);
                handleJobStatus(status);
            } catch (Exception e) {
                log.warn("Errore polling job {}: {}", currentJobId, e.getMessage());
            }
        });
    }

    private void handleJobStatus(DoclingJobStatusResponse status) {
        if (status == null) return;

        String st = status.getStatus();
        showStatus(buildStatusMessage(status), false);

        if ("DONE".equals(st)) {
            stopPolling();
            showSuccessAndNavigate(status);
        } else if ("ERROR".equals(st)) {
            stopPolling();
            showError(status.getError() != null ? status.getError() : "Elaborazione fallita");
        }
    }

    private String buildStatusMessage(DoclingJobStatusResponse status) {
        return switch (status.getStatus()) {
            case "QUEUED"    -> "⏳ In coda: " + status.getFileName() + "...";
            case "PARSING"   -> "🔍 Parsing Docling in corso: " + status.getFileName() + "...";
            case "INDEXING"  -> "📦 Indicizzazione in Elasticsearch...";
            case "DONE"      -> "✅ " + (status.getMessage() != null ? status.getMessage() : "Completato");
            case "ERROR"     -> "❌ Errore: " + (status.getError() != null ? status.getError() : "Sconosciuto");
            default          -> "⏳ Stato: " + status.getStatus();
        };
    }

    private void stopPolling() {
        UI ui = UI.getCurrent();
        if (ui != null) {
            ui.setPollInterval(-1);
        }
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        upload.getElement().setEnabled(true);
        progressBar.setVisible(false);
    }

    private void showSuccessAndNavigate(DoclingJobStatusResponse status) {
        String msg = status != null && status.getChunks() != null
                ? "✅ Documento indicizzato: " + status.getChunks() + " chunks"
                : "✅ Documento indicizzato con successo";

        Notification notification = Notification.show(msg, 4000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        upload.clearFileList();
        UI.getCurrent().navigate(DocumentListView.class);
    }

    private void showError(String message) {
        stopPolling();
        Notification notification = Notification.show(
            "❌ " + message, 5000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        showStatus("❌ Errore: " + message, true);
    }

    private void showStatus(String text, boolean isError) {
        statusLabel.setText(text);
        statusLabel.getStyle().set("color", isError
                ? "var(--lumo-error-color)"
                : "var(--lumo-secondary-text-color)");
        statusLabel.setVisible(true);
        progressBar.setVisible(!isError && !text.startsWith("✅"));
    }
}

