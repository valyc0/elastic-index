package io.bootify.my_app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.model.SemanticChunk;
import io.bootify.my_app.util.ChunkingUtils;
import io.bootify.my_app.util.ChunkingUtils.ChunkEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Indicizza i chunk di un documento nell'indice semantico.
 * L'embedding denso viene generato da Ollama (nomic-embed-text) prima
 * dell'indicizzazione – nessuna ingest pipeline né licenza trial richiesta.
 */
@Service
public class SemanticIndexService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);

    public static final String SEMANTIC_INDEX = "semantic_docs";

    private final ElasticsearchClient elasticsearchClient;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public SemanticIndexService(ElasticsearchClient elasticsearchClient,
                                OllamaEmbeddingService ollamaEmbeddingService) {
        this.elasticsearchClient = elasticsearchClient;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
    }

    /**
     * Suddivide il documento in chunk, genera gli embedding tramite Ollama
     * e li indicizza in Elasticsearch.
     *
     * @return numero di chunk indicizzati
     */
    public int indexDocument(String documentId, DocumentExtractionResult result) {
        List<ChunkEntry> chunks;
        if (result.getChapters() != null && !result.getChapters().isEmpty()) {
            chunks = ChunkingUtils.chunkFromSections(result.getChapters());
            log.info("Using PDFBox outline: {} chapters → {} semantic chunks",
                    result.getChapters().size(), chunks.size());
        } else {
            chunks = ChunkingUtils.chunkWithChapters(result.getText());
            log.info("Using regex chapter detection: {} semantic chunks", chunks.size());
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

            // Genera embedding denso tramite Ollama (free, locale)
            List<Float> embedding = ollamaEmbeddingService.embed(entry.content());
            chunk.setContentEmbedding(embedding);

            String chunkId = UUID.randomUUID().toString();
            try {
                IndexRequest<SemanticChunk> request = IndexRequest.of(b -> b
                        .index(SEMANTIC_INDEX)
                        .id(chunkId)
                        .document(chunk)
                );
                elasticsearchClient.index(request);
                indexed++;
                log.debug("Indexed semantic chunk {}/{}: id={}", indexed, chunks.size(), chunkId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to index semantic chunk id=" + chunkId, e);
            }
        }

        log.info("Semantic indexing complete: documentId={}, file={}, chunks={}",
                documentId, result.getFileName(), indexed);
        return indexed;
    }
}
