package io.github.vihuynh72.brownie.core.template;

import java.util.Objects;

/**
 * How a place in a table is named, the same for a Word table and a grid of
 * boxes on a PDF page: by its column's header, with its row added when
 * several rows have a place under that header, so that each place's name
 * is its own. The row is the text in the row's first cell ("Year
 * (Painting)") or, when that cell is empty or is the place itself, the
 * row's number under the header ("Course (row 2)"). A value printed in the
 * table never names a place on its own: it only tells rows apart.
 */
public final class TableCellLabels {

    private TableCellLabels() {
    }

    /**
     * The label for a place under {@code header}: the header alone when
     * {@code severalRows} is false, otherwise the header and its row, the
     * row cut short when the two would be longer than a label may be.
     * {@code rowText} is the row's first cell, or null to use {@code
     * rowNumber} (1 for the first row under the header).
     */
    public static String label(String header, String rowText, int rowNumber, boolean severalRows) {
        Objects.requireNonNull(header, "header");
        if (!severalRows) {
            return header;
        }
        String row = rowText != null ? rowText : "row " + rowNumber;
        int room = FieldIds.MAX_LABEL_LENGTH - header.codePointCount(0, header.length()) - 3;
        if (room < 1) {
            return header;
        }
        if (row.codePointCount(0, row.length()) > room) {
            row = row.substring(0, row.offsetByCodePoints(0, room)).strip();
        }
        String label = FieldIds.normalizeLabel(header + " (" + row + ")");
        return label == null ? header : label;
    }

    /**
     * The words a found place's field id is made from: its label without the
     * words in brackets at its end, which only tell places apart ("Year
     * (Painting)", "Phone (mobile)"). A second place with the same words gets
     * the next number ({@link FieldIds#fromLabel}), so ids stay short: a
     * template's sample fill writes each id into its place, and a long one
     * wraps in a narrow table cell. The label itself when nothing would be
     * left.
     */
    public static String idWords(String label) {
        String stripped = label.strip();
        if (stripped.endsWith(")")) {
            int open = stripped.lastIndexOf(" (");
            if (open > 0) {
                String words = FieldIds.normalizeLabel(stripped.substring(0, open));
                if (words != null) {
                    return words;
                }
            }
        }
        return label;
    }

    /** What the naming step is told about the place: "column: Year; row: Painting", or "column: Course; row 2" for a numbered row. */
    public static String context(String header, String rowText, int rowNumber) {
        return "column: " + header + (rowText != null ? "; row: " + rowText : "; row " + rowNumber);
    }
}
