package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a template's structural graph into a {@link TemplateLayout}, placing
 * fill spots by exactly the rules the DOCX filler follows, so the page never
 * offers a place to type that the exported file would leave untouched:
 *
 * <ul>
 * <li>A content control becomes a fill spot only in the main document body
 * and only when its tag is a field's {@link FieldBindingTarget.ContentControlTag}.
 * The filler never looks inside headers or footers, and never writes into a
 * control whose tag no field names, so both stay ordinary template text.</li>
 * <li>A field bound to a raw structural node is never filled, so it has no
 * fill spot and is listed as unplaced, as is a field whose tag is nowhere.</li>
 * <li>Repeated fields are one group. When the first table of the body has, in
 * its last row, a control for every repeated field, that row is the one the
 * filler copies per item; otherwise it is the first top-level body paragraph
 * holding a control for the first repeated field.</li>
 * </ul>
 */
public final class TemplateLayoutProjector {

    /**
     * Far above anything a qualified template holds (a template is at most a
     * few pages), and low enough that a file built to be enormous cannot turn
     * one read into a response of unbounded size.
     */
    static final int MAX_TEXT_CHARACTERS = 400_000;

    private static final Pattern COLOR_HEX = Pattern.compile("[0-9A-Fa-f]{6}");

    private static final Comparator<DocumentPart> PART_ORDER =
            Comparator.comparingInt(part -> switch (part.kind()) {
                case MAIN_DOCUMENT -> 0;
                case HEADER -> 1;
                case FOOTER -> 2;
            });

    private final long templateId;
    private final long versionId;
    private final Map<String, String> fieldIdByTag = new LinkedHashMap<>();
    private final Set<String> placedFieldIds = new HashSet<>();
    private String repeatingRowNodeId;
    private String repeatingParagraphNodeId;
    private int textCharacters;

    private TemplateLayoutProjector(long templateId, long versionId) {
        this.templateId = templateId;
        this.versionId = versionId;
    }

    /**
     * Throws {@link TemplateLayoutUnavailableException} when the template's
     * text would exceed {@link #MAX_TEXT_CHARACTERS}.
     */
    public static TemplateLayout project(
            long templateId, long versionId, DocxStructuralGraph graph, List<FieldDefinition> fieldDefinitions) {
        return new TemplateLayoutProjector(templateId, versionId).run(graph, fieldDefinitions);
    }

    private TemplateLayout run(DocxStructuralGraph graph, List<FieldDefinition> fieldDefinitions) {
        for (FieldDefinition field : fieldDefinitions) {
            if (field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag)) {
                // Two fields naming one tag would both be written into the same control; the page shows it once,
                // for the first of them, and the other is reported as having no place of its own.
                fieldIdByTag.putIfAbsent(tag, field.fieldId());
            }
        }
        List<DocumentPart> orderedParts = graph.parts().stream().sorted(PART_ORDER).toList();
        orderedParts.stream()
                .filter(part -> part.kind() == DocumentPartKind.MAIN_DOCUMENT)
                .findFirst()
                .ifPresent(main -> locateRepeatingRegion(main, fieldDefinitions));

        List<TemplateLayout.Part> parts = new ArrayList<>();
        boolean mainSeen = false;
        for (DocumentPart part : orderedParts) {
            // Only the first main part is the body the filler writes into; a graph never has two, but if it did
            // the second would be drawn as plain template text.
            boolean fillable = part.kind() == DocumentPartKind.MAIN_DOCUMENT && !mainSeen;
            mainSeen |= part.kind() == DocumentPartKind.MAIN_DOCUMENT;
            parts.add(new TemplateLayout.Part(part.kind(), blocksOf(part.root().children(), fillable, true)));
        }

        List<String> unplaced = fieldDefinitions.stream()
                .map(FieldDefinition::fieldId)
                .filter(fieldId -> !placedFieldIds.contains(fieldId))
                .distinct()
                .toList();
        return new TemplateLayout(templateId, versionId, graph.parserVersion(), parts, unplaced);
    }

    private void locateRepeatingRegion(DocumentPart main, List<FieldDefinition> fieldDefinitions) {
        List<FieldDefinition> repeated = fieldDefinitions.stream()
                .filter(field -> field.cardinality() == FieldCardinality.REPEATED)
                .toList();
        if (repeated.isEmpty()) {
            return;
        }
        List<StructuralNode> body = main.root().children();
        StructuralNode firstTable = body.stream().filter(node -> node.kind() == StructuralNodeKind.TABLE).findFirst().orElse(null);
        if (firstTable != null && !firstTable.children().isEmpty()) {
            StructuralNode lastRow = firstTable.children().getLast();
            boolean everyFieldHasAControl = repeated.stream().allMatch(field -> tagOf(field) != null
                    && lastRow.children().stream()
                            .flatMap(cell -> cell.children().stream())
                            .anyMatch(paragraph -> holdsControlTagged(paragraph, tagOf(field))));
            if (everyFieldHasAControl) {
                repeatingRowNodeId = lastRow.nodeId();
                return;
            }
        }
        String firstTag = tagOf(repeated.getFirst());
        if (firstTag == null) {
            return;
        }
        body.stream()
                .filter(node -> node.kind() == StructuralNodeKind.PARAGRAPH && holdsControlTagged(node, firstTag))
                .findFirst()
                .ifPresent(paragraph -> repeatingParagraphNodeId = paragraph.nodeId());
    }

    private static String tagOf(FieldDefinition field) {
        return field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag) ? tag : null;
    }

    private static boolean holdsControlTagged(StructuralNode paragraph, String tag) {
        return paragraph.kind() == StructuralNodeKind.PARAGRAPH
                && paragraph.children().stream().anyMatch(child ->
                        child.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(child.contentControlTag()));
    }

    /** {@code topLevel} is true only for a part's own body, where the filler looks for a repeated paragraph. */
    private List<TemplateLayout.Block> blocksOf(List<StructuralNode> nodes, boolean fillable, boolean topLevel) {
        List<TemplateLayout.Block> blocks = new ArrayList<>();
        for (StructuralNode node : nodes) {
            if (node.kind() == StructuralNodeKind.PARAGRAPH) {
                blocks.add(paragraphOf(node, fillable, topLevel));
            } else if (node.kind() == StructuralNodeKind.TABLE) {
                blocks.add(tableOf(node, fillable));
            }
        }
        return blocks;
    }

    private TemplateLayout.Table tableOf(StructuralNode table, boolean fillable) {
        List<TemplateLayout.Row> rows = new ArrayList<>();
        for (StructuralNode row : table.children()) {
            List<TemplateLayout.Cell> cells = new ArrayList<>();
            for (StructuralNode cell : row.children()) {
                cells.add(new TemplateLayout.Cell(blocksOf(cell.children(), fillable, false)));
            }
            boolean repeating = fillable && row.nodeId().equals(repeatingRowNodeId);
            rows.add(new TemplateLayout.Row(repeating, cells));
        }
        return new TemplateLayout.Table(rows);
    }

    private TemplateLayout.Paragraph paragraphOf(StructuralNode paragraph, boolean fillable, boolean topLevel) {
        List<TemplateLayout.Inline> inlines = new ArrayList<>();
        for (StructuralNode child : paragraph.children()) {
            switch (child.kind()) {
                case RUN -> appendText(inlines, child);
                case IMAGE -> inlines.add(new TemplateLayout.Image());
                case CONTENT_CONTROL -> {
                    String fieldId = fillable && child.contentControlTag() != null ? fieldIdByTag.get(child.contentControlTag()) : null;
                    if (fieldId != null) {
                        inlines.add(fillSpotOf(fieldId, child));
                    } else {
                        for (StructuralNode inner : child.children()) {
                            if (inner.kind() == StructuralNodeKind.IMAGE) {
                                inlines.add(new TemplateLayout.Image());
                            } else if (inner.kind() == StructuralNodeKind.RUN) {
                                appendText(inlines, inner);
                            }
                        }
                    }
                }
                default -> {
                    // A paragraph holds only runs, controls and images; anything else carries nothing to show.
                }
            }
        }
        ResolvedStyle style = paragraph.style();
        boolean repeating = fillable && topLevel && paragraph.nodeId().equals(repeatingParagraphNodeId);
        return new TemplateLayout.Paragraph(alignmentOf(style), listLevelOf(style), repeating, inlines);
    }

    private TemplateLayout.FillSpot fillSpotOf(String fieldId, StructuralNode control) {
        StringBuilder placeholder = new StringBuilder();
        for (StructuralNode inner : control.children()) {
            if (inner.kind() == StructuralNodeKind.RUN && inner.text() != null) {
                placeholder.append(inner.text());
            }
        }
        String trimmed = placeholder.toString().trim();
        countText(trimmed.length());
        // The filler keeps the control's first run, with its formatting, and writes the value into it.
        StructuralNode first = control.children().isEmpty() ? null : control.children().getFirst();
        TemplateLayout.Style style = first != null && first.kind() == StructuralNodeKind.RUN ? styleOf(first.style()) : null;
        placedFieldIds.add(fieldId);
        return new TemplateLayout.FillSpot(fieldId, trimmed.isEmpty() ? null : trimmed, style);
    }

    /** Adjacent runs set in the same style read as one piece of text, so they are joined; a run with no text adds nothing. */
    private void appendText(List<TemplateLayout.Inline> inlines, StructuralNode run) {
        String text = run.text();
        if (text == null || text.isEmpty()) {
            return;
        }
        countText(text.length());
        TemplateLayout.Style style = styleOf(run.style());
        if (!inlines.isEmpty() && inlines.getLast() instanceof TemplateLayout.Text(String previousText, TemplateLayout.Style previousStyle)
                && Objects.equals(previousStyle, style)) {
            inlines.set(inlines.size() - 1, new TemplateLayout.Text(previousText + text, style));
            return;
        }
        inlines.add(new TemplateLayout.Text(text, style));
    }

    private void countText(int characters) {
        textCharacters += characters;
        if (textCharacters > MAX_TEXT_CHARACTERS) {
            throw new TemplateLayoutUnavailableException(
                    templateId, versionId, "the template holds more than " + MAX_TEXT_CHARACTERS + " characters of text.");
        }
    }

    /** Null when the template set none of the properties a page can show, so an unstyled run carries no empty object. */
    static TemplateLayout.Style styleOf(ResolvedStyle resolved) {
        if (resolved == null) {
            return null;
        }
        String color = resolved.colorHex() != null && COLOR_HEX.matcher(resolved.colorHex()).matches() ? resolved.colorHex() : null;
        TemplateLayout.Style style = new TemplateLayout.Style(
                resolved.bold(), resolved.italic(), resolved.underline(), resolved.fontFamily(), resolved.fontSizeHalfPoints(), color);
        return style.equals(new TemplateLayout.Style(null, null, null, null, null, null)) ? null : style;
    }

    /**
     * Word's own alignment words, read the way a left-to-right page shows
     * them. Anything else (the kashida and Thai spacing variants, a numbered
     * tab) has no faithful counterpart on a web page and is left unset.
     */
    static TemplateLayout.Alignment alignmentOf(ResolvedStyle style) {
        if (style == null || style.alignment() == null) {
            return null;
        }
        return switch (style.alignment().toLowerCase(Locale.ROOT)) {
            case "left", "start" -> TemplateLayout.Alignment.START;
            case "center" -> TemplateLayout.Alignment.CENTER;
            case "right", "end" -> TemplateLayout.Alignment.END;
            case "both", "distribute" -> TemplateLayout.Alignment.JUSTIFY;
            default -> null;
        };
    }

    /** Numbering id 0 is Word's way of switching numbering off for a paragraph, so it is not a list. */
    static Integer listLevelOf(ResolvedStyle style) {
        if (style == null || style.numberingId() == null || style.numberingId() == 0) {
            return null;
        }
        return style.numberingLevel() == null ? 0 : style.numberingLevel();
    }
}
