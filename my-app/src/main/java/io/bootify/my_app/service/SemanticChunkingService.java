package io.bootify.my_app.service;

import io.bootify.my_app.model.ChapterSection;
import io.bootify.my_app.util.ChunkingUtils;
import io.bootify.my_app.util.ChunkingUtils.ChunkEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Servizio di chunking semantico per la pipeline RAG.
 *
 * <p>Strategia ibrida a tre livelli:
 * <ol>
 *   <li><b>Struttura logica</b>: usa capitoli/sezioni estratti (PDFBox outline o regex)</li>
 *   <li><b>Confini di paragrafo</b>: divide sulle righe vuote prima di fare split per parole</li>
 *   <li><b>Overlap controllato</b>: le ultime {@code overlapSentences} frasi di un chunk
 *       vengono aggiunte come prefisso del chunk successivo per preservare il contesto</li>
 * </ol>
 *
 * <p>Questo approccio garantisce che:
 * <ul>
 *   <li>Nessun chunk spezza a metà un concetto</li>
 *   <li>Il contesto non venga perso al confine tra chunk</li>
 *   <li>La struttura logica del documento sia preservata nei metadati</li>
 * </ul>
 */
@Service
public class SemanticChunkingService {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkingService.class);

    // Pattern per splitting su confini di frase: ". ", "! ", "? ", ".\n"
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[.!?])\\s+|(?<=[.!?])\\n");

    // Pattern per splitting su paragrafi (una o più righe vuote)
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n{2,}");

    private final int maxWordsPerChunk;
    private final int overlapSentences;
    private final int minChunkWords;

    public SemanticChunkingService(
            @Value("${chunking.max-words:400}") int maxWordsPerChunk,
            @Value("${chunking.overlap-sentences:2}") int overlapSentences,
            @Value("${chunking.min-words:30}") int minChunkWords) {
        this.maxWordsPerChunk = maxWordsPerChunk;
        this.overlapSentences = overlapSentences;
        this.minChunkWords = minChunkWords;
    }

    /**
     * Produce chunk semantici a partire da sezioni strutturate.
     * Ogni chunk preserva il titolo del capitolo/sezione di appartenenza.
     *
     * @param sections sezioni del documento (da PDFBox outline o regex)
     * @return lista di ChunkEntry ordinata per posizione nel documento
     */
    public List<ChunkEntry> chunkSections(List<ChapterSection> sections) {
        List<ChunkEntry> result = new ArrayList<>();
        int globalIndex = 0;
        for (ChapterSection section : sections) {
            List<String> sectionChunks = chunkTextSemantically(section.getText());
            for (String chunkContent : sectionChunks) {
                result.add(new ChunkEntry(globalIndex++, chunkContent,
                        section.getTitle(), section.getChapterIndex()));
            }
        }
        log.debug("Chunking semantico: {} sezioni → {} chunk", sections.size(), result.size());
        return result;
    }

    /**
     * Produce chunk semantici da testo grezzo senza struttura preesistente.
     *
     * @param text testo completo del documento
     * @return lista di ChunkEntry (chapterTitle vuoto, chapterIndex=0)
     */
    public List<ChunkEntry> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = chunkTextSemantically(text);
        List<ChunkEntry> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            result.add(new ChunkEntry(i, chunks.get(i), "", 0));
        }
        return result;
    }

    /**
     * Core del chunking semantico.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Divide il testo in paragrafi</li>
     *   <li>Ogni paragrafo viene diviso in frasi</li>
     *   <li>Le frasi vengono accumulate finché non si supera {@code maxWordsPerChunk}</li>
     *   <li>Quando il limite viene raggiunto, le ultime {@code overlapSentences} frasi
     *       vengono "riportate" nel chunk successivo come overlap</li>
     * </ol>
     */
    List<String> chunkTextSemantically(String text) {
        if (text == null || text.isBlank()) return List.of();

        // Step 1: estrai tutte le frasi preservando l'ordine
        List<String> sentences = extractSentences(text);
        if (sentences.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();
        int currentWordCount = 0;

        for (String sentence : sentences) {
            int sentenceWordCount = countWords(sentence);
            if (sentenceWordCount == 0) continue;

            // Se la frase da sola supera il limite, dividila per parole
            if (sentenceWordCount > maxWordsPerChunk) {
                // Finalizza chunk corrente prima
                if (!currentChunk.isEmpty()) {
                    chunks.add(String.join(" ", currentChunk));
                    currentChunk = buildOverlap(currentChunk);
                    currentWordCount = countWords(String.join(" ", currentChunk));
                }
                // Spezza la frase lunga
                List<String> subChunks = splitLongSentence(sentence);
                for (int i = 0; i < subChunks.size() - 1; i++) {
                    chunks.add(subChunks.get(i));
                }
                // L'ultimo sub-chunk diventa inizio del prossimo chunk
                String last = subChunks.get(subChunks.size() - 1);
                currentChunk.add(last);
                currentWordCount += countWords(last);
                continue;
            }

            if (currentWordCount + sentenceWordCount > maxWordsPerChunk && !currentChunk.isEmpty()) {
                // Finalizza chunk corrente
                chunks.add(String.join(" ", currentChunk));
                // Overlap: riporta le ultime N frasi
                currentChunk = buildOverlap(currentChunk);
                currentWordCount = countWords(String.join(" ", currentChunk));
            }

            currentChunk.add(sentence);
            currentWordCount += sentenceWordCount;
        }

        // Ultimo chunk residuo
        if (!currentChunk.isEmpty()) {
            String last = String.join(" ", currentChunk);
            if (countWords(last) >= minChunkWords) {
                chunks.add(last);
            } else if (!chunks.isEmpty()) {
                // Troppo corto: appendilo al chunk precedente
                String merged = chunks.get(chunks.size() - 1) + " " + last;
                chunks.set(chunks.size() - 1, merged);
            } else {
                chunks.add(last);
            }
        }

        return chunks;
    }

    /**
     * Estrae le frasi dal testo dividendo prima per paragrafi poi per confini di frase.
     */
    private List<String> extractSentences(String text) {
        List<String> sentences = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_BOUNDARY.split(text);
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) continue;
            String[] parts = SENTENCE_BOUNDARY.split(trimmed);
            for (String part : parts) {
                String s = part.trim();
                if (!s.isBlank()) {
                    sentences.add(s);
                }
            }
        }
        return sentences;
    }

    /**
     * Costruisce la porzione di overlap prendendo le ultime N frasi del chunk corrente.
     */
    private List<String> buildOverlap(List<String> currentChunk) {
        if (overlapSentences <= 0 || currentChunk.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, currentChunk.size() - overlapSentences);
        return new ArrayList<>(currentChunk.subList(start, currentChunk.size()));
    }

    /**
     * Spezza una frase eccessivamente lunga usando uno sliding window di parole.
     */
    private List<String> splitLongSentence(String sentence) {
        String[] words = sentence.split("\\s+");
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < words.length; start += maxWordsPerChunk) {
            int end = Math.min(start + maxWordsPerChunk, words.length);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) sb.append(' ');
                sb.append(words[i]);
            }
            parts.add(sb.toString());
        }
        return parts;
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
