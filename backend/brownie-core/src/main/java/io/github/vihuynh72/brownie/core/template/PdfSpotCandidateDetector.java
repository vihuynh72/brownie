package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the places on a PDF's pages that look like blanks to write in, from
 * the words and shapes a {@link PdfFormGraph} records, and places a box
 * where a person points. Everything here is a guess from geometry; nothing
 * reads what a label means. Four shapes are looked for:
 *
 * <ol>
 *   <li>a run of at least four underscores, or at least six dots;</li>
 *   <li>a label ending in a colon with at least 72 points of empty space
 *   after it, up to the next text or the page's text margin;</li>
 *   <li>a drawn horizontal line at least 36 points long with nothing
 *   written just above it (a line under words is an underline, and a line
 *   as wide as the page is a separator, not a blank);</li>
 *   <li>a drawn rectangle at least 36 by 12 points with nothing inside it
 *   (an empty table cell).</li>
 * </ol>
 *
 * <p>The same place found twice (a line under a label's empty space, dots
 * inside a cell) is kept once, from the most exact of the shapes. Two
 * places that only share an edge's worth (two blank lines set closer than
 * a line's height apart) are both kept, the later made shorter so they no
 * longer overlap, since a template's places may not cover one another. A
 * place that overlaps a field the PDF already has is dropped, because the
 * form fills that itself. At most 200 are returned, in reading order.
 *
 * <p>Only what the page shows is looked at: words, lines and rectangles
 * outside the crop box (a part of the page cut away) are not.
 *
 * <p>A place is named by the words just before it on its row, or the short
 * line just above it. Boxes drawn edge to edge make a grid (a table drawn
 * cell by cell): an empty cell of a grid is named by its column's header,
 * the words just above the grid in that column or in the grid's own first
 * row, with its row added when several rows have a place in that column
 * ({@link TableCellLabels}), never by the words in the cell beside it,
 * which are one of the table's values. When nothing names a place, the
 * nearest words to its left, then a short line further above, do.
 *
 * <p>Each page is read in the direction most of its text runs, so a page
 * stored sideways and turned upright by {@code /Rotate}, or a page whose
 * text is set sideways, is searched the way it reads. A page with no text
 * (a scan) yields nothing: there is nothing to go on, and a person draws
 * the boxes.
 */
public final class PdfSpotCandidateDetector {

    public static final int MAX_CANDIDATES = 200;
    static final int MIN_UNDERSCORES = 4;
    static final int MIN_DOTS = 6;
    static final double MIN_LABEL_SPACE = 72;
    static final double MIN_RULE_LENGTH = 36;
    static final double MIN_BOX_WIDTH = 36;
    static final double MIN_BOX_HEIGHT = 12;
    /** Text this close above a line means the line underlines it. */
    static final double RULE_CLEARANCE = 2;
    /** The shortest box for one line of text: what {@link #lineHeightFor} gives the smallest type, and what a shortened place keeps. */
    static final double MIN_LINE_HEIGHT = 10;
    /** The width of a box placed where a person clicked with no label beside it: two inches. */
    static final double POINTED_BOX_WIDTH = 144;

    private static final double LABEL_GAP = 4;
    /** A line wider than this share of the page is a separator between parts of the page, not a line to write on. */
    private static final double SEPARATOR_SHARE_OF_WIDTH = 0.85;
    /** A rectangle taller than this share of the page is a frame around content, not a box to write in. */
    private static final double FRAME_SHARE_OF_HEIGHT = 0.4;
    private static final int MAX_CONTEXT_CHARACTERS = 80;
    private static final int MAX_LABEL_CHARACTERS = 60;
    private static final int SHORT_LINE_CHARACTERS = 40;
    private static final Pattern SIGNATURE_WORDS =
            Pattern.compile("\\b(signature|sign here|signed|initials)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OFFICE_USE =
            Pattern.compile("\\b(office|official|internal|admin|administrative|staff)\\s+use\\b", Pattern.CASE_INSENSITIVE);
    /** Column headers that only say "the answer goes here": the label beside the cell names it better. */
    private static final Set<String> ANSWER_HEADERS = Set.of(
            "answer", "answers", "your answer", "response", "value", "details", "entry", "input", "information", "reply");
    /** A header that tells the person what to do ("Please complete", "Write here") names no value either. */
    private static final Pattern INSTRUCTION_HEADER = Pattern.compile(
            "(?i)(please|kindly|enter|write|insert|provide|fill|complete)\\b.*");
    /** Two boxes whose edges are this close share the edge: they are cells of one table. */
    private static final double SHARED_EDGE = 2;
    /** How many lines above a place a short line may be and still name it, when nothing closer does. */
    private static final double FARTHEST_LINE_ABOVE = 3;

    private PdfSpotCandidateDetector() {
    }

    public static List<PdfSpotCandidate> detect(PdfFormGraph graph) {
        List<Found> found = new ArrayList<>();
        for (PdfFormGraph.Page page : graph.pages()) {
            if (!page.hasText()) {
                continue;
            }
            Frame frame = Frame.of(page, widgetsOn(graph, page.pageNumber()));
            List<Found> onPage = new ArrayList<>();
            findLeaders(frame, onPage);
            findLabelSpaces(frame, onPage);
            findRules(frame, onPage);
            findEmptyBoxes(frame, onPage);
            List<Found> kept = keepDistinct(frame, onPage);
            placeInGrids(frame, kept);
            found.addAll(kept);
        }
        found.sort(Comparator.comparingInt((Found f) -> f.frame.page.pageNumber())
                .thenComparingDouble(f -> f.box.y())
                .thenComparingDouble(f -> f.box.x()));

        List<PdfSpotCandidate> candidates = new ArrayList<>();
        for (Found place : found) {
            if (candidates.size() == MAX_CANDIDATES) {
                break;
            }
            candidates.add(place.toCandidate("c" + (candidates.size() + 1)));
        }
        return candidates;
    }

    /**
     * A box for a person who pointed at {@code point} ({@link PdfRect}
     * convention) on a page. Just right of a label on the same line, the
     * box runs from there to the next text or the page's text margin and is
     * one line tall; anywhere else it is two inches wide at the point.
     */
    public static PdfBoxSuggestion boxSuggestion(PdfFormGraph graph, int pageNumber, PdfPoint point) {
        PdfFormGraph.Page page = pageOf(graph, pageNumber);
        Frame frame = Frame.of(page, widgetsOn(graph, pageNumber));
        PdfPoint at = PdfBoxGeometry.toDisplayed(point, page.cropBox(), frame.rotation);
        Band band = frame.bandAt(at.y());
        if (band != null) {
            FrameWord before = band.lastWordEndingBefore(at.x());
            if (before != null) {
                PdfRect row = frame.rowOf(band);
                double left = Math.max(at.x(), before.box.right() + LABEL_GAP);
                PdfRect box = frame.spaceRightOf(left, row, before.box.right());
                if (box != null && box.width() >= 8) {
                    return frame.suggestion(box, row);
                }
            }
        }
        double height = lineHeightFor(PdfTextStyle.DEFAULT.sizePt());
        double width = Math.min(POINTED_BOX_WIDTH, frame.width);
        double x = clamp(at.x(), 0, frame.width - width);
        double y = clamp(at.y() - height / 2, 0, frame.height - height);
        PdfRect box = new PdfRect(x, y, width, height);
        return frame.suggestion(box, box);
    }

    /**
     * A box for a person who chose a whole line: from the end of the line
     * to the next text or the page's text margin, one line tall, or, when
     * the line already runs to the margin, two inches wide just below it.
     */
    public static PdfBoxSuggestion boxSuggestion(PdfFormGraph graph, int pageNumber, int lineIndex) {
        PdfFormGraph.Page page = pageOf(graph, pageNumber);
        if (lineIndex < 0 || lineIndex >= page.lines().size()) {
            throw new IllegalArgumentException("Page " + pageNumber + " has no line " + lineIndex + ".");
        }
        Frame frame = Frame.of(page, widgetsOn(graph, pageNumber));
        Band band = frame.bandOfLine(lineIndex);
        PdfRect lineBox = band == null
                ? PdfBoxGeometry.toDisplayed(page.lines().get(lineIndex).box(), page.cropBox(), frame.rotation)
                : frame.rowOf(band);
        PdfRect beside = frame.spaceRightOf(lineBox.right() + LABEL_GAP, lineBox, lineBox.right());
        if (beside != null && beside.width() >= MIN_BOX_WIDTH) {
            return frame.suggestion(beside, lineBox);
        }
        double height = lineHeightFor(PdfTextStyle.DEFAULT.sizePt());
        double width = Math.min(POINTED_BOX_WIDTH, frame.width);
        PdfRect below = new PdfRect(
                clamp(lineBox.x(), 0, frame.width - width), clamp(lineBox.bottom() + 2, 0, frame.height - height), width, height);
        return frame.suggestion(below, lineBox);
    }

    /**
     * A box for the place just after the first {@code endOffset} code
     * points of line {@code lineIndex}, as when a person names the words a
     * value goes after ("after Company:"): over the blank that follows them
     * when the next word is one (underscores or dots), otherwise in the
     * empty space after them up to the next text or the page's text margin,
     * one line tall. When there is no room there, or the words are not
     * found among the line's words, it is the box for the whole line.
     */
    public static PdfBoxSuggestion boxSuggestionAfter(PdfFormGraph graph, int pageNumber, int lineIndex, int endOffset) {
        PdfFormGraph.Page page = pageOf(graph, pageNumber);
        if (lineIndex < 0 || lineIndex >= page.lines().size()) {
            throw new IllegalArgumentException("Page " + pageNumber + " has no line " + lineIndex + ".");
        }
        PdfFormGraph.Line line = page.lines().get(lineIndex);
        String text = line.text();
        int end = text.offsetByCodePoints(0, Math.max(0, Math.min(endOffset, text.codePointCount(0, text.length()))));
        // The line's text is its words in order, so each word is found after the one before it.
        PdfFormGraph.Word last = null;
        PdfFormGraph.Word next = null;
        int from = 0;
        for (PdfFormGraph.Word word : line.words()) {
            int at = word.text().isEmpty() ? -1 : text.indexOf(word.text(), from);
            if (at < 0) {
                continue;
            }
            from = at + word.text().length();
            if (at < end) {
                last = word;
            } else if (next == null) {
                next = word;
            }
        }
        Frame frame = Frame.of(page, widgetsOn(graph, pageNumber));
        Band band = frame.bandOfLine(lineIndex);
        PdfRect row = band == null ? frame.turn(line.box()) : frame.rowOf(band);
        if (next != null && leaderKind(next.text()) != null) {
            PdfRect blank = frame.turn(next.box());
            double height = lineHeightFor(next.fontSizePt());
            // As for a blank found on its own: the value is written on it, so the box sits on its baseline.
            return frame.suggestion(new PdfRect(blank.x(), blank.bottom() - height, blank.width(), height), row);
        }
        if (last != null) {
            PdfRect word = frame.turn(last.box());
            PdfRect space = frame.spaceRightOf(word.right() + LABEL_GAP, row, word.right());
            if (space != null && space.width() >= MIN_BOX_WIDTH) {
                return frame.suggestion(space, row);
            }
        }
        return boxSuggestion(graph, pageNumber, lineIndex);
    }

    // ---- the four shapes ----------------------------------------------------------------------------------------

    private static void findLeaders(Frame frame, List<Found> found) {
        for (FrameWord word : frame.readingWords) {
            Kind kind = leaderKind(word.text);
            if (kind == null) {
                continue;
            }
            double height = lineHeightFor(word.sizePt);
            // The blank is written on: its baseline is the bottom of the run, and the text sits above it.
            PdfRect box = new PdfRect(word.box.x(), word.box.bottom() - height, word.box.width(), height);
            found.add(new Found(frame, kind == Kind.UNDERSCORES
                    ? PdfSpotCandidate.Kind.UNDERSCORES : PdfSpotCandidate.Kind.DOT_LEADER, box, word.box));
        }
    }

    private static void findLabelSpaces(Frame frame, List<Found> found) {
        for (FrameWord word : frame.readingWords) {
            if (!isLabel(word.text)) {
                continue;
            }
            double height = lineHeightFor(word.sizePt);
            PdfRect band = new PdfRect(word.box.x(), word.box.centerY() - height / 2, word.box.width(), height);
            PdfRect space = frame.spaceRightOf(word.box.right() + LABEL_GAP, band, word.box.right());
            // A label whose row goes on to one of the form's own fields is that field's label, not a blank of its own.
            boolean fieldFollows = frame.widgets.stream()
                    .anyMatch(widget -> widget.x() > word.box.right() && Frame.overlapsVertically(widget, band));
            if (space != null && space.width() >= MIN_LABEL_SPACE && !fieldFollows) {
                found.add(new Found(frame, PdfSpotCandidate.Kind.LABEL_SPACE, space, band));
            }
        }
    }

    private static void findRules(Frame frame, List<Found> found) {
        for (Segment rule : frame.horizontalRules()) {
            if (rule.length() < MIN_RULE_LENGTH || rule.length() > SEPARATOR_SHARE_OF_WIDTH * frame.width) {
                continue;
            }
            for (Segment piece : frame.splitByVerticalRules(rule)) {
                if (piece.length() < MIN_RULE_LENGTH) {
                    continue;
                }
                PdfRect beside = frame.bandLeftOf(piece.from, piece.at);
                double size = beside == null ? PdfTextStyle.DEFAULT.sizePt() : frame.styleOf(frame.wordsIn(beside)).sizePt();
                double height = lineHeightFor(size);
                PdfRect box = new PdfRect(piece.from, piece.at - height, piece.to - piece.from, height);
                PdfRect justAbove = new PdfRect(piece.from, piece.at - height, piece.to - piece.from, height - 0.25);
                if (frame.anyWordOverlaps(justAbove, RULE_CLEARANCE)) {
                    continue;
                }
                found.add(new Found(frame, PdfSpotCandidate.Kind.RULE, box, beside == null ? box : beside));
            }
        }
    }

    private static void findEmptyBoxes(Frame frame, List<Found> found) {
        for (PdfRect rect : frame.rects) {
            if (!frame.wholePage().encloses(rect, 0.5)) {
                continue;
            }
            if (rect.width() < MIN_BOX_WIDTH || rect.height() < MIN_BOX_HEIGHT
                    || rect.height() > FRAME_SHARE_OF_HEIGHT * frame.height || rect.width() > 0.95 * frame.width) {
                continue;
            }
            PdfRect inside = rect.grownBy(-1);
            boolean holdsWords = frame.allWords.stream().anyMatch(word -> inside.contains(word.box.centerX(), word.box.centerY()));
            boolean holdsBoxes = frame.rects.stream().anyMatch(other -> other != rect
                    && other.area() < rect.area() && inside.contains(other.centerX(), other.centerY()));
            if (holdsWords || holdsBoxes) {
                continue;
            }
            PdfRect box = rect.grownBy(-1.5);
            found.add(new Found(frame, PdfSpotCandidate.Kind.EMPTY_BOX, box, box, rect));
        }
    }

    // ---- tables drawn as boxes ---------------------------------------------------------------------------------

    /** Where a found box sits in a grid, and what the grid says about it. */
    private record GridPlace(String gridKey, String header, String rowText, int rowNumber, boolean severalRows, List<String> values) {
    }

    /**
     * Finds the grids among a page's boxes (two or more boxes, each sharing
     * a whole edge with another) and gives each found empty cell of one its
     * place in it. A grid's first row is its header row when every cell of
     * it holds words, the first cell excepted (the empty corner of a table
     * with its rows named down the side); otherwise each column's header is
     * the line of words just above the grid in that column, and every row is
     * a row of values. Only a place in a row of values belongs to its grid:
     * an empty box in the header row is not one of the cells to fill in it.
     */
    private static void placeInGrids(Frame frame, List<Found> kept) {
        List<PdfRect> cells = new ArrayList<>();
        for (PdfRect rect : frame.rects) {
            if (cellSized(frame, rect) && !holdsBoxes(frame, rect) && !cells.contains(rect)) {
                cells.add(rect);
            }
        }
        int[] parent = new int[cells.size()];
        for (int index = 0; index < parent.length; index++) {
            parent[index] = index;
        }
        for (int first = 0; first < cells.size(); first++) {
            for (int second = first + 1; second < cells.size(); second++) {
                if (shareAnEdge(cells.get(first), cells.get(second))) {
                    parent[root(parent, first)] = root(parent, second);
                }
            }
        }
        Map<Integer, List<PdfRect>> grids = new LinkedHashMap<>();
        for (int index = 0; index < cells.size(); index++) {
            grids.computeIfAbsent(root(parent, index), key -> new ArrayList<>()).add(cells.get(index));
        }
        List<List<PdfRect>> ordered = new ArrayList<>(grids.values().stream().filter(grid -> grid.size() > 1).toList());
        ordered.sort(Comparator.comparingDouble((List<PdfRect> grid) -> grid.stream().mapToDouble(PdfRect::y).min().orElse(0))
                .thenComparingDouble(grid -> grid.stream().mapToDouble(PdfRect::x).min().orElse(0)));
        for (int number = 0; number < ordered.size(); number++) {
            placeInGrid(frame, kept, ordered.get(number), "P" + frame.page.pageNumber() + "G" + (number + 1));
        }
    }

    private static void placeInGrid(Frame frame, List<Found> kept, List<PdfRect> grid, String gridKey) {
        List<Double> columns = clusters(grid.stream().map(PdfRect::x).toList());
        List<Double> rows = clusters(grid.stream().map(PdfRect::y).toList());
        List<PdfRect> topRow = grid.stream().filter(cell -> indexOf(rows, cell.y()) == 0).toList();
        boolean headerRow = rows.size() > 1
                && topRow.stream().filter(cell -> indexOf(columns, cell.x()) > 0).allMatch(cell -> !frame.textIn(cell).isEmpty())
                && topRow.stream().anyMatch(cell -> !frame.textIn(cell).isEmpty());
        double top = grid.stream().mapToDouble(PdfRect::y).min().orElse(0);

        Map<Integer, String> headers = new HashMap<>();
        for (PdfRect cell : grid) {
            int column = indexOf(columns, cell.x());
            if (headers.containsKey(column)) {
                continue;
            }
            String header = headerRow
                    ? (indexOf(rows, cell.y()) == 0 ? labelText(frame.textIn(cell)) : null)
                    : labelText(frame.lineAboveIn(cell.x(), cell.right(), top));
            if (header != null) {
                headers.put(column, header);
            }
        }
        int firstValueRow = headerRow ? 1 : 0;
        List<String> values = new ArrayList<>();
        if (!headers.isEmpty()) {
            for (PdfRect cell : grid) {
                String text = frame.textIn(cell);
                if (indexOf(rows, cell.y()) >= firstValueRow && !text.isEmpty() && !text.endsWith(":")
                        && text.codePointCount(0, text.length()) <= MAX_LABEL_CHARACTERS && !values.contains(text)) {
                    values.add(text);
                }
            }
        }
        Map<Integer, Integer> placesInColumn = new HashMap<>();
        List<Found> inGrid = kept.stream().filter(place -> place.cell != null && grid.contains(place.cell)).toList();
        for (Found place : inGrid) {
            if (indexOf(rows, place.cell.y()) >= firstValueRow) {
                placesInColumn.merge(indexOf(columns, place.cell.x()), 1, Integer::sum);
            }
        }
        for (Found place : inGrid) {
            int column = indexOf(columns, place.cell.x());
            int row = indexOf(rows, place.cell.y());
            if (row < firstValueRow) {
                continue;
            }
            String header = headers.get(column);
            String rowText = column > 0 ? labelText(frame.textIn(cellAt(grid, columns, rows, 0, row))) : null;
            String beside = column > 0 ? frame.textIn(cellAt(grid, columns, rows, column - 1, row)) : "";
            boolean answerHeader = header != null && (ANSWER_HEADERS.contains(header.toLowerCase(Locale.ROOT))
                    || INSTRUCTION_HEADER.matcher(header).matches());
            if (!beside.isEmpty() && (beside.endsWith(":") || answerHeader)) {
                // A label beside the cell names it ("Phone:"), as the words before any blank do.
                header = null;
            }
            place.grid = new GridPlace(gridKey, header, rowText, row - firstValueRow + 1,
                    placesInColumn.getOrDefault(column, 0) > 1, header == null ? List.of() : values);
        }
    }

    /** The grid's cell in that column and row, or null when the grid has none there (a merged cell). */
    private static PdfRect cellAt(List<PdfRect> grid, List<Double> columns, List<Double> rows, int column, int row) {
        return grid.stream()
                .filter(cell -> indexOf(columns, cell.x()) == column && indexOf(rows, cell.y()) == row)
                .findFirst().orElse(null);
    }

    /** A box the size of a cell to write in: what {@link #findEmptyBoxes} would look in, empty or not. */
    private static boolean cellSized(Frame frame, PdfRect rect) {
        return frame.wholePage().encloses(rect, 0.5) && rect.width() >= MIN_BOX_WIDTH && rect.height() >= MIN_BOX_HEIGHT
                && rect.height() <= FRAME_SHARE_OF_HEIGHT * frame.height && rect.width() <= 0.95 * frame.width;
    }

    private static boolean holdsBoxes(Frame frame, PdfRect rect) {
        PdfRect inside = rect.grownBy(-1);
        return frame.rects.stream()
                .anyMatch(other -> other != rect && other.area() < rect.area() && inside.contains(other.centerX(), other.centerY()));
    }

    /** Side by side in one row (same top, same height) or one above the other in one column (same left, same width), touching. */
    private static boolean shareAnEdge(PdfRect first, PdfRect second) {
        boolean sameRow = Math.abs(first.y() - second.y()) <= SHARED_EDGE && Math.abs(first.height() - second.height()) <= SHARED_EDGE
                && (Math.abs(first.right() - second.x()) <= SHARED_EDGE || Math.abs(second.right() - first.x()) <= SHARED_EDGE);
        boolean sameColumn = Math.abs(first.x() - second.x()) <= SHARED_EDGE && Math.abs(first.width() - second.width()) <= SHARED_EDGE
                && (Math.abs(first.bottom() - second.y()) <= SHARED_EDGE || Math.abs(second.bottom() - first.y()) <= SHARED_EDGE);
        return sameRow || sameColumn;
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    /** Positions grouped where they are within a shared edge of each other, in order: a table's column lefts or row tops. */
    private static List<Double> clusters(List<Double> positions) {
        List<Double> sorted = new ArrayList<>(positions);
        sorted.sort(Double::compare);
        List<Double> clusters = new ArrayList<>();
        for (double position : sorted) {
            if (clusters.isEmpty() || position - clusters.get(clusters.size() - 1) > SHARED_EDGE) {
                clusters.add(position);
            }
        }
        return clusters;
    }

    private static int indexOf(List<Double> clusters, double position) {
        int best = 0;
        for (int index = 0; index < clusters.size(); index++) {
            if (Math.abs(clusters.get(index) - position) < Math.abs(clusters.get(best) - position)) {
                best = index;
            }
        }
        return best;
    }

    /** Words as a label: a colon at the end taken off, and nothing longer than a short line; null when there is no such label. */
    private static String labelText(String words) {
        String text = words == null ? "" : words.strip();
        while (text.endsWith(":")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (text.isEmpty() || text.codePointCount(0, text.length()) > SHORT_LINE_CHARACTERS) {
            return null;
        }
        return FieldIds.normalizeLabel(text);
    }

    /**
     * The most exact shape wins where two found the same place: a run of
     * underscores or dots is exactly where the form wants writing, a box is
     * exactly its cell, a line is exactly its line, and space after a label
     * is only an estimate. A place over one of the form's own fields goes.
     */
    private static List<Found> keepDistinct(Frame frame, List<Found> found) {
        List<Found> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt((Found f) -> precedence(f.kind))
                .thenComparingDouble(f -> f.box.y())
                .thenComparingDouble(f -> f.box.x()));
        List<Found> kept = new ArrayList<>();
        for (Found candidate : ordered) {
            boolean overWidget = frame.widgets.stream().anyMatch(widget -> widget.overlapArea(candidate.box) > 1);
            boolean duplicate = kept.stream().anyMatch(other -> {
                double smaller = Math.min(other.box.area(), candidate.box.area());
                return smaller > 0 && other.box.overlapArea(candidate.box) > 0.5 * smaller;
            });
            Found clear = overWidget || duplicate ? null : clearOf(candidate, kept);
            if (clear != null) {
                kept.add(clear);
            }
        }
        return kept;
    }

    /**
     * The place made shorter, top or bottom, until it covers none of those
     * kept before it the way a template's check counts covering ({@link
     * TemplateBindingValidator#covers}); null when that leaves less than a
     * line's height, or when the overlap is across rather than up and down.
     */
    private static Found clearOf(Found candidate, List<Found> kept) {
        PdfRect box = candidate.box;
        for (Found other : kept) {
            if (!TemplateBindingValidator.covers(box, other.box)) {
                continue;
            }
            box = box.centerY() >= other.box.centerY()
                    ? new PdfRect(box.x(), other.box.bottom(), box.width(), box.bottom() - other.box.bottom())
                    : new PdfRect(box.x(), box.y(), box.width(), other.box.y() - box.y());
            if (box.height() < MIN_LINE_HEIGHT) {
                return null;
            }
        }
        for (Found other : kept) {
            if (TemplateBindingValidator.covers(box, other.box)) {
                return null;
            }
        }
        return box == candidate.box ? candidate : new Found(candidate.frame, candidate.kind, box, candidate.labelBand, candidate.cell);
    }

    private static int precedence(PdfSpotCandidate.Kind kind) {
        return switch (kind) {
            case UNDERSCORES -> 0;
            case DOT_LEADER -> 1;
            case EMPTY_BOX -> 2;
            case RULE -> 3;
            case LABEL_SPACE -> 4;
        };
    }

    // ---- words and styles -----------------------------------------------------------------------------------------

    private enum Kind { UNDERSCORES, DOTS }

    /** The reader keeps each run of underscores or dots as a word of its own, so a leader is a word made of nothing else. */
    private static Kind leaderKind(String text) {
        int underscores = 0;
        int dots = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '_' || character == '\uFF3F') {
                underscores++;
            } else if (character == '.') {
                dots++;
            } else if (character == '\u2026') {
                dots += 3;
            } else {
                return null;
            }
        }
        if (underscores >= MIN_UNDERSCORES && dots == 0) {
            return Kind.UNDERSCORES;
        }
        return dots >= MIN_DOTS && underscores == 0 ? Kind.DOTS : null;
    }

    private static boolean isLabel(String text) {
        return text.length() >= 2 && text.endsWith(":") && text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /** One line of text at a size, with a little room above and below: what a box for one line needs. */
    static double lineHeightFor(double sizePt) {
        return clamp(sizePt * 1.4, MIN_LINE_HEIGHT, 28);
    }

    /**
     * A text size kept to what a template's box may have (4 to 72 points),
     * so that a place found beside fine print, or a box drawn beside it, can
     * be sent back as it came and be taken.
     */
    static double boxTextSize(double sizePt) {
        return clamp(sizePt, TemplateDerivationService.MIN_BOX_TEXT_SIZE, TemplateDerivationService.MAX_BOX_TEXT_SIZE);
    }

    /** {@code value} kept between {@code low} and {@code high}; {@code low} when there is no room at all. */
    private static double clamp(double value, double low, double high) {
        return high < low ? low : Math.max(low, Math.min(high, value));
    }

    private static List<PdfRect> widgetsOn(PdfFormGraph graph, int pageNumber) {
        List<PdfRect> widgets = new ArrayList<>();
        if (graph.acroForm() == null) {
            return widgets;
        }
        for (PdfFormGraph.Field field : graph.acroForm().fields()) {
            for (PdfFormGraph.Widget widget : field.widgets()) {
                if (widget.pageNumber() == pageNumber) {
                    widgets.add(widget.box());
                }
            }
        }
        return widgets;
    }

    private static PdfFormGraph.Page pageOf(PdfFormGraph graph, int pageNumber) {
        for (PdfFormGraph.Page page : graph.pages()) {
            if (page.pageNumber() == pageNumber) {
                return page;
            }
        }
        throw new IllegalArgumentException("The document has no page " + pageNumber + ".");
    }

    // ---- one page, turned so that its text reads left to right ----------------------------------------------------

    private record FrameWord(String text, PdfRect box, String fontName, double sizePt, int lineIndex, boolean reading) {
    }

    private record Segment(double from, double to, double at) {

        double length() {
            return to - from;
        }
    }

    /** One line of reading text: its box and its words in reading order. */
    private record Band(int lineIndex, PdfRect box, List<FrameWord> words) {

        FrameWord lastWordEndingBefore(double x) {
            FrameWord last = null;
            for (FrameWord word : words) {
                if (word.box.right() <= x + 1) {
                    last = word;
                }
            }
            return last;
        }
    }

    private static final class Frame {

        final PdfFormGraph.Page page;
        final int rotation;
        final double width;
        final double height;
        final List<FrameWord> allWords = new ArrayList<>();
        final List<FrameWord> readingWords = new ArrayList<>();
        final List<Band> bands = new ArrayList<>();
        final List<PdfRect> rects = new ArrayList<>();
        final List<PdfRect> widgets = new ArrayList<>();
        final List<Segment> horizontal = new ArrayList<>();
        final List<Segment> vertical = new ArrayList<>();
        double textLeft = Double.MAX_VALUE;
        double textRight = 0;

        private Frame(PdfFormGraph.Page page, int rotation) {
            this.page = page;
            this.rotation = rotation;
            this.width = PdfBoxGeometry.displayedWidth(page.cropBox(), rotation);
            this.height = PdfBoxGeometry.displayedHeight(page.cropBox(), rotation);
        }

        static Frame of(PdfFormGraph.Page page, List<PdfRect> widgetBoxes) {
            Frame frame = new Frame(page, readingDirection(page));
            PdfFormGraph.CropBox crop = page.cropBox();
            for (PdfFormGraph.Line line : page.lines()) {
                List<FrameWord> reading = new ArrayList<>();
                for (PdfFormGraph.Word word : line.words()) {
                    boolean isReading = Math.floorMod(word.textDirection(), 360) == frame.rotation;
                    FrameWord turned = new FrameWord(word.text(), frame.turn(word.box()), word.fontName(), word.fontSizePt(),
                            line.index(), isReading);
                    if (!frame.wholePage().contains(turned.box.centerX(), turned.box.centerY())) {
                        continue;
                    }
                    frame.allWords.add(turned);
                    if (isReading && !word.text().isBlank()) {
                        reading.add(turned);
                        frame.readingWords.add(turned);
                    }
                }
                if (!reading.isEmpty()) {
                    reading.sort(Comparator.comparingDouble(word -> word.box.x()));
                    PdfRect box = reading.get(0).box;
                    for (FrameWord word : reading) {
                        box = box.union(word.box);
                    }
                    frame.bands.add(new Band(line.index(), box, List.copyOf(reading)));
                    frame.textLeft = Math.min(frame.textLeft, box.x());
                    frame.textRight = Math.max(frame.textRight, box.right());
                }
            }
            for (PdfRect rect : page.rects()) {
                PdfRect turned = frame.turn(rect);
                if (frame.wholePage().overlapArea(turned) > 0) {
                    frame.rects.add(turned);
                }
            }
            for (PdfRect widget : widgetBoxes) {
                frame.widgets.add(frame.turn(widget));
            }
            for (PdfFormGraph.Rule rule : page.rules()) {
                PdfPoint from = PdfBoxGeometry.toDisplayed(rule.from(), crop, frame.rotation);
                PdfPoint to = PdfBoxGeometry.toDisplayed(rule.to(), crop, frame.rotation);
                if (Math.abs(to.y() - from.y()) <= 0.75) {
                    frame.addClipped(frame.horizontal, from.x(), to.x(), (from.y() + to.y()) / 2, frame.width, frame.height);
                } else if (Math.abs(to.x() - from.x()) <= 0.75) {
                    frame.addClipped(frame.vertical, from.y(), to.y(), (from.x() + to.x()) / 2, frame.height, frame.width);
                }
            }
            return frame;
        }

        /**
         * The direction most of the page's characters run in; with no text,
         * the page's own rotation, so that a box drawn on a scan reads
         * upright. A tie goes to the page's rotation, then to the smaller
         * angle.
         */
        private static int readingDirection(PdfFormGraph.Page page) {
            Map<Integer, Integer> characters = new HashMap<>();
            for (PdfFormGraph.Line line : page.lines()) {
                for (PdfFormGraph.Word word : line.words()) {
                    characters.merge(Math.floorMod(word.textDirection(), 360), word.text().length(), Integer::sum);
                }
            }
            int pageRotation = Math.floorMod(page.rotation(), 360);
            int best = pageRotation;
            int most = characters.getOrDefault(pageRotation, 0);
            for (int direction : new int[] {0, 90, 180, 270}) {
                int count = characters.getOrDefault(direction, 0);
                if (count > most) {
                    best = direction;
                    most = count;
                }
            }
            return best;
        }

        PdfRect turn(PdfRect box) {
            return PdfBoxGeometry.toDisplayed(box, page.cropBox(), rotation);
        }

        /** The part of the page the crop box shows, in this frame. */
        PdfRect wholePage() {
            return new PdfRect(0, 0, width, height);
        }

        /** A line from {@code start} to {@code end} at {@code at}, cut to the shown page ({@code along} long, {@code across} wide); nothing when none of it is shown. */
        private void addClipped(List<Segment> lines, double start, double end, double at, double along, double across) {
            double from = Math.max(0, Math.min(start, end));
            double to = Math.min(along, Math.max(start, end));
            if (at >= 0 && at <= across && to > from) {
                lines.add(new Segment(from, to, at));
            }
        }

        PdfRect unturn(PdfRect box) {
            return PdfBoxGeometry.fromDisplayed(box, page.cropBox(), rotation);
        }

        /** Forms are usually laid out with equal margins, so the right margin is at least the left one mirrored. */
        double rightMargin() {
            if (textLeft == Double.MAX_VALUE) {
                return width;
            }
            return Math.min(width, Math.max(textRight, width - textLeft));
        }

        Band bandAt(double y) {
            Band best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Band band : bands) {
                PdfRect grown = band.box.grownBy(2);
                if (y >= grown.y() && y <= grown.bottom()) {
                    double distance = Math.abs(band.box.centerY() - y);
                    if (distance < bestDistance) {
                        best = band;
                        bestDistance = distance;
                    }
                }
            }
            return best;
        }

        /** A line's words span less than a line of text needs: the row is one line tall, centred on them. */
        PdfRect rowOf(Band band) {
            double height = lineHeightFor(styleOf(band.words).sizePt());
            return new PdfRect(band.box.x(), band.box.centerY() - height / 2, band.box.width(), height);
        }

        Band bandOfLine(int lineIndex) {
            for (Band band : bands) {
                if (band.lineIndex == lineIndex) {
                    return band;
                }
            }
            return null;
        }

        /**
         * The empty space on {@code band}'s row from {@code left} to the
         * first thing in the way (a word, a box's edge, a field, a line
         * across the row) or the text margin, as tall as the band; null when
         * there is none. {@code after} is the right edge of the label the
         * space belongs to: anything before it is not in the way.
         */
        PdfRect spaceRightOf(double left, PdfRect band, double after) {
            double limit = rightMargin();
            for (FrameWord word : allWords) {
                if (word.box.x() > after + 0.5 && overlapsVertically(word.box, band)) {
                    limit = Math.min(limit, word.box.x() - LABEL_GAP);
                }
            }
            for (PdfRect obstacle : obstacles()) {
                if (overlapsVertically(obstacle, band)) {
                    if (obstacle.x() > after + 0.5) {
                        limit = Math.min(limit, obstacle.x() - LABEL_GAP);
                    } else if (obstacle.right() > after + 0.5 && obstacle.right() < limit && obstacle.x() <= after) {
                        // Inside a cell that starts before the label: the cell's right edge is the end of the space.
                        limit = Math.min(limit, obstacle.right() - LABEL_GAP);
                    }
                }
            }
            for (Segment line : vertical) {
                if (line.at > after + 0.5 && line.from < band.bottom() && line.to > band.y()) {
                    limit = Math.min(limit, line.at - LABEL_GAP);
                }
            }
            if (limit - left <= 0) {
                return null;
            }
            return new PdfRect(left, band.y(), limit - left, band.height());
        }

        private List<PdfRect> obstacles() {
            List<PdfRect> obstacles = new ArrayList<>(rects);
            obstacles.addAll(widgets);
            return obstacles;
        }

        /** Horizontal lines, with pieces drawn end to end (a line drawn as many short strokes) joined into one. */
        List<Segment> horizontalRules() {
            List<Segment> sorted = new ArrayList<>(horizontal);
            sorted.sort(Comparator.comparingDouble(Segment::at).thenComparingDouble(Segment::from));
            List<Segment> joined = new ArrayList<>();
            for (Segment segment : sorted) {
                Segment last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
                if (last != null && Math.abs(last.at - segment.at) <= 0.75 && segment.from <= last.to + 1.5) {
                    joined.set(joined.size() - 1, new Segment(last.from, Math.max(last.to, segment.to), last.at));
                } else {
                    joined.add(segment);
                }
            }
            return joined;
        }

        /** A line along the bottom of a row of table cells is one line per cell: cut it wherever an upright line meets it from above. */
        List<Segment> splitByVerticalRules(Segment rule) {
            List<Double> cuts = new ArrayList<>();
            for (Segment line : vertical) {
                boolean meetsFromAbove = line.from < rule.at - 2 && line.to >= rule.at - 1;
                if (meetsFromAbove && line.at > rule.from + 1 && line.at < rule.to - 1) {
                    cuts.add(line.at);
                }
            }
            cuts.sort(Double::compare);
            List<Segment> pieces = new ArrayList<>();
            double start = rule.from;
            for (double cut : cuts) {
                if (cut - start > 1) {
                    pieces.add(new Segment(start + 1.5, cut - 1.5, rule.at));
                }
                start = cut;
            }
            pieces.add(new Segment(cuts.isEmpty() ? rule.from : start + 1.5, rule.to, rule.at));
            return pieces;
        }

        /** The band of words just left of a line drawn to write on, which is usually its label; null when there are none. */
        PdfRect bandLeftOf(double x, double y) {
            PdfRect best = null;
            for (Band band : bands) {
                boolean onTheLine = band.box.bottom() >= y - 14 && band.box.bottom() <= y + 3;
                if (onTheLine && band.box.x() < x) {
                    PdfRect before = null;
                    for (FrameWord word : band.words) {
                        if (word.box.right() <= x + 1) {
                            before = before == null ? word.box : before.union(word.box);
                        }
                    }
                    if (before != null && (best == null || before.right() > best.right())) {
                        best = before;
                    }
                }
            }
            return best;
        }

        List<FrameWord> wordsIn(PdfRect region) {
            List<FrameWord> inside = new ArrayList<>();
            for (FrameWord word : readingWords) {
                if (region.grownBy(0.5).contains(word.box.centerX(), word.box.centerY())) {
                    inside.add(word);
                }
            }
            return inside;
        }

        boolean anyWordOverlaps(PdfRect region, double clearance) {
            PdfRect grown = new PdfRect(region.x(), region.y(), region.width(), region.height() + clearance);
            for (FrameWord word : allWords) {
                if (word.text.isBlank()) {
                    continue;
                }
                double sharedWidth = Math.min(word.box.right(), grown.right()) - Math.max(word.box.x(), grown.x());
                boolean overlapsAcross = sharedWidth > Math.min(2, word.box.width() / 2);
                boolean overlapsDown = word.box.bottom() > grown.y() && word.box.y() < grown.bottom();
                if (overlapsAcross && overlapsDown) {
                    return true;
                }
            }
            return false;
        }

        /** The words just before a place on its own row, back to the previous blank; empty when there are none. */
        List<FrameWord> wordsBefore(PdfRect box, PdfRect labelBand) {
            PdfRect row = labelBand == null ? box : labelBand;
            List<FrameWord> before = new ArrayList<>();
            for (FrameWord word : readingWords) {
                if (word.box.right() <= box.x() + 1 && overlapsVertically(word.box, row)) {
                    before.add(word);
                }
            }
            before.sort(Comparator.comparingDouble(word -> word.box.x()));
            int start = 0;
            for (int index = before.size() - 1; index >= 0; index--) {
                FrameWord word = before.get(index);
                boolean gapTooWide = index < before.size() - 1
                        && before.get(index + 1).box.x() - word.box.right() > 3 * Math.max(word.sizePt, 6);
                if (leaderKind(word.text) != null || gapTooWide) {
                    start = index + 1;
                    break;
                }
            }
            return before.subList(start, before.size());
        }

        /** The line just above a place, when it is short enough to be a heading for it. */
        String shortLineAbove(PdfRect box) {
            Band best = null;
            for (Band band : bands) {
                boolean above = band.box.bottom() <= box.y() + 1 && box.y() - band.box.bottom() <= 1.5 * Math.max(box.height(), 12);
                boolean overlapsAcross = band.box.x() < box.right() && band.box.right() > box.x();
                if (above && overlapsAcross && (best == null || band.box.bottom() > best.box.bottom())) {
                    best = band;
                }
            }
            if (best == null) {
                return null;
            }
            String text = joined(best.words);
            return text.length() <= SHORT_LINE_CHARACTERS ? text : null;
        }

        /** The words inside a drawn box, in reading order; empty when there are none or there is no box. */
        String textIn(PdfRect rect) {
            if (rect == null) {
                return "";
            }
            PdfRect inside = rect.grownBy(-1);
            List<FrameWord> words = new ArrayList<>();
            for (FrameWord word : readingWords) {
                if (inside.contains(word.box.centerX(), word.box.centerY())) {
                    words.add(word);
                }
            }
            words.sort(Comparator.comparingInt((FrameWord word) -> word.lineIndex).thenComparingDouble(word -> word.box.x()));
            return joined(words);
        }

        /**
         * The words of the nearest line just above {@code top} whose middles
         * lie between {@code left} and {@code right}: a column's header
         * printed over a table. Empty when no line is close enough.
         */
        String lineAboveIn(double left, double right, double top) {
            List<FrameWord> column = new ArrayList<>();
            for (FrameWord word : readingWords) {
                boolean above = word.box.bottom() <= top + 1 && top - word.box.bottom() <= lineHeightFor(word.sizePt);
                if (above && word.box.centerX() >= left && word.box.centerX() <= right) {
                    column.add(word);
                }
            }
            double nearest = column.stream().mapToDouble(word -> word.box.bottom()).max().orElse(0);
            List<FrameWord> line = new ArrayList<>(column.stream()
                    .filter(word -> Math.abs(word.box.bottom() - nearest) <= 0.5 * lineHeightFor(word.sizePt))
                    .toList());
            line.sort(Comparator.comparingDouble(word -> word.box.x()));
            return joined(line);
        }

        /**
         * A name for a place nothing just beside or above names: the nearest
         * words to its left on its row, however far, back to a blank; then a
         * short line up to three lines above it.
         * Null when neither is there.
         */
        String nearestLabel(PdfRect box, PdfRect labelBand) {
            PdfRect row = labelBand == null ? box : labelBand;
            List<FrameWord> left = new ArrayList<>();
            for (FrameWord word : readingWords) {
                if (word.box.right() <= box.x() + 1 && overlapsVertically(word.box, row)) {
                    left.add(word);
                }
            }
            left.sort(Comparator.comparingDouble(word -> word.box.x()));
            int start = 0;
            for (int index = left.size() - 1; index >= 0; index--) {
                if (leaderKind(left.get(index).text) != null) {
                    start = index + 1;
                    break;
                }
            }
            String text = labelText(joined(left.subList(start, left.size())));
            if (text != null) {
                return truncate(text, MAX_LABEL_CHARACTERS);
            }
            Band best = null;
            for (Band band : bands) {
                double gap = box.y() - band.box.bottom();
                boolean above = gap >= -1 && gap <= FARTHEST_LINE_ABOVE * Math.max(box.height(), 12);
                boolean overlapsAcross = band.box.x() < box.right() && band.box.right() > box.x();
                if (above && overlapsAcross && (best == null || band.box.bottom() > best.box.bottom())) {
                    best = band;
                }
            }
            return best == null ? null : labelText(joined(best.words));
        }

        /**
         * Whether words keep the place for the office: on its own row, on the
         * short line above it, or inside a drawn box around it ("For office
         * use only" heading a boxed part of the form).
         */
        boolean forOfficeUse(PdfRect box, String context) {
            if (OFFICE_USE.matcher(context).find()) {
                return true;
            }
            String above = shortLineAbove(box);
            if (above != null && OFFICE_USE.matcher(above).find()) {
                return true;
            }
            for (PdfRect rect : rects) {
                boolean aPartOfThePage = rect.area() > box.area() * 1.5 && rect.height() <= FRAME_SHARE_OF_HEIGHT * height;
                if (aPartOfThePage && rect.encloses(box, 2) && OFFICE_USE.matcher(textIn(rect)).find()) {
                    return true;
                }
            }
            return false;
        }

        String contextOf(PdfRect box) {
            List<FrameWord> row = new ArrayList<>();
            for (FrameWord word : allWords) {
                if (overlapsVertically(word.box, box.grownBy(2)) && !word.text.isBlank()) {
                    row.add(word);
                }
            }
            row.sort(Comparator.comparingDouble(word -> word.box.x()));
            String text = joined(row);
            if (text.isEmpty()) {
                String above = shortLineAbove(box);
                text = above == null ? "" : above;
            }
            return truncate(text, MAX_CONTEXT_CHARACTERS);
        }

        /**
         * How text written into {@code box} should look: the font most of
         * {@code source}'s characters are set in, mapped to one of the three
         * families by its name, its size rounded to half a point and no more
         * than eight tenths of the box's height, and within the sizes a
         * template's box may have ({@link #boxTextSize}).
         */
        PdfTextStyle styleFor(PdfRect box, List<FrameWord> source) {
            PdfTextStyle style = styleOf(source.isEmpty() ? readingWords : source);
            double largest = Math.floor(0.8 * box.height() * 2) / 2;
            return new PdfTextStyle(style.family(), style.bold(), boxTextSize(Math.min(style.sizePt(), largest)));
        }

        PdfTextStyle styleOf(List<FrameWord> words) {
            if (words.isEmpty()) {
                return PdfTextStyle.DEFAULT;
            }
            Map<String, Integer> fonts = new HashMap<>();
            Map<Double, Integer> sizes = new HashMap<>();
            for (FrameWord word : words) {
                int weight = word.text.length();
                fonts.merge(word.fontName == null ? "" : word.fontName, weight, Integer::sum);
                sizes.merge(Math.round(word.sizePt * 2) / 2.0, weight, Integer::sum);
            }
            String font = mostCommon(fonts);
            double size = mostCommon(sizes);
            return new PdfTextStyle(PdfFontFamily.fromFontName(font), PdfFontFamily.boldFromFontName(font),
                    size > 0 ? size : PdfTextStyle.DEFAULT.sizePt());
        }

        PdfBoxSuggestion suggestion(PdfRect box, PdfRect labelBand) {
            List<FrameWord> before = wordsBefore(box, labelBand);
            return new PdfBoxSuggestion(unturn(box), styleFor(box, before.isEmpty() ? wordsIn(labelBand) : before),
                    labelFrom(before, box));
        }

        String labelFrom(List<FrameWord> before, PdfRect box) {
            String text = joined(before);
            if (text.isEmpty() || text.length() > SHORT_LINE_CHARACTERS && !text.endsWith(":")) {
                text = shortLineAbove(box);
            }
            if (text == null || text.isEmpty()) {
                return null;
            }
            while (text.endsWith(":")) {
                text = text.substring(0, text.length() - 1).strip();
            }
            return text.isEmpty() ? null : truncate(text, MAX_LABEL_CHARACTERS);
        }

        static boolean overlapsVertically(PdfRect first, PdfRect second) {
            double shared = Math.min(first.bottom(), second.bottom()) - Math.max(first.y(), second.y());
            return shared > 0.3 * Math.min(Math.max(first.height(), 1), Math.max(second.height(), 1));
        }
    }

    private static String joined(List<FrameWord> words) {
        StringBuilder text = new StringBuilder();
        for (FrameWord word : words) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word.text);
        }
        return text.toString().strip();
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).strip();
    }

    private static <K> K mostCommon(Map<K, Integer> counts) {
        K best = null;
        int most = -1;
        for (Map.Entry<K, Integer> entry : counts.entrySet()) {
            boolean more = entry.getValue() > most;
            boolean tieBrokenByKey = entry.getValue() == most && String.valueOf(entry.getKey()).compareTo(String.valueOf(best)) < 0;
            if (more || tieBrokenByKey) {
                best = entry.getKey();
                most = entry.getValue();
            }
        }
        return best;
    }

    /**
     * One place found on one page, in that page's reading frame, before it
     * is numbered. {@code cell} is the drawn box an empty box was found in,
     * and {@code grid} its place in a grid of boxes, once known.
     */
    private static final class Found {

        final Frame frame;
        final PdfSpotCandidate.Kind kind;
        final PdfRect box;
        final PdfRect labelBand;
        final PdfRect cell;
        GridPlace grid;

        Found(Frame frame, PdfSpotCandidate.Kind kind, PdfRect box, PdfRect labelBand) {
            this(frame, kind, box, labelBand, null);
        }

        Found(Frame frame, PdfSpotCandidate.Kind kind, PdfRect box, PdfRect labelBand, PdfRect cell) {
            this.frame = frame;
            this.kind = kind;
            this.box = box;
            this.labelBand = labelBand;
            this.cell = cell;
        }

        PdfSpotCandidate toCandidate(String id) {
            List<FrameWord> before = frame.wordsBefore(box, labelBand);
            String context = frame.contextOf(box);
            String label;
            if (grid != null && grid.header() != null) {
                label = TableCellLabels.label(grid.header(), grid.rowText(), grid.rowNumber(), grid.severalRows());
                context = truncate(TableCellLabels.context(grid.header(), grid.rowText(), grid.rowNumber()), MAX_CONTEXT_CHARACTERS);
            } else {
                label = frame.labelFrom(before, box);
                if (label == null) {
                    label = frame.nearestLabel(box, labelBand);
                }
            }
            boolean signature = SIGNATURE_WORDS.matcher(context).find() || (label != null && SIGNATURE_WORDS.matcher(label).find());
            return new PdfSpotCandidate(id, frame.page.pageNumber(), frame.unturn(box), kind, context, label,
                    frame.styleFor(box, before), signature, grid == null ? null : grid.gridKey(),
                    grid == null ? List.of() : grid.values(), frame.forOfficeUse(box, context));
        }
    }
}
