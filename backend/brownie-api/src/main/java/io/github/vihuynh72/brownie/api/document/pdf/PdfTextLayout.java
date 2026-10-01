package io.github.vihuynh72.brownie.api.document.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays text out in a rectangle: the one set of rules for every value
 * Brownie writes into a PDF, whether into one of the form's own fields or
 * into a box it draws. It only computes where each line goes; drawing is
 * the caller's.
 *
 * <p>The rectangle is measured upright, as the text will be read, with the
 * origin at its bottom-left and Y up (a PDF appearance's own frame). One
 * line is centred vertically. Wrapped text starts at the top, breaks at
 * spaces, and breaks a word that is wider than the whole line between its
 * letters; an explicit line break always starts a new line. A comb field
 * puts one character in the middle of each of its equal cells. Text is
 * never cut short: text that does not fit is reported as not fitting.
 *
 * <p>When the text may shrink, it is tried half a point smaller at a time
 * down to six points (or its starting size, when that is already smaller),
 * and at most {@value #MOST_SIZES_TRIED} sizes in all. Every caller starts
 * at 72 points or less, well within that; the limit is there so that no
 * starting size can keep it trying (past 2^53, taking half a point away
 * does not change the number at all).
 */
final class PdfTextLayout {

    static final double SHRINK_STEP = 0.5;
    static final double SHRINK_FLOOR = 6;
    static final int MOST_SIZES_TRIED = 200;

    /** How wide a piece of text is at a size, in points. */
    interface Measure {
        double width(String text, double sizePt);
    }

    /**
     * What to lay out. {@code padding} is kept clear inside every edge.
     * {@code combCells} is 0 unless the field is a comb. {@code alignment}
     * is a PDF field's quadding: 0 left, 1 centred, 2 right. {@code
     * ascent}, {@code descent} (negative) and {@code lineGap} are the
     * font's, as fractions of the size.
     */
    record Request(
            String text,
            double width,
            double height,
            double padding,
            boolean wrap,
            boolean centreOneLine,
            int combCells,
            int alignment,
            double startSizePt,
            boolean mayShrink,
            double ascent,
            double descent,
            double lineGap) {
    }

    /** One line of text, with the left end of its baseline, in the rectangle's own frame. */
    record Line(String text, double x, double baseline) {
    }

    /** Where each character of a comb goes: its own left edge and baseline. */
    record Placed(String character, double x, double baseline) {
    }

    /**
     * The outcome: whether the text fits, at what size, and its lines (or,
     * for a comb, its characters). When it does not fit, the lines are
     * those of the last size tried.
     */
    record Result(boolean fits, double sizePt, List<Line> lines, List<Placed> combCharacters) {

        boolean shrunk(double startSizePt) {
            return fits && sizePt < startSizePt;
        }
    }

    private PdfTextLayout() {
    }

    static Result layOut(Request request, Measure measure) {
        double floor = Math.min(SHRINK_FLOOR, request.startSizePt());
        double size = request.startSizePt();
        for (int tried = 1; ; tried++) {
            Result attempt = request.combCells() > 0 ? comb(request, measure, size) : lines(request, measure, size);
            boolean canShrink = request.mayShrink() && tried < MOST_SIZES_TRIED && size - SHRINK_STEP >= floor - 1e-9;
            if (attempt.fits() || !canShrink) {
                return attempt;
            }
            size -= SHRINK_STEP;
        }
    }

    private static Result comb(Request request, Measure measure, double size) {
        double cell = request.width() / request.combCells();
        double baseline = centredBaseline(request, size);
        List<Placed> placed = new ArrayList<>();
        boolean fits = fitsOneLineVertically(request, size);
        String text = request.text().replace('\n', ' ');
        int index = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            double width = measure.width(character, size);
            fits &= width <= cell && index < request.combCells();
            placed.add(new Placed(character, index * cell + (cell - width) / 2, baseline));
            index++;
            offset += Character.charCount(codePoint);
        }
        return new Result(fits, size, List.of(), placed);
    }

    private static Result lines(Request request, Measure measure, double size) {
        double available = request.width() - 2 * request.padding();
        List<String> texts = request.wrap()
                ? wrapped(request.text(), available, size, measure)
                : List.of(request.text().replace('\n', ' '));
        boolean fitsAcross = true;
        for (String text : texts) {
            fitsAcross &= measure.width(text, size) <= available + 1e-6;
        }
        List<Line> lines = new ArrayList<>();
        boolean fitsDown;
        if (texts.size() == 1 && (!request.wrap() || request.centreOneLine())) {
            fitsDown = fitsOneLineVertically(request, size);
            lines.add(aligned(request, measure, texts.get(0), size, centredBaseline(request, size)));
        } else {
            double advance = size * (request.ascent() - request.descent() + request.lineGap());
            double baseline = request.height() - request.padding() - size * request.ascent();
            for (String text : texts) {
                lines.add(aligned(request, measure, text, size, baseline));
                baseline -= advance;
            }
            double lastBaseline = baseline + advance;
            fitsDown = lastBaseline + size * request.descent() >= request.padding() - 1e-6;
        }
        return new Result(fitsAcross && fitsDown, size, lines, List.of());
    }

    /** One line fits from top to bottom when the size itself does, a point clear of each edge: an accent may touch the edge, a letter may not. */
    private static boolean fitsOneLineVertically(Request request, double size) {
        return size <= request.height() - 2 + 1e-6;
    }

    private static double centredBaseline(Request request, double size) {
        double textHeight = size * (request.ascent() - request.descent());
        return (request.height() - textHeight) / 2 - size * request.descent();
    }

    private static Line aligned(Request request, Measure measure, String text, double size, double baseline) {
        double width = measure.width(text, size);
        double x = switch (request.alignment()) {
            case 1 -> (request.width() - width) / 2;
            case 2 -> request.width() - request.padding() - width;
            default -> request.padding();
        };
        return new Line(text, x, baseline);
    }

    /** Greedy wrapping: as many words on each line as fit; a word wider than a whole line is broken between its letters. */
    static List<String> wrapped(String text, double available, double size, Measure measure) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            String line = "";
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (measure.width(candidate, size) <= available + 1e-6) {
                    line = candidate;
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                line = word;
                while (measure.width(line, size) > available + 1e-6 && line.codePointCount(0, line.length()) > 1) {
                    int fitting = longestFittingPrefix(line, available, size, measure);
                    lines.add(line.substring(0, fitting));
                    line = line.substring(fitting);
                }
            }
            lines.add(line);
        }
        return lines;
    }

    private static int longestFittingPrefix(String word, double available, double size, Measure measure) {
        int end = word.offsetByCodePoints(0, 1);
        int next = end;
        while (next < word.length()) {
            next = word.offsetByCodePoints(next, 1);
            if (measure.width(word.substring(0, next), size) > available + 1e-6) {
                break;
            }
            end = next;
        }
        return end;
    }
}
