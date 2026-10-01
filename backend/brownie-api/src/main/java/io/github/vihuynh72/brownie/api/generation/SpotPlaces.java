package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.assist.AssistCommand;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.template.TemplateLayout;
import io.github.vihuynh72.brownie.core.text.CodePoints;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines of a form's page a fill spot can be added to, and how words a
 * person quoted, or a line the model chose, become a place in one of them.
 * Pure: it reads the page layout and computes anchors, and never looks at
 * the file.
 *
 * <p>A line is an anchorable paragraph of the body (see {@code
 * TemplateLayoutProjector}); its text is its anchor text, rebuilt from the
 * layout's pieces that carry an offset into it, so every place computed here
 * counts the same code points the Word editor counts. Quoted words are
 * found ignoring case and runs of spaces, the way a person types them back.
 */
final class SpotPlaces {

    /** A run of three or more underscores, four or more dots, or ellipses: the part of a line a person writes on. */
    private static final Pattern BLANK_RUN = Pattern.compile("_{3,}|\\.{4,}|\u2026+");
    private static final Pattern ONLY_BLANK = Pattern.compile("^\\s*(?:(?:_{3,}|\\.{4,}|\u2026+)\\s*)+$|^\\s*\\[[^\\[\\]]{1,80}]\\s*$");

    /** {@code where} says where a line sits when it is in a table ("table 1, row 2, column 1"), and is null otherwise. */
    record Line(String id, String paragraphNodeId, String text, String anchorTextHash, String where) {
    }

    /** A place a spot can go, and the text of its line for the person to recognise it by. */
    record Place(DocxAnchor anchor, String lineText, String description) {
    }

    private SpotPlaces() {
    }

    /** Every anchorable line of the body, in reading order, with opaque ids L1, L2, ... */
    static List<Line> linesOf(TemplateLayout layout) {
        List<Line> lines = new ArrayList<>();
        layout.parts().stream()
                .filter(part -> part.kind() == DocumentPartKind.MAIN_DOCUMENT)
                .findFirst()
                .ifPresent(main -> collect(main.blocks(), null, lines));
        return lines;
    }

    private static void collect(List<TemplateLayout.Block> blocks, String where, List<Line> lines) {
        int table = 0;
        for (TemplateLayout.Block block : blocks) {
            switch (block) {
                case TemplateLayout.Paragraph paragraph -> {
                    if (paragraph.anchorable()) {
                        lines.add(new Line("L" + (lines.size() + 1), paragraph.nodeId(), anchorText(paragraph), paragraph.anchorTextHash(), where));
                    }
                }
                case TemplateLayout.Table tableBlock -> {
                    table++;
                    for (int row = 0; row < tableBlock.rows().size(); row++) {
                        List<TemplateLayout.Cell> cells = tableBlock.rows().get(row).cells();
                        for (int column = 0; column < cells.size(); column++) {
                            collect(cells.get(column).blocks(), "table " + table + ", row " + (row + 1) + ", column " + (column + 1), lines);
                        }
                    }
                }
            }
        }
    }

    /** The paragraph's own text: its pieces that carry an offset, in order, which together are its anchor text. */
    static String anchorText(TemplateLayout.Paragraph paragraph) {
        StringBuilder text = new StringBuilder();
        for (TemplateLayout.Inline inline : paragraph.inlines()) {
            if (inline instanceof TemplateLayout.Text piece && piece.anchorStart() != null) {
                text.append(piece.text());
            }
        }
        return text.toString();
    }

    /** Every place the quoted words make, one per time they appear in a line. */
    static List<Place> placesOfQuoted(List<Line> lines, String quoted, AssistCommand.SpotPlacement placement, String parserVersion) {
        List<Place> places = new ArrayList<>();
        for (Line line : lines) {
            for (int[] found : occurrences(line.text(), quoted)) {
                places.add(placeFor(line, found[0], found[1], placement, parserVersion));
            }
        }
        return places;
    }

    /**
     * Each time the quoted words appear in {@code text}, ignoring case and
     * runs of spaces, as its start and end in code points of {@code text}.
     * Shared with the lines of a PDF page, which are found the same way.
     */
    static List<int[]> occurrences(String text, String quoted) {
        Folded wanted = Folded.of(Normalizer.normalize(quoted, Normalizer.Form.NFC).strip());
        List<int[]> occurrences = new ArrayList<>();
        if (wanted.text().isEmpty()) {
            return occurrences;
        }
        Folded folded = Folded.of(text);
        int from = 0;
        while (true) {
            int found = folded.text().indexOf(wanted.text(), from);
            if (found < 0) {
                break;
            }
            occurrences.add(new int[] {folded.originalOffset(found), folded.originalOffset(found + wanted.text().length())});
            from = found + Math.max(1, wanted.text().length());
        }
        return occurrences;
    }

    /**
     * The place for words found at {@code [start, end)} of a line, in code
     * points: right after them, stepping over the spaces that follow and
     * taking the place of a blank there ("Company: ____"); in their place; in
     * the line, over its blank or at its end; or over the whole line.
     */
    static Place placeFor(Line line, int start, int end, AssistCommand.SpotPlacement placement, String parserVersion) {
        String text = line.text();
        int length = CodePoints.length(text);
        return switch (placement) {
            case REPLACE -> place(line, AnchorPlacement.REPLACE, start, end, parserVersion,
                    "in place of \"" + CodePoints.substring(text, start, end) + "\"");
            case WHOLE_LINE -> place(line, AnchorPlacement.WHOLE_LINE, 0, length, parserVersion, "on the line");
            case IN_LINE -> inLine(line, parserVersion);
            case AFTER, UNSPECIFIED -> after(line, start, end, parserVersion);
        };
    }

    /** At the end of a line, or in place of its blank when it has one: the same two places a model can choose by name. */
    static Place inLine(Line line, String parserVersion) {
        String text = line.text();
        int length = CodePoints.length(text);
        if (ONLY_BLANK.matcher(text).matches()) {
            return place(line, AnchorPlacement.WHOLE_LINE, 0, length, parserVersion, "on the line");
        }
        Matcher blank = BLANK_RUN.matcher(text);
        if (blank.find()) {
            int start = text.codePointCount(0, blank.start());
            int end = text.codePointCount(0, blank.end());
            return place(line, AnchorPlacement.REPLACE, start, end, parserVersion, "in place of \"" + blank.group() + "\"");
        }
        return place(line, AnchorPlacement.AT, length, length, parserVersion, "at the end of the line");
    }

    private static Place after(Line line, int start, int end, String parserVersion) {
        String text = line.text();
        String quoted = CodePoints.substring(text, start, end);
        int charEnd = text.offsetByCodePoints(0, end);
        int afterSpaces = charEnd;
        while (afterSpaces < text.length() && (text.charAt(afterSpaces) == ' ' || text.charAt(afterSpaces) == '\t'
                || text.charAt(afterSpaces) == '\u00A0')) {
            afterSpaces++;
        }
        Matcher blank = BLANK_RUN.matcher(text);
        if (blank.find(afterSpaces) && blank.start() == afterSpaces) {
            int blankStart = text.codePointCount(0, blank.start());
            int blankEnd = text.codePointCount(0, blank.end());
            return place(line, AnchorPlacement.REPLACE, blankStart, blankEnd, parserVersion, "after \"" + quoted + "\"");
        }
        int at = text.codePointCount(0, afterSpaces);
        return place(line, AnchorPlacement.AT, at, at, parserVersion, "after \"" + quoted + "\"");
    }

    private static Place place(Line line, AnchorPlacement placement, int start, int end, String parserVersion, String description) {
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, line.paragraphNodeId(), placement, start, end, line.anchorTextHash(), parserVersion, null);
        return new Place(anchor, line.text(), description);
    }

    /** The line a place on the page is in, if it is one of the lines a spot can go on. */
    static Optional<Line> lineOf(List<Line> lines, DocxAnchor anchor) {
        String nodeId = anchor.paragraphNodeId() != null ? anchor.paragraphNodeId() : parentOf(anchor.controlNodeId());
        return lines.stream().filter(line -> line.paragraphNodeId().equals(nodeId)).findFirst();
    }

    private static String parentOf(String controlNodeId) {
        if (controlNodeId == null) {
            return null;
        }
        int slash = controlNodeId.lastIndexOf('/');
        return slash <= 0 ? null : controlNodeId.substring(0, slash);
    }

    /**
     * The model's choice as a place, only when it holds together: a line it
     * was offered, and for a choice by words, words that are really in that
     * line. Anything else is no place at all.
     */
    static Optional<Place> fromModel(List<Line> offered, String lineId, String placement, String words, String parserVersion) {
        Optional<Line> chosen = offered.stream().filter(line -> line.id().equals(lineId)).findFirst();
        if (chosen.isEmpty() || placement == null) {
            return Optional.empty();
        }
        Line line = chosen.get();
        int length = CodePoints.length(line.text());
        switch (placement) {
            case "AFTER_TEXT", "REPLACE_TEXT" -> {
                if (words == null || words.isBlank()) {
                    return Optional.empty();
                }
                int found = line.text().indexOf(words);
                if (found < 0) {
                    // Words copied from the line as the call showed it stand at the same place in the line itself.
                    found = SpotPlacementPrompt.shownText(line.text()).indexOf(words);
                }
                if (found < 0) {
                    return Optional.empty();
                }
                int start = line.text().codePointCount(0, found);
                int end = start + CodePoints.length(words);
                return Optional.of(placeFor(line, start, end,
                        placement.equals("AFTER_TEXT") ? AssistCommand.SpotPlacement.AFTER : AssistCommand.SpotPlacement.REPLACE, parserVersion));
            }
            case "END_OF_LINE" -> {
                return Optional.of(place(line, AnchorPlacement.AT, length, length, parserVersion, "at the end of the line"));
            }
            case "WHOLE_LINE" -> {
                return Optional.of(place(line, AnchorPlacement.WHOLE_LINE, 0, length, parserVersion, "on the line"));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * Text with case folded and every run of spaces made one space, and
     * where each of its code points came from in the original, so a match
     * found in it is a range of the original's code points.
     */
    private record Folded(String text, int[] origins, int originalLength) {

        static Folded of(String original) {
            StringBuilder folded = new StringBuilder();
            List<Integer> origins = new ArrayList<>();
            int index = 0;
            boolean spaceBefore = false;
            for (int i = 0; i < original.length(); ) {
                int codePoint = original.codePointAt(i);
                i += Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    if (!spaceBefore) {
                        folded.append(' ');
                        origins.add(index);
                    }
                    spaceBefore = true;
                } else {
                    folded.appendCodePoint(Character.toLowerCase(codePoint));
                    origins.add(index);
                    spaceBefore = false;
                }
                index++;
            }
            return new Folded(folded.toString(), origins.stream().mapToInt(Integer::intValue).toArray(), index);
        }

        /** The original code point offset of a char offset into {@link #text}; the end of the text maps to the end of the original. */
        int originalOffset(int charOffset) {
            int codePointOffset = text.codePointCount(0, charOffset);
            return codePointOffset >= origins.length ? originalLength : origins[codePointOffset];
        }
    }
}
