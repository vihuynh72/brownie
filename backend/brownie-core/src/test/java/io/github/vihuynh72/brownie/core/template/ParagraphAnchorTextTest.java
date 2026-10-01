package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A paragraph's anchor text counts its own runs in code points; a control's text and a picture add nothing. */
class ParagraphAnchorTextTest {

    @Test
    void theAnchorTextIsTheParagraphsOwnRunsAndTheirPlacesCountCodePoints() {
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(
                run("p0/r0", "Name \uD83D\uDE00: "),
                new StructuralNode("p0/sdt1", StructuralNodeKind.CONTENT_CONTROL, null, null, "name", null,
                        List.of(run("p0/sdt1/r0", "Ada"))),
                new StructuralNode("p0/r2", StructuralNodeKind.IMAGE, null, null, null, "rId4", List.of()),
                run("p0/r3", "____")));

        assertEquals("Name \uD83D\uDE00: ____", ParagraphAnchorText.of(paragraph));
        assertEquals(List.of(
                new ParagraphAnchorText.RunSpan("p0/r0", 0, 8),
                new ParagraphAnchorText.RunSpan("p0/r3", 8, 12)), ParagraphAnchorText.runSpans(paragraph));
    }

    @Test
    void onlyAParagraphHasAnchorText() {
        assertThrows(IllegalArgumentException.class, () -> ParagraphAnchorText.of(run("p0/r0", "x")));
    }

    private static StructuralNode run(String nodeId, String text) {
        return new StructuralNode(nodeId, StructuralNodeKind.RUN, null, text, null, null, List.of());
    }
}
