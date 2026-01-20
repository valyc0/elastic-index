package io.bootify.my_app.config;

import io.bootify.my_app.model.DocumentChunk;
import org.springframework.stereotype.Component;

@Component("indexNameResolver")
public class IndexNameResolver {

    public String resolve(DocumentChunk chunk) {
        if (chunk == null || chunk.getLanguage() == null) {
            return "files_generic";
        }
        
        return switch (chunk.getLanguage()) {
            case "it" -> "files_it";
            case "en" -> "files_en";
            case "fr" -> "files_fr";
            case "de" -> "files_de";
            case "es" -> "files_es";
            default -> "files_generic";
        };
    }
}
