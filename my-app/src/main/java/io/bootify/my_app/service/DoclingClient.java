package io.bootify.my_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.ChapterSection;
import io.bootify.my_app.model.DoclingParseResponse;
import io.bootify.my_app.model.DoclingParseResponse.DoclingSection;
import io.bootify.my_app.model.DoclingParseResponse.DoclingTable;
import io.bootify.my_app.model.DocumentExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// forza HTTP/1.1 per compatibilità con uvicorn multipart
import static java.net.http.HttpClient.Version.HTTP_1_1;

/**
 * Client Java per il microservizio Docling (Python/FastAPI).
 *
 * <p>Invia il file al servizio Docling via multipart/form-data e converte
 * la risposta strutturata in {@link DocumentExtractionResult} con sezioni
 * pronte per il chunking semantico.
 *
 * <p>Le tabelle estratte da Docling vengono aggiunte come sezioni dedicate
 * con titolo "Tabella" + numero progressivo, così vengono indicizzate
 * e ricercabili separatamente dal testo narrativo.
 */
@Service
public class DoclingClient {

    private static final Logger log = LoggerFactory.getLogger(DoclingClient.class);

    private final String doclingUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public DoclingClient(
            @Value("${docling.url:http://localhost:8001}") String doclingUrl,
            @Value("${docling.timeout-seconds:600}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.doclingUrl = doclingUrl;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HTTP_1_1)   // HTTP/2 può causare problemi con multipart/form-data su uvicorn
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Invia il file al servizio Docling e restituisce il risultato di estrazione.
     *
     * @param file file caricato dal client HTTP
     * @return risultato con sezioni strutturate, tabelle e metadati
     * @throws DoclingException se il servizio non è raggiungibile o restituisce errore
     */
    public DocumentExtractionResult parse(MultipartFile file) {
        log.info("Docling parse: file={}, size={} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            byte[] fileBytes = file.getBytes();
            String fileName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "document";
            String boundary = UUID.randomUUID().toString().replace("-", "");

            byte[] body = buildMultipartBody(boundary, fileName, fileBytes,
                    file.getContentType());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(doclingUrl + "/parse"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new DoclingException(
                        "Docling service error HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            DoclingParseResponse parsed = objectMapper.readValue(
                    response.body(), DoclingParseResponse.class);

            log.info("Docling risposta: file={}, sezioni={}, tabelle={}, pagine={}",
                    parsed.getFileName(),
                    parsed.getSections() != null ? parsed.getSections().size() : 0,
                    parsed.getTables() != null ? parsed.getTables().size() : 0,
                    parsed.getPageCount());

            return toExtractionResult(parsed, fileName);

        } catch (DoclingException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoclingException(
                    "Impossibile raggiungere il servizio Docling su " + doclingUrl
                    + ". Verificare che sia avviato.", e);
        } catch (Exception e) {
            throw new DoclingException("Errore durante il parsing Docling: " + e.getMessage(), e);
        }
    }

    /**
     * Verifica che il servizio Docling sia raggiungibile.
     *
     * @return true se il servizio risponde correttamente
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(doclingUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Docling service non disponibile su {}: {}", doclingUrl, e.getMessage());
            return false;
        }
    }

    // ── Conversione DoclingParseResponse → DocumentExtractionResult ──────────

    private DocumentExtractionResult toExtractionResult(DoclingParseResponse parsed,
                                                          String originalFileName) {
        DocumentExtractionResult result = new DocumentExtractionResult(
                parsed.getFullText(),
                parsed.getMetadata() != null ? parsed.getMetadata() : java.util.Map.of(),
                originalFileName
        );

        List<ChapterSection> sections = new ArrayList<>();

        if (parsed.getSections() != null) {
            for (DoclingSection s : parsed.getSections()) {
                if (s.getText() == null || s.getText().isBlank()) continue;

                // Titolo visualizzato: per le sottosezioni includiamo il capitolo padre
                // Formato: "Capitolo 1 > 1.2 Sottosezione"
                String displayTitle = buildDisplayTitle(s);

                sections.add(new ChapterSection(
                        displayTitle,
                        s.getChapterIndex(),
                        s.getText()
                ));
            }
        }

        // Tabelle: aggiunte come sezioni speciali per l'indicizzazione
        if (parsed.getTables() != null) {
            int tableOffset = sections.size();
            for (int i = 0; i < parsed.getTables().size(); i++) {
                DoclingTable table = parsed.getTables().get(i);
                if (table.getTextRepresentation() == null
                        || table.getTextRepresentation().isBlank()) continue;
                String caption = table.getCaption() != null
                        ? table.getCaption()
                        : "Tabella " + (i + 1);
                sections.add(new ChapterSection(
                        caption,
                        tableOffset + i,
                        table.getTextRepresentation()
                ));
            }
        }

        if (!sections.isEmpty()) {
            result.setChapters(sections);
        }

        return result;
    }

    /**
     * Costruisce il titolo da mostrare nel chunk.
     * <ul>
     *   <li>H1 (capitolo): usa il titolo diretto</li>
     *   <li>H2/H3 con capitolo padre noto: "Capitolo Padre > Titolo Sezione"</li>
     *   <li>H2/H3 senza capitolo padre: usa il titolo diretto</li>
     * </ul>
     */
    private String buildDisplayTitle(DoclingSection s) {
        if (s.isSubsection()
                && s.getParentChapterTitle() != null
                && !s.getParentChapterTitle().isBlank()) {
            return s.getParentChapterTitle() + " > " + s.getTitle();
        }
        return s.getTitle() != null ? s.getTitle() : "";
    }

    // ── Costruzione corpo multipart/form-data manuale ─────────────────────────

    private byte[] buildMultipartBody(String boundary, String fileName,
                                       byte[] fileBytes, String contentType) throws IOException {
        String ct = contentType != null ? contentType : "application/octet-stream";
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + ct + "\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);
        return body;
    }
}
