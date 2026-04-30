package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.SemanticChunk;
import io.bootify.my_app.service.embedding.EmbeddingProvider;
import io.bootify.my_app.util.ChunkingUtils.ChunkEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Indicizza i chunk di un documento nell'indice semantico.
 *
 * <p>Usa {@link SemanticChunkingService} per un chunking semantico
 * (sentence-aware + overlap controllato) e {@link EmbeddingProvider}
 * come astrazione intercambiabile per la generazione degli embedding.
 *
 * <p>Il flusso di indicizzazione è:
 * <ol>
 *   <li>Genera tutti gli embedding in memoria (se fallisce, i chunk vecchi restano intatti)</li>
 *   <li>Elimina i chunk precedenti per lo stesso fileName (deduplicazione)</li>
 *   <li>Indicizza tutti i chunk con una singola {@code BulkRequest}</li>
 * </ol>
 */
@Service
public class SemanticIndexService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);

    /** Titoli che contengono riferimenti di pagina: tipici indici/sommari (es. "PARTE PRIMA: pagina 16."). */
    private static final Pattern TOC_TITLE_PATTERN = Pattern.compile("(?i)pagina\\s+\\d+");

    /** Numero minimo di parole nel contenuto perché un chunk venga indicizzato. */
    private static final int MIN_CONTENT_WORDS = 10;

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingProvider embeddingProvider;
    private final SemanticChunkingService chunkingService;

    @Value("${semantic.index.name:semantic_docs}")
    private String semanticIndex;

    public SemanticIndexService(ElasticsearchClient elasticsearchClient,
                                EmbeddingProvider embeddingProvider,
                                SemanticChunkingService chunkingService) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingProvider = embeddingProvider;
        this.chunkingService = chunkingService;
    }

    /**
     * Suddivide il documento in chunk semantici, genera gli embedding
     * e li indicizza in Elasticsearch con una singola BulkRequest.
     *
     * <p>Se il file era già stato indicizzato, i chunk precedenti vengono
     * eliminati prima dell'inserimento per evitare duplicati.
     *
     * @return numero di chunk indicizzati
     */
    public int indexDocument(String documentId, DocumentExtractionResult result) {
        List<ChunkEntry> chunks;
        if (result.getChapters() != null && !result.getChapters().isEmpty()) {
            chunks = chunkingService.chunkSections(result.getChapters());
            log.info("Chunking semantico da sezioni: {} sezioni → {} chunk",
                    result.getChapters().size(), chunks.size());
        } else {
            chunks = chunkingService.chunkText(result.getText());
            log.info("Chunking semantico da testo grezzo: {} chunk", chunks.size());
        }

        if (chunks.isEmpty()) {
            log.warn("Nessun chunk generato per il documento: {}", result.getFileName());
            return 0;
        }

        // Filtra chunk di frontmatter/indice prima di generare gli embedding.
        int before = chunks.size();
        chunks = chunks.stream().filter(e -> !isBoilerplate(e)).toList();
        int skipped = before - chunks.size();
        if (skipped > 0) {
            log.info("Filtrati {} chunk di frontmatter/indice (su {})", skipped, before);
        }
        if (chunks.isEmpty()) {
            log.warn("Nessun chunk valido dopo il filtraggio per: {}", result.getFileName());
            return 0;
        }

        // 1. Genera tutti gli embedding prima di modificare l'indice.
        //    Un eventuale errore qui non altera i dati già presenti.
        List<SemanticChunk> preparedChunks = new ArrayList<>(chunks.size());
        for (ChunkEntry entry : chunks) {
            SemanticChunk chunk = new SemanticChunk();
            chunk.setDocumentId(documentId);
            chunk.setFileName(result.getFileName());
            chunk.setChunkIndex(entry.chunkIndex());
            chunk.setContent(entry.content());
            chunk.setChapterTitle(entry.chapterTitle());
            chunk.setChapterIndex(entry.chapterIndex());
            chunk.setContentEmbedding(embeddingProvider.embed(entry.content()));
            preparedChunks.add(chunk);
        }
        log.debug("Embedding generati: {}/{}", preparedChunks.size(), chunks.size());

        // 2. Deduplicazione: rimuovi chunk precedenti per questo file.
        deleteChunksByFileName(result.getFileName());

        // 3. Indicizza tutti i chunk con una singola BulkRequest.
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (SemanticChunk chunk : preparedChunks) {
            final String chunkId = UUID.randomUUID().toString();
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(semanticIndex)
                            .id(chunkId)
                            .document(chunk)
                    )
            );
        }

        try {
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                long errorCount = response.items().stream()
                        .filter(item -> item.error() != null)
                        .count();
                log.error("Bulk indexing parzialmente fallito: {}/{} chunk con errore",
                        errorCount, preparedChunks.size());
                if (errorCount == preparedChunks.size()) {
                    throw new RuntimeException(
                            "Bulk indexing completamente fallito per: " + result.getFileName());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Errore bulk indexing per: " + result.getFileName(), e);
        }

        log.info("Indicizzazione semantica completata: documentId={}, file={}, chunks={}, embeddingModel={}",
                documentId, result.getFileName(), preparedChunks.size(), embeddingProvider.modelName());
        return preparedChunks.size();
    }

    /**
     * Restituisce true se il chunk è frontmatter/indice da non indicizzare
     * (titolo con riferimento di pagina, o contenuto troppo breve).
     */
    private boolean isBoilerplate(ChunkEntry entry) {
        if (entry.chapterTitle() != null && TOC_TITLE_PATTERN.matcher(entry.chapterTitle()).find()) {
            return true;
        }
        if (entry.content() == null) return true;
        long wordCount = entry.content().trim().isEmpty() ? 0
                : entry.content().trim().split("\\s+").length;
        return wordCount < MIN_CONTENT_WORDS;
    }

    /**
     * Elimina in modo sincrono tutti i chunk dell'indice associati al fileName indicato.
     *
     * @return numero di chunk eliminati, -1 in caso di errore
     */
    public long deleteChunksByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return 0L;
        try {
            DeleteByQueryResponse response = elasticsearchClient.deleteByQuery(d -> d
                    .index(semanticIndex)
                    .query(q -> q.term(t -> t.field("fileName").value(fileName)))
            );
            long deleted = response.deleted() != null ? response.deleted() : 0;
            if (deleted > 0) {
                log.info("Eliminati {} chunk per '{}'", deleted, fileName);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("Impossibile eliminare chunk per '{}': {}", fileName, e.getMessage());
            return -1;
        }
    }
}
