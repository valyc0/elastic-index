package io.bootify.my_app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChunkingUtils {

    private static final int CHUNK_SIZE = 500; // parole per chunk
    private static final int OVERLAP = 100;     // parole di overlap

    public static List<String> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();

        for (int start = 0; start < words.length; start += (CHUNK_SIZE - OVERLAP)) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
