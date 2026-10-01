package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/** Reads every character position on one page, under a budget, for the readers that group them themselves. */
final class PdfPageText {

    private PdfPageText() {
    }

    /**
     * Collects every character position on one page, ignoring {@link
     * PDFTextStripper}'s own per-call line/word boundaries entirely --
     * only the raw positions are trusted; {@link PdfLineGrouper} does the
     * grouping.
     */
    static List<TextPosition> characters(PDDocument document, int zeroBasedIndex, PdfReadingBudget budget)
            throws IOException {
        List<TextPosition> characters = new ArrayList<>();
        PDFTextStripper stripper = new BoundedPdfTextStripper(budget) {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                characters.addAll(textPositions);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(zeroBasedIndex + 1);
        stripper.setEndPage(zeroBasedIndex + 1);
        // A Writer is required to drive extraction at all, but its output is
        // never used -- writeString above is where real characters are captured.
        stripper.writeText(document, new OutputStreamWriter(new ByteArrayOutputStream()));
        return characters;
    }
}
