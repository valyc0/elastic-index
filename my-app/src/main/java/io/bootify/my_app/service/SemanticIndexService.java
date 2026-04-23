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
 * Indicizza i chunk di un documento nell'indice semantico, passando attraverso
 * l'ingest pipeline di Elasticsearch che genera automaticamente l'embedding
 * sparso ELSER sul campo "content".
 */
@Service
public class SemanticIndexService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);

    public static final String SEMANTIC_INDEX = "semantic_docs";
    public static final String SEMANTIC_PIPELINE = "elser-v2-sparse";

    private final ElasticsearchClient elasticsearchClient;

    public SemanticIndexService(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    /**
     * Suddivide il documento in chunk e li indicizza nel vettore semantico.
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

            String chunkId = UUID.randomUUID().toString();
            try {
                IndexRequest<SemanticChunk> request = IndexRequest.of(b -> b
                        .index(SEMANTIC_INDEX)
                        .id(chunkId)
                        .pipeline(SEMANTIC_PIPELINE)
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
