package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Groups a page's characters into lines, the one way every PDF reader here
 * does it: the source text extractor and the form reader give the same
 * line the same text. It works from raw {@link TextPosition} data rather
 * than {@code PDFTextStripper}'s own line assembly, which garbles ordinary
 * text on a page whose {@code /Rotate} is set (see {@link
 * PdfBoxStructuralExtractor}).
 */
final class PdfLineGrouper {

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
     * grouping otherwise treated as one line, wider than this multiple of
     * the current font size, is treated as a word space if it is modest,
     * or as a sign two visually separate regions were merged into one
     * line (see {@code PdfTextLine#ambiguousReadingOrder}) if it is much
     * wider still. Word-space and ambiguity thresholds are expressed as
     * two separate multiples of font size for exactly that reason.
     */
    private static final double WORD_SPACE_GAP_FONT_SIZE_MULTIPLE = 0.25;

    private static final double AMBIGUOUS_GAP_FONT_SIZE_MULTIPLE = 3.0;

    private PdfLineGrouper() {
    }

    /**
     * One grouped line. {@code characters} are in reading order, and {@code
     * spaceBefore} says, character by character, whether a space was made
     * up in front of it from a gap (no literal space character was there).
     * The box is {@code x}, {@code y}, {@code width}, {@code height}, in the
     * characters' own direction-adjusted coordinates.
     */
    record GroupedLine(
            List<TextPosition> characters,
            List<Boolean> spaceBefore,
            String text,
            double x,
            double y,
            double width,
            double height,
            boolean ambiguousReadingOrder) {
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
    static List<GroupedLine> group(List<TextPosition> characters) {
        List<TextPosition> sorted = new ArrayList<>(characters);
        sorted.sort(Comparator
                .comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));

        List<GroupedLine> lines = new ArrayList<>();
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
                lines.add(buildLine(currentLine));
                currentLine = new ArrayList<>();
                currentLine.add(tp);
                currentLineBaselineY = tp.getYDirAdj();
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(buildLine(currentLine));
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
     * simply, and what this grouping is used for is page geometry and
     * reading order, not pixel-exact glyph boxes.
     */
    private static GroupedLine buildLine(List<TextPosition> lineCharacters) {
        lineCharacters.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));

        StringBuilder text = new StringBuilder();
        List<Boolean> spaceBefore = new ArrayList<>(lineCharacters.size());
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean ambiguous = false;
        TextPosition previous = null;

        for (TextPosition tp : lineCharacters) {
            double fontSize = Math.max(tp.getFontSizeInPt(), 1f);
            boolean currentIsWhitespace = isBlank(tp.getUnicode());
            boolean synthesized = false;
            // A literal space character already provides its own
            // separation; synthesizing a gap-based space next to one too
            // would double it up. Only ever synthesize between two
            // genuinely non-whitespace characters.
            if (previous != null && !currentIsWhitespace && !isBlank(previous.getUnicode())) {
                double gap = tp.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj());
                if (gap > AMBIGUOUS_GAP_FONT_SIZE_MULTIPLE * fontSize) {
                    ambiguous = true;
                    text.append(' ');
                    synthesized = true;
                } else if (gap > WORD_SPACE_GAP_FONT_SIZE_MULTIPLE * fontSize) {
                    text.append(' ');
                    synthesized = true;
                }
            }
            text.append(tp.getUnicode());
            spaceBefore.add(synthesized);
            previous = tp;

            minX = Math.min(minX, tp.getXDirAdj());
            maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
            minY = Math.min(minY, tp.getYDirAdj() - tp.getHeightDir());
            maxY = Math.max(maxY, tp.getYDirAdj());
        }

        return new GroupedLine(List.copyOf(lineCharacters), List.copyOf(spaceBefore), text.toString(),
                minX, minY, maxX - minX, maxY - minY, ambiguous);
    }

    static boolean isBlank(String unicode) {
        return unicode == null || unicode.isBlank();
    }
}
