package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.SemanticChunk;
import io.bootify.my_app.service.embedding.EmbeddingProvider;
import io.bootify.my_app.util.ChunkingUtils.ChunkEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Indicizza i chunk di un documento nell'indice semantico.
 *
 * <p>Usa {@link SemanticChunkingService} per un chunking semantico
 * (sentence-aware + overlap controllato) e {@link EmbeddingProvider}
 * come astrazione intercambiabile per la generazione degli embedding.
 */
@Service
public class SemanticIndexService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);

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
     * e li indicizza in Elasticsearch.
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

        int indexed = 0;
        for (ChunkEntry entry : chunks) {
            SemanticChunk chunk = new SemanticChunk();
            chunk.setDocumentId(documentId);
            chunk.setFileName(result.getFileName());
            chunk.setChunkIndex(entry.chunkIndex());
            chunk.setContent(entry.content());
            chunk.setChapterTitle(entry.chapterTitle());
            chunk.setChapterIndex(entry.chapterIndex());

            List<Float> embedding = embeddingProvider.embed(entry.content());
            chunk.setContentEmbedding(embedding);

            String chunkId = UUID.randomUUID().toString();
            try {
                IndexRequest<SemanticChunk> request = IndexRequest.of(b -> b
                        .index(semanticIndex)
                        .id(chunkId)
                        .document(chunk)
                );
                elasticsearchClient.index(request);
                indexed++;
                log.debug("Indicizzato chunk {}/{}: id={}", indexed, chunks.size(), chunkId);
            } catch (Exception e) {
                throw new RuntimeException("Errore indicizzazione chunk id=" + chunkId, e);
            }
        }

        log.info("Indicizzazione semantica completata: documentId={}, file={}, chunks={}, embeddingModel={}",
                documentId, result.getFileName(), indexed, embeddingProvider.modelName());
        return indexed;
    }
}
