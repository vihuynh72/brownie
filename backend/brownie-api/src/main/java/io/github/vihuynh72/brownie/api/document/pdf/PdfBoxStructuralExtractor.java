package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfParseException;
import io.github.vihuynh72.brownie.core.document.PdfStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a PDF's page geometry and text using Apache PDFBox, building line
 * groupings and bounding boxes directly from raw {@link TextPosition} data
 * rather than {@link PDFTextStripper}'s own default line/word assembly.
 * That default assembly was tried first and empirically confirmed (via a
 * throwaway exploration script, not assumed) to garble ordinary,
 * unrotated-looking text on a page whose {@code /Rotate} entry is set --
 * splitting single words mid-character across separate "lines" -- because
 * it tries to account for the page's viewing rotation when deciding line
 * breaks and gets it wrong for this common case. Raw {@code TextPosition}
 * coordinates, in contrast, are themselves completely unaffected by a
 * page's {@code /Rotate} value (confirmed the same way: identical
 * positions reported for the same content stream regardless of the
 * page's declared rotation), so building line/word grouping directly from
 * them side-steps the bug entirely.
 */
public final class PdfBoxStructuralExtractor implements PdfStructuralExtractor {

    /**
     * Names both this extractor's own graph shape and the PDFBox version
     * it depends on, the same reasoning {@code PoiDocxStructuralExtractor}
     * already uses for its own parser version.
     */
    static final String PARSER_VERSION = "brownie-pdf-graph-v1+pdfbox-3.0.8";

    /**
     * Two characters on the same visual line rarely land at the exact same
     * baseline Y -- font metrics, kerning, and rounding all introduce
     * small jitter. A new line starts once a character's Y differs from
     * the line's own baseline by more than this fraction of that
     * character's own height; small enough to keep genuinely distinct
     * lines apart, generous enough to absorb ordinary sub-pixel jitter
     * within one line.
     */
    private static final double LINE_Y_TOLERANCE_FRACTION = 0.4;

    /**
     * A horizontal gap between two consecutive characters on what this
     * extractor otherwise treated as one line, wider than this multiple of
     * the current font size, is treated as a word space if it is modest,
     * or as a sign two visually separate regions were merged into one
     * line (see {@link PdfTextLine#ambiguousReadingOrder}) if it is much
     * wider still. Word-space and ambiguity thresholds are expressed as
     * two separate multiples of font size for exactly that reason.
     */
    private static final double WORD_SPACE_GAP_FONT_SIZE_MULTIPLE = 0.25;

    private static final double AMBIGUOUS_GAP_FONT_SIZE_MULTIPLE = 3.0;

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    @Override
    public PdfExtractionOutcome extract(InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        PDDocument document;
        try {
            document = Loader.loadPDF(bytes);
        } catch (InvalidPasswordException e) {
            return new PdfExtractionOutcome.Unsupported(
                    UnsupportedPdfReason.ENCRYPTED, "The PDF is password-protected; no password was supplied.");
        } catch (IOException e) {
            throw new PdfParseException("Could not parse the package as a PDF document.", e);
        }

        try (document) {
            List<PdfPage> pages = new ArrayList<>();
            boolean anyPageHasText = false;
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PdfPage page = extractPage(document, index);
                pages.add(page);
                anyPageHasText = anyPageHasText || page.hasExtractableText();
            }

            if (!anyPageHasText) {
                return new PdfExtractionOutcome.Unsupported(
                        UnsupportedPdfReason.NO_EXTRACTABLE_TEXT,
                        "No page in this " + pages.size() + "-page document produced any extractable text.");
            }
            return new PdfExtractionOutcome.Supported(new PdfStructuralGraph(PARSER_VERSION, List.copyOf(pages)));
        } catch (RuntimeException e) {
            throw new PdfParseException("Could not read this PDF's content.", e);
        }
    }

    private PdfPage extractPage(PDDocument document, int zeroBasedIndex) throws IOException {
        PDPage page = document.getPage(zeroBasedIndex);
        PDRectangle cropBox = page.getCropBox();
        int rotation = page.getRotation();

        List<TextPosition> characters = collectCharacters(document, zeroBasedIndex);
        List<PdfTextLine> lines = groupIntoLines(characters);

        return new PdfPage(zeroBasedIndex + 1, cropBox.getWidth(), cropBox.getHeight(), rotation, !lines.isEmpty(), lines);
    }

    /**
     * Collects every character position on one page, ignoring {@link
     * PDFTextStripper}'s own per-call line/word boundaries entirely --
     * only the raw positions are trusted; this extractor does its own
     * grouping in {@link #groupIntoLines}.
     */
    private List<TextPosition> collectCharacters(PDDocument document, int zeroBasedIndex) throws IOException {
        List<TextPosition> characters = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
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
        stripper.writeText(document, new java.io.OutputStreamWriter(new ByteArrayOutputStream()));
        return characters;
    }

    /**
     * Sorts characters top-to-bottom then left-to-right using their own
     * "direction adjusted" coordinates -- top-left origin, Y increasing
     * downward, confirmed empirically to already be in that orientation
     * rather than raw bottom-left-origin PDF space -- then clusters them
     * into lines by Y proximity. Within a line, a wide horizontal gap
     * between consecutive characters becomes either a synthesized space
     * (PDF text is frequently spaced using position adjustments rather
     * than literal space characters, so a gap-based space is necessary,
     * not optional) or, if wide enough, a signal the line itself merges
     * two visually separate regions.
     */
    private List<PdfTextLine> groupIntoLines(List<TextPosition> characters) {
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
     * simply, and this task asks for page geometry and reading order, not
     * pixel-exact glyph boxes.
     */
    private PdfTextLine buildLine(int lineIndex, List<TextPosition> lineCharacters) {
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
