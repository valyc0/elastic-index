package io.bootify.my_app.util;

import io.bootify.my_app.model.ChapterSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChunkingUtils {

    private static final int CHUNK_SIZE = 500; // parole per chunk
    private static final int OVERLAP = 100;     // parole di overlap

    /**
     * Pattern per riconoscere intestazioni di capitolo in italiano e inglese:
     * - "Capitolo 1", "Chapter I", "CAPITOLO 2: Titolo"
     * - "Sezione 3", "Section III", "Parte 1"
     * - "1. Introduzione", "2.1 Background" (anche con sottocapitoli)
     * - "I. Prefazione", "II. Analisi" (numerazione romana standalone)
     */
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
        "(?m)^[ \\t]*(?:" +
        "(?:Capitolo|Chapter|CAPITOLO|CHAPTER|Sezione|Section|SEZIONE|SECTION|Parte|Part|PARTE|PART)" +
        "\\s+(?:[IVXLCDM]+|\\d+)(?:[\\s.:–—\\-].*)?|" +
        "(?:\\d{1,2}(?:\\.\\d{1,2})*\\.?\\s{1,4}[A-ZÀÁÂÄÈÉÊËÌÍÎÏÒÓÔÖÙÚÛÜ][^\\n]{2,})|" +
        "(?:[IVXLCDM]{1,6}\\.\\s+[A-ZÀÁÂÄÈÉÊËÌÍÎÏÒÓÔÖÙÚÛÜ][^\\n]{3,})" +
        ")[ \\t]*$"
    );

    /**
     * Rappresenta un chunk di testo con le informazioni sul capitolo di appartenenza.
     */
    public record ChunkEntry(int chunkIndex, String content, String chapterTitle, int chapterIndex) {}



    /**
     * Suddivide il testo in chunk usando il fallback regex per rilevare i capitoli.
     */
    public static List<ChunkEntry> chunkWithChapters(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        return chunkFromSections(splitByChapters(text));
    }

    /**
     * Produce chunk da sezioni già estratte (es. da PDFBox outline).
     * Ogni chunk eredita il chapterTitle e chapterIndex della sezione di appartenenza.
     */
    public static List<ChunkEntry> chunkFromSections(List<ChapterSection> sections) {
        List<ChunkEntry> result = new ArrayList<>();
        int globalChunkIndex = 0;
        for (ChapterSection section : sections) {
            for (String chunkContent : chunkText(section.getText())) {
                result.add(new ChunkEntry(globalChunkIndex++, chunkContent, section.getTitle(), section.getChapterIndex()));
            }
        }
        return result;
    }

    /**
     * Metodo mantenuto per compatibilità: restituisce solo il testo dei chunk.
     */
    public static List<String> chunk(String text) {
        return chunkWithChapters(text).stream().map(ChunkEntry::content).toList();
    }

    private static List<ChapterSection> splitByChapters(String text) {
        Matcher matcher = CHAPTER_HEADING.matcher(text);

        List<int[]> positions = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        while (matcher.find()) {
            positions.add(new int[]{matcher.start(), matcher.end()});
            titles.add(matcher.group().trim());
        }

        if (positions.isEmpty()) {
            return List.of(new ChapterSection("", 0, text.trim()));
        }

        List<ChapterSection> sections = new ArrayList<>();
        int chapterIdx = 0;

        // Testo prima del primo capitolo (prefazione / introduzione)
        String prefix = text.substring(0, positions.get(0)[0]).trim();
        if (!prefix.isEmpty()) {
            sections.add(new ChapterSection("", chapterIdx++, prefix));
        }

        // Ogni capitolo
        for (int i = 0; i < positions.size(); i++) {
            int bodyStart = positions.get(i)[1];
            int bodyEnd = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).trim();
            if (!body.isEmpty()) {
                sections.add(new ChapterSection(titles.get(i), chapterIdx++, body));
            }
        }

        return sections.isEmpty() ? List.of(new ChapterSection("", 0, text.trim())) : sections;
    }

    private static List<String> chunkText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < words.length; start += (CHUNK_SIZE - OVERLAP)) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end == words.length) break;
        }
        return chunks;
    }
}
