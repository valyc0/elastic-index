package io.bootify.my_app.util;

import io.bootify.my_app.model.ChapterSection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Estrae i capitoli da un PDF usando l'outline (segnalibri/TOC) embedded nella struttura del file.
 * Restituisce lista vuota se il PDF non ha outline — in quel caso ChunkingUtils userà il fallback regex.
 */
public class PdfOutlineExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfOutlineExtractor.class);

    /**
     * Tenta l'estrazione delle sezioni capitolo dall'outline PDFBox.
     *
     * @param pdfBytes contenuto del PDF in byte (può essere riletto più volte)
     * @return lista di ChapterSection ordinata, vuota se nessun outline è disponibile
     */
    public static List<ChapterSection> extract(byte[] pdfBytes) {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null || outline.getFirstChild() == null) {
                log.info("PDF outline not found, will use regex fallback");
                return List.of();
            }

            int totalPages = doc.getNumberOfPages();
            List<String> titles = new ArrayList<>();
            List<Integer> startPages = new ArrayList<>();  // 0-based

            PDOutlineItem item = outline.getFirstChild();
            while (item != null) {
                int pageIndex = resolvePageIndex(item, doc);
                if (pageIndex >= 0) {
                    titles.add(item.getTitle());
                    startPages.add(pageIndex);
                }
                item = item.getNextSibling();
            }

            if (titles.isEmpty()) {
                log.info("PDF outline present but no resolvable page destinations");
                return List.of();
            }

            log.info("PDF outline found: {} top-level entries", titles.size());

            PDFTextStripper stripper = new PDFTextStripper();
            List<ChapterSection> sections = new ArrayList<>();

            // Testo prima del primo capitolo (es. indice, prefazione)
            if (startPages.get(0) > 0) {
                stripper.setStartPage(1);
                stripper.setEndPage(startPages.get(0)); // 0-based → 1-based inclusive
                String preText = stripper.getText(doc).trim();
                if (!preText.isEmpty()) {
                    sections.add(new ChapterSection("", 0, preText));
                }
            }

            // Un capitolo per ogni voce dell'outline
            for (int i = 0; i < titles.size(); i++) {
                int startPage1 = startPages.get(i) + 1;  // 1-based
                int endPage1 = (i + 1 < startPages.size()) ? startPages.get(i + 1) : totalPages;

                stripper.setStartPage(startPage1);
                stripper.setEndPage(endPage1);
                String text = stripper.getText(doc).trim();

                if (!text.isEmpty()) {
                    sections.add(new ChapterSection(titles.get(i), sections.size(), text));
                }
            }

            log.info("Extracted {} sections from PDF outline", sections.size());
            return sections;

        } catch (Exception e) {
            log.warn("PDFBox outline extraction failed ({}), will use regex fallback", e.getMessage());
            return List.of();
        }
    }

    private static int resolvePageIndex(PDOutlineItem item, PDDocument doc) {
        try {
            PDDestination dest = item.getDestination();
            if (dest == null) {
                var action = item.getAction();
                if (action instanceof PDActionGoTo goTo) {
                    dest = goTo.getDestination();
                }
            }
            if (dest instanceof PDPageDestination pageDest) {
                // Prova prima il numero di pagina diretto
                int idx = pageDest.getPageNumber();
                if (idx >= 0) return idx;
                // Fallback: risolvi tramite oggetto pagina
                PDPage page = pageDest.getPage();
                if (page != null) return doc.getPages().indexOf(page);
            }
        } catch (Exception e) {
            log.debug("Cannot resolve page for outline item '{}': {}", item.getTitle(), e.getMessage());
        }
        return -1;
    }
}
