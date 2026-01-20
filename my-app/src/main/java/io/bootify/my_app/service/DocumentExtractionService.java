package io.bootify.my_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bootify.my_app.model.DocumentExtractionResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class DocumentExtractionService {

    private final ObjectMapper objectMapper;
    private static final String OUTPUT_DIR = "extracted-documents";

    public DocumentExtractionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        createOutputDirectory();
    }

    private void createOutputDirectory() {
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create output directory", e);
        }
    }

    public DocumentExtractionResult extractTextAndMetadata(MultipartFile file) {
        try {
            // Estrae testo e metadati usando Tika
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 per nessun limite
            Metadata metadata = new Metadata();
            ParseContext parseContext = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            try (InputStream stream = file.getInputStream()) {
                parser.parse(stream, handler, metadata, parseContext);
            }

            // Converte i metadati in una mappa
            Map<String, String> metadataMap = new HashMap<>();
            for (String name : metadata.names()) {
                metadataMap.put(name, metadata.get(name));
            }

            String extractedText = handler.toString();
            String fileName = file.getOriginalFilename();

            // Crea il risultato
            DocumentExtractionResult result = new DocumentExtractionResult(
                    extractedText,
                    metadataMap,
                    fileName
            );

            // Salva il risultato in un file JSON
            saveToJsonFile(result, fileName);

            return result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text and metadata from file", e);
        }
    }

    private void saveToJsonFile(DocumentExtractionResult result, String originalFileName) {
        try {
            // Genera nome file con timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String baseFileName = originalFileName != null ? 
                    originalFileName.replaceAll("[^a-zA-Z0-9.-]", "_") : "document";
            String jsonFileName = baseFileName + "_" + timestamp + ".json";
            
            Path outputPath = Paths.get(OUTPUT_DIR, jsonFileName);
            
            // Scrive il file JSON
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputPath.toFile(), result);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to save JSON file", e);
        }
    }
}
