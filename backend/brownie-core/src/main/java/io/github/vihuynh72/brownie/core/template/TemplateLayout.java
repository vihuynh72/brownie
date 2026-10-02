package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;

import java.util.List;

/**
 * One template version's document drawn as a page a person can read: its
 * paragraphs and tables in order, the template's own text with the style it
 * is set in, and a fill spot wherever the filler actually writes a field's
 * value. Read-only and derived entirely from the template's own file, so it
 * says nothing about any one document's values.
 *
 * <p>{@code unplacedFieldIds} names every field of the version that has no
 * fill spot anywhere in {@code parts}, in the version's own field order, so
 * a page can still offer those fields somewhere rather than leave them
 * unreachable.
 */
public record TemplateLayout(
        long templateId, long versionId, String parserVersion, List<Part> parts, List<String> unplacedFieldIds) {

    public TemplateLayout {
        parts = List.copyOf(parts);
        unplacedFieldIds = List.copyOf(unplacedFieldIds);
    }

    /** One package part's blocks, in document order. */
    public record Part(DocumentPartKind kind, List<Block> blocks) {

        public Part {
            blocks = List.copyOf(blocks);
        }
    }

    /** A paragraph or a table, the only two things a part's body holds. */
    public sealed interface Block permits Paragraph, Table {
    }

    /**
     * {@code listLevel} is the numbering level of a numbered paragraph and
     * null otherwise. {@code repeating} marks the one paragraph the filler
     * copies once per repeated item.
     *
     * <p>{@code nodeId} is the paragraph's node id in the graph of the
     * layout's {@code parserVersion}. {@code anchorable} says a fill spot can
     * be added in it: only a paragraph of the body the filler writes into,
     * outside the row or paragraph that repeats per item, and not a content
     * control around whole paragraphs. {@code anchorTextHash} is the hash of
     * its anchor text ({@link ParagraphAnchorText}), which a place chosen in
     * it sends back so a page that has since changed is refused; null when it
     * is not anchorable.
     */
    public record Paragraph(
            Alignment alignment,
            Integer listLevel,
            boolean repeating,
            List<Inline> inlines,
            String nodeId,
            boolean anchorable,
            String anchorTextHash) implements Block {

        public Paragraph {
            inlines = List.copyOf(inlines);
        }
    }

    public record Table(List<Row> rows) implements Block {

        public Table {
            rows = List.copyOf(rows);
        }
    }

    /** {@code repeating} marks the one row the filler copies once per repeated item. */
    public record Row(boolean repeating, List<Cell> cells) {

        public Row {
            cells = List.copyOf(cells);
        }
    }

    public record Cell(List<Block> blocks) {

        public Cell {
            blocks = List.copyOf(blocks);
        }
    }

    /** One piece of a paragraph's content, in reading order. */
    public sealed interface Inline permits Text, FillSpot, Image {
    }

    /**
     * The template's own words, exactly as written; {@code style} is null
     * when the template set nothing at all on them.
     *
     * <p>In an anchorable paragraph, {@code anchorStart} is where the text
     * starts in the paragraph's anchor text, in code points, so a place
     * inside it can be sent back as an offset; text shown from a content
     * control no field names has none and carries that control's {@code
     * controlNodeId} instead, since the control itself is what a spot there
     * would be. Both are null in a paragraph that is not anchorable.
     */
    public record Text(String text, Style style, Integer anchorStart, String controlNodeId) implements Inline {

        /** Text that cannot be pointed at: in a header, a footer or a repeating row. */
        public Text(String text, Style style) {
            this(text, style, null, null);
        }
    }

    /**
     * Where one field's value goes. {@code placeholder} is what the template
     * itself shows there before anything is filled, and {@code style} is the
     * style of the control's first run, which is the formatting a filled
     * value takes. {@code nodeId} is the control's node id; {@code origin}
     * and {@code label} are the field's, so a page can say who placed the
     * spot and name it the way the person does.
     */
    public record FillSpot(String fieldId, String placeholder, Style style, String nodeId, SpotOrigin origin, String label)
            implements Inline {
    }

    /** A picture the template carries; its bytes stay in the template file. */
    public record Image() implements Inline {
    }

    /** Every property is null when the template never set it, which is not the same as "off". */
    public record Style(
            Boolean bold, Boolean italic, Boolean underline, String fontFamily, Integer fontSizeHalfPoints, String colorHex) {
    }

    public enum Alignment {
        START,
        CENTER,
        END,
        JUSTIFY
    }
}
