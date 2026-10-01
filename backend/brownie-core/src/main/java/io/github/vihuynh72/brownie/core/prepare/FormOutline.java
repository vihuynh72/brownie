package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;

import java.util.List;
import java.util.Objects;

/**
 * A Word form read for the places a person could fill: every paragraph in
 * document order with what the finder needs and the structural graph does
 * not carry -- which runs are underlined, hidden, inside a link or inside a
 * field Word works out; which line a tab draws; where the form's own
 * fields and controls sit. Offsets are code points into the paragraph's
 * {@code anchorText}, which is exactly the graph's anchor text for the
 * same paragraph ({@code core.template.ParagraphAnchorText}), so a place
 * found here is a place the editor can make.
 *
 * <p>Paragraphs the filler cannot reach are listed too, with no node id
 * and the {@link Region} that explains why, so blanks there can be counted
 * and a person told about them.
 */
public record FormOutline(String parserVersion, List<Paragraph> paragraphs) {

    public FormOutline {
        Objects.requireNonNull(parserVersion, "parserVersion");
        paragraphs = List.copyOf(paragraphs);
    }

    /** Where a paragraph sits, which decides whether a spot can be made there. */
    public enum Region {
        /** A paragraph of the main document's own body. */
        BODY,
        /** A paragraph of a cell of a table in the main document's own body. */
        TOP_TABLE_CELL,
        /** A paragraph of a table inside a table cell; the filler does not look there. */
        NESTED_TABLE,
        /** A paragraph of a text box; the filler does not look there. */
        TEXT_BOX,
        /** A paragraph of a header or footer; values are written only in the body. */
        HEADER_FOOTER
    }

    /**
     * Where a top-level table cell sits: {@code table} counts the main
     * body's tables from 1, {@code row} and {@code column} count from 0, and
     * {@code rowCount} is how many rows the table has. {@code continuesMerge}
     * is true for a cell that continues a vertical merge from the cell above:
     * it prints as part of that cell, and nothing written in it shows.
     */
    public record Cell(int table, String tableNodeId, String rowNodeId, int row, int column, int rowCount, boolean continuesMerge) {

        public Cell(int table, String tableNodeId, String rowNodeId, int row, int column, int rowCount) {
            this(table, tableNodeId, rowNodeId, row, column, rowCount, false);
        }
    }

    /**
     * One paragraph. {@code key} is unique within the outline. {@code
     * nodeId} is the paragraph's graph node id within {@code part}, or null
     * where the graph gives it none. {@code styleName} is the paragraph
     * style's name as the form spells it, or null; {@code heading} is true
     * for a title or heading. {@code cell} is set exactly for
     * {@link Region#TOP_TABLE_CELL}.
     */
    public record Paragraph(
            String key,
            DocumentPartKind part,
            Region region,
            String nodeId,
            String anchorText,
            String styleName,
            boolean heading,
            Cell cell,
            List<Atom> atoms) {

        public Paragraph {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(anchorText, "anchorText");
            if ((region == Region.TOP_TABLE_CELL) != (cell != null)) {
                throw new IllegalArgumentException("A cell position is given exactly for a top-level table cell's paragraph.");
            }
            atoms = List.copyOf(atoms);
        }
    }

    /** Something in a paragraph the finder reads, at a place in its anchor text. */
    public sealed interface Atom permits Run, Tab, FormField, CheckboxField, Control, Drawing {
    }

    /**
     * One run's text at {@code [start, end)}. {@code inField} is true for
     * text shown by a field Word works out (a page number, a table of
     * contents), which is never a blank.
     */
    public record Run(int start, int end, boolean underlined, boolean hidden, boolean inLink, boolean inField) implements Atom {
    }

    /** The tab character at {@code at}, and the line it draws to its stop ({@code dot}, {@code underscore}, ...), or null for none. */
    public record Tab(int at, String leader) implements Atom {
    }

    /**
     * A form's own field for a blank -- a form text box, a merge field, a
     * prompt -- already written out as the text it showed, now at
     * {@code [start, end)} (empty when it showed nothing). {@code keyword}
     * is the field's kind ({@code FORMTEXT}, {@code MERGEFIELD}, ...);
     * {@code words} is what the field calls its blank (a merge field's
     * name, a prompt's question, a form box's help text), or null.
     */
    public record FormField(int start, int end, String keyword, String words) implements Atom {
    }

    /** A form checkbox field at {@code at}; Brownie cannot fill a checkbox yet. */
    public record CheckboxField(int at) implements Atom {
    }

    /** What an inline content control holds, as far as filling goes. */
    public enum ControlKind {
        TEXT,
        DATE,
        CHECKBOX,
        PICTURE
    }

    /**
     * An inline content control, which sits at {@code at} in the anchor
     * text (its own text is not part of it). {@code nodeId} is its graph
     * node id; {@code tag} and {@code alias} are as the form set them, or
     * null; {@code text} is what it shows.
     */
    public record Control(int at, String nodeId, String tag, String alias, String text, ControlKind kind) implements Atom {
    }

    /** A picture at {@code at}. */
    public record Drawing(int at) implements Atom {
    }
}
