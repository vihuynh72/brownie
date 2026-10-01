package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.text.CodePoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A paragraph's anchor text: the text of its own runs, joined in order, as
 * the structural graph reads them. A place in a paragraph is sent and
 * stored as code-point offsets into this text, so everything that makes or
 * reads such a place (the fill spots found in an upload, a spot a person
 * adds on the page, the editor that turns either into a control) counts
 * the same characters from here.
 *
 * <p>A control's own text is not part of it: a control is already a
 * place of its own and is named by its node id. A picture adds nothing. A
 * run inside a hyperlink is one of the paragraph's runs in the graph, so
 * its text counts; the editor refuses to split it.
 */
public final class ParagraphAnchorText {

    private ParagraphAnchorText() {
    }

    /** Where one run's text sits in the anchor text, in code points; {@code end} is exclusive. */
    public record RunSpan(String runNodeId, int start, int end) {
    }

    /** The anchor text of a PARAGRAPH node. */
    public static String of(StructuralNode paragraph) {
        requireParagraph(paragraph);
        StringBuilder text = new StringBuilder();
        for (StructuralNode child : paragraph.children()) {
            if (child.kind() == StructuralNodeKind.RUN && child.text() != null) {
                text.append(child.text());
            }
        }
        return text.toString();
    }

    /** Each run's place in {@link #of}, in order, runs with no text included (at a zero-width span). */
    public static List<RunSpan> runSpans(StructuralNode paragraph) {
        requireParagraph(paragraph);
        List<RunSpan> spans = new ArrayList<>();
        int offset = 0;
        for (StructuralNode child : paragraph.children()) {
            if (child.kind() != StructuralNodeKind.RUN) {
                continue;
            }
            int length = child.text() == null ? 0 : CodePoints.length(child.text());
            spans.add(new RunSpan(child.nodeId(), offset, offset + length));
            offset += length;
        }
        return List.copyOf(spans);
    }

    private static void requireParagraph(StructuralNode paragraph) {
        Objects.requireNonNull(paragraph, "paragraph");
        if (paragraph.kind() != StructuralNodeKind.PARAGRAPH) {
            throw new IllegalArgumentException("Anchor text belongs to a paragraph, not a " + paragraph.kind() + ".");
        }
    }
}
