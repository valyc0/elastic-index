package io.bootify.my_app.service;

import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.springframework.stereotype.Service;

import static org.apache.tika.language.detect.LanguageDetector.getDefaultLanguageDetector;

@Service
public class LanguageDetectionService {

    private final LanguageDetector detector;

    public LanguageDetectionService() {
        try {
            this.detector = getDefaultLanguageDetector().loadModels();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize language detector", e);
        }
    }

    public String detect(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "unknown";
        }
        
        try {
            LanguageResult result = detector.detect(text);
            return result.isReasonablyCertain() ? result.getLanguage() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
