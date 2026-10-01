package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The source text extractor gives the same lines after its grouping moved
 * into {@link PdfLineGrouper} as before: the grouping as it was, kept here
 * word for word, is run on the same characters and compared line by line,
 * over every fixture that has text.
 */
class PdfLineGrouperParityTest {

    private static final double LINE_Y_TOLERANCE_FRACTION = 0.4;
    private static final double WORD_SPACE_GAP_FONT_SIZE_MULTIPLE = 0.25;
    private static final double AMBIGUOUS_GAP_FONT_SIZE_MULTIPLE = 3.0;

    @Test
    void theExtractorsLinesAreTheLinesTheOldGroupingGave() throws Exception {
        Map<String, byte[]> documents = new LinkedHashMap<>();
        documents.put("two lines", PdfFixtures.twoLineDocument());
        for (int rotation : new int[] {90, 180, 270}) {
            documents.put("turned " + rotation, PdfFixtures.rotatedTwoLineDocument(rotation));
            documents.put("upright on a page turned " + rotation, PdfFormFixtures.flatFormOnTurnedPage(rotation));
        }
        documents.put("three pages", PdfFixtures.multiPageDocument(3));
        documents.put("text and no text", PdfFixtures.mixedTextAndNoTextPagesDocument());
        documents.put("gap-spaced words", PdfFixtures.gapSpacedWordsDocument());
        documents.put("columns", PdfFixtures.sideBySideColumnsDocument());
        documents.put("grid", PdfFixtures.twoRowGridDocument());
        documents.put("fillable form", PdfFormFixtures.fillableForm());
        documents.put("flat form", PdfFormFixtures.flatForm());
        documents.put("offset crop box", PdfFormFixtures.offsetCropBoxDocument());
        documents.put("subset font", PdfFormFixtures.subsetFontDocument());

        PdfBoxStructuralExtractor extractor = new PdfBoxStructuralExtractor();
        for (Map.Entry<String, byte[]> document : documents.entrySet()) {
            PdfExtractionOutcome outcome = extractor.extract(new ByteArrayInputStream(document.getValue()));
            List<PdfPage> pages = assertInstanceOf(PdfExtractionOutcome.Supported.class, outcome, document.getKey()).graph().pages();
            try (PDDocument pdf = Loader.loadPDF(document.getValue())) {
                for (int index = 0; index < pages.size(); index++) {
                    List<TextPosition> characters = PdfPageText.characters(pdf, index, PdfReadingBudget.standard());
                    assertEquals(groupIntoLines(characters), pages.get(index).lines(), document.getKey() + ", page " + (index + 1));
                }
            }
        }
    }

    // ---- the grouping exactly as the extractor had it before it was shared ----

    private static List<PdfTextLine> groupIntoLines(List<TextPosition> characters) {
        List<TextPosition> sorted = new ArrayList<>(characters);
        sorted.sort(java.util.Comparator
                .comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));

        List<PdfTextLine> lines = new ArrayList<>();
        List<TextPosition> currentLine = new ArrayList<>();
        double currentLineBaselineY = Double.NaN;

        for (TextPosition tp : sorted) {
            if (currentLine.isEmpty()) {
                currentLine.add(tp);
                currentLineBaselineY = tp.getYDirAdj();
                continue;
            }
            double tolerance = Math.max(tp.getHeightDir(), 1f) * LINE_Y_TOLERANCE_FRACTION;
            if (Math.abs(tp.getYDirAdj() - currentLineBaselineY) <= tolerance) {
                currentLine.add(tp);
            } else {
                lines.add(buildLine(lines.size(), currentLine));
                currentLine = new ArrayList<>();
                currentLine.add(tp);
                currentLineBaselineY = tp.getYDirAdj();
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(buildLine(lines.size(), currentLine));
        }
        return lines;
    }

    /**
     * The line's bounding box is a simple envelope around its characters'
     * own reported boxes -- {@code getYDirAdj()} is each character's
     * baseline, and {@code getYDirAdj() - getHeightDir()} approximates the
     * top of its visible glyph, but the bottom is taken as the baseline
     * itself, not extended for a descender (the tail on a 'g', 'y', or
     * 'p'). A real, deliberate simplification: full per-glyph font metrics
     * are not something PDFBox's public {@code TextPosition} exposes
     * simply, and what this graph is used for is page geometry and reading
     * order, not pixel-exact glyph boxes.
     */
    private static PdfTextLine buildLine(int lineIndex, List<TextPosition> lineCharacters) {
        lineCharacters.sort(java.util.Comparator.comparingDouble(TextPosition::getXDirAdj));

        StringBuilder text = new StringBuilder();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean ambiguous = false;
        TextPosition previous = null;

        for (TextPosition tp : lineCharacters) {
            double fontSize = Math.max(tp.getFontSizeInPt(), 1f);
            boolean currentIsWhitespace = isBlank(tp.getUnicode());
            // A literal space character already provides its own
            // separation; synthesizing a gap-based space next to one too
            // would double it up. Only ever synthesize between two
            // genuinely non-whitespace characters.
            if (previous != null && !currentIsWhitespace && !isBlank(previous.getUnicode())) {
                double gap = tp.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj());
                if (gap > AMBIGUOUS_GAP_FONT_SIZE_MULTIPLE * fontSize) {
                    ambiguous = true;
                    text.append(' ');
                } else if (gap > WORD_SPACE_GAP_FONT_SIZE_MULTIPLE * fontSize) {
                    text.append(' ');
                }
            }
            text.append(tp.getUnicode());
            previous = tp;

            minX = Math.min(minX, tp.getXDirAdj());
            maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
            minY = Math.min(minY, tp.getYDirAdj() - tp.getHeightDir());
            maxY = Math.max(maxY, tp.getYDirAdj());
        }

        return new PdfTextLine(lineIndex, text.toString(), minX, minY, maxX - minX, maxY - minY, ambiguous);
    }

    private static boolean isBlank(String unicode) {
        return unicode == null || unicode.isBlank();
    }
}
