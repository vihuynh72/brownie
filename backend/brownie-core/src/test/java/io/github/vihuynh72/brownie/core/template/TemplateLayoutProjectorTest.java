package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the projection against hand-built graphs, the same fake-graph
 * style {@link FieldBindingCandidateProposerTest} uses: what matters here is
 * that a fill spot appears exactly where the filler writes a value, and that
 * the template's own text and style come through unchanged.
 */
class TemplateLayoutProjectorTest {

    private static final String PARSER_VERSION = "test-v1";
    private static final ResolvedStyle LABEL = runStyle(true, 22);
    private static final ResolvedStyle BODY = runStyle(null, 22);
    private static final ResolvedStyle HEADING = runStyle(true, 32);

    @Test
    void aBoundControlBecomesAFillSpotStyledLikeItsFirstRunWithItsOwnTextAsPlaceholder() {
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null,
                        run("p0/r0", "Title: ", LABEL),
                        control("p0/sdt1", "meeting.title",
                                run("p0/sdt1/r0", " [meeting ", BODY),
                                run("p0/sdt1/r1", "title] ", HEADING)))));

        TemplateLayout layout = TemplateLayoutProjector.project(3L, 4L, graph, List.of(scalar("meeting.title")));

        assertEquals(3L, layout.templateId());
        assertEquals(4L, layout.versionId());
        assertEquals(PARSER_VERSION, layout.parserVersion());
        List<TemplateLayout.Inline> inlines = onlyParagraph(layout).inlines();
        assertEquals(new TemplateLayout.Text("Title: ", style(true, 22), 0, null), inlines.get(0));
        assertEquals(
                new TemplateLayout.FillSpot("meeting.title", "[meeting title]", style(null, 22), "p0/sdt1", SpotOrigin.FORM, "Meeting title"),
                inlines.get(1));
        assertEquals(2, inlines.size());
        assertTrue(layout.unplacedFieldIds().isEmpty());
    }

    /** A form's line to write on is not a hint of what goes there, so the spot has no placeholder and is named by its field. */
    @Test
    void aPlaceholderThatIsOnlyALineToWriteOnIsNoPlaceholder() {
        List<String> blanks = List.of("__________", ". . . . . .", "\u2026\u2026", "- - -", "_\t_\u00A0_", "   ");
        for (String blank : blanks) {
            DocxStructuralGraph graph = graphOf(main(
                    paragraph("p0", null, run("p0/r0", "Name: ", LABEL), control("p0/sdt1", "client.name", run("p0/sdt1/r0", blank, BODY)))));

            TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of(scalar("client.name")));

            assertEquals(
                    new TemplateLayout.FillSpot("client.name", null, style(null, 22), "p0/sdt1", SpotOrigin.FORM, "Client name"),
                    onlyParagraph(layout).inlines().get(1), blank);
        }
        DocxStructuralGraph words = graphOf(main(
                paragraph("p0", null, control("p0/sdt0", "client.name", run("p0/sdt0/r0", "Full name ______", BODY)))));
        assertEquals(new TemplateLayout.FillSpot("client.name", "Full name ______", style(null, 22), "p0/sdt0", SpotOrigin.FORM, "Client name"),
                onlyParagraph(TemplateLayoutProjector.project(1L, 1L, words, List.of(scalar("client.name")))).inlines().getFirst());
    }

    /**
     * The control's text is shown as the template's own, but kept apart from
     * the text around it: a place in it is the control itself, not an offset
     * into the paragraph's own text.
     */
    @Test
    void aControlWhoseTagNoFieldNamesIsOrdinaryTemplateTextKeptApartFromItsNeighbours() {
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null,
                        run("p0/r0", "Status: ", BODY),
                        control("p0/sdt1", "internal.note", run("p0/sdt1/r0", "Draft", BODY)),
                        run("p0/r2", " only", BODY))));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of(scalar("meeting.title")));

        assertEquals(
                List.of(
                        new TemplateLayout.Text("Status: ", style(null, 22), 0, null),
                        new TemplateLayout.Text("Draft", style(null, 22), null, "p0/sdt1"),
                        new TemplateLayout.Text(" only", style(null, 22), 8, null)),
                onlyParagraph(layout).inlines());
        assertEquals(List.of("meeting.title"), layout.unplacedFieldIds());
    }

    /** A field bound to a place on a PDF has no place in a Word page, so it is listed as unplaced rather than dropped. */
    @Test
    void aFieldBoundToAPlaceOnAPdfIsUnplacedOnAWordPage() {
        DocxStructuralGraph graph = graphOf(main(paragraph("p0", null, run("p0/r0", "Status", BODY))));
        FieldDefinition onPdf = new FieldDefinition("form.name", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.AcroFormField("fullName"));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of(onPdf));

        assertEquals(List.of("form.name"), layout.unplacedFieldIds());
    }

    @Test
    void runsJoinOnlyWhileTheirStyleMatchesAndEveryCharacterIsKept() {
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null,
                        run("p0/r0", "  Hello", BODY),
                        run("p0/r1", "", LABEL),
                        run("p0/r2", " ", BODY),
                        run("p0/r3", "world  ", BODY),
                        run("p0/r4", "Bold\ttail", LABEL))));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of());

        assertEquals(
                List.of(
                        new TemplateLayout.Text("  Hello world  ", style(null, 22), 0, null),
                        new TemplateLayout.Text("Bold\ttail", style(true, 22), 15, null)),
                onlyParagraph(layout).inlines());
    }

    @Test
    void theLastRowOfTheFirstTableRepeatsWhenItHoldsAControlForEveryRepeatedField() {
        StructuralNode actions = table("tbl1",
                row("tbl1/row0",
                        cell("tbl1/row0/cell0", paragraph("tbl1/row0/cell0/p0", null, run("tbl1/row0/cell0/p0/r0", "Task", LABEL))),
                        cell("tbl1/row0/cell1", paragraph("tbl1/row0/cell1/p0", null, run("tbl1/row0/cell1/p0/r0", "Owner", LABEL)))),
                row("tbl1/row1",
                        cell("tbl1/row1/cell0", paragraph("tbl1/row1/cell0/p0", null,
                                control("tbl1/row1/cell0/p0/sdt0", "action.item.task", run("tbl1/row1/cell0/p0/sdt0/r0", "[task]", BODY)))),
                        cell("tbl1/row1/cell1", paragraph("tbl1/row1/cell1/p0", null,
                                control("tbl1/row1/cell1/p0/sdt0", "action.item.owner", run("tbl1/row1/cell1/p0/sdt0/r0", "[owner]", BODY))))));
        StructuralNode secondTable = table("tbl2",
                row("tbl2/row0", cell("tbl2/row0/cell0", paragraph("tbl2/row0/cell0/p0", null, run("tbl2/row0/cell0/p0/r0", "Notes", BODY)))));
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null, run("p0/r0", "Action Items", HEADING)), actions, secondTable));

        TemplateLayout layout = TemplateLayoutProjector.project(
                1L, 1L, graph, List.of(repeated("action.item.task"), repeated("action.item.owner")));

        List<TemplateLayout.Block> blocks = layout.parts().getFirst().blocks();
        TemplateLayout.Table first = assertInstanceOf(TemplateLayout.Table.class, blocks.get(1));
        assertFalse(first.rows().get(0).repeating());
        assertTrue(first.rows().get(1).repeating());
        TemplateLayout.Paragraph ownerCell = assertInstanceOf(
                TemplateLayout.Paragraph.class, first.rows().get(1).cells().get(1).blocks().getFirst());
        assertEquals(
                List.of(new TemplateLayout.FillSpot(
                        "action.item.owner", "[owner]", style(null, 22), "tbl1/row1/cell1/p0/sdt0", SpotOrigin.FORM, "Action item owner")),
                ownerCell.inlines());
        // Nothing can be added in the row that repeats per item, or it would repeat too.
        assertFalse(ownerCell.anchorable());
        assertTrue(((TemplateLayout.Paragraph) first.rows().get(0).cells().get(0).blocks().getFirst()).anchorable());
        TemplateLayout.Table second = assertInstanceOf(TemplateLayout.Table.class, blocks.get(2));
        assertFalse(second.rows().getFirst().repeating());
        assertFalse(((TemplateLayout.Paragraph) blocks.get(0)).repeating());
        assertTrue(layout.unplacedFieldIds().isEmpty());
    }

    @Test
    void withoutAFullTableRowTheFirstTopLevelParagraphHoldingTheFirstRepeatedFieldRepeats() {
        // The table's last row lacks an owner control, so the filler falls back to paragraphs; a paragraph inside a
        // table cell is never one it copies, whatever it holds.
        StructuralNode partialTable = table("tbl0",
                row("tbl0/row0", cell("tbl0/row0/cell0", paragraph("tbl0/row0/cell0/p0", null,
                        control("tbl0/row0/cell0/p0/sdt0", "action.item.task", run("tbl0/row0/cell0/p0/sdt0/r0", "[task]", BODY))))));
        StructuralNode itemParagraph = paragraph("p1", null,
                run("p1/r0", "Task: ", LABEL),
                control("p1/sdt1", "action.item.task", run("p1/sdt1/r0", "[task]", BODY)),
                run("p1/r2", "   Owner: ", LABEL),
                control("p1/sdt3", "action.item.owner", run("p1/sdt3/r0", "[owner]", BODY)));
        StructuralNode anotherParagraph = paragraph("p2", null, control("p2/sdt0", "action.item.task", run("p2/sdt0/r0", "[again]", BODY)));
        DocxStructuralGraph graph = graphOf(main(partialTable, itemParagraph, anotherParagraph));

        TemplateLayout layout = TemplateLayoutProjector.project(
                1L, 1L, graph, List.of(repeated("action.item.task"), repeated("action.item.owner")));

        List<TemplateLayout.Block> blocks = layout.parts().getFirst().blocks();
        TemplateLayout.Table table = (TemplateLayout.Table) blocks.get(0);
        assertFalse(table.rows().getFirst().repeating());
        TemplateLayout.Paragraph cellParagraph = (TemplateLayout.Paragraph) table.rows().getFirst().cells().getFirst().blocks().getFirst();
        assertFalse(cellParagraph.repeating());
        assertTrue(((TemplateLayout.Paragraph) blocks.get(1)).repeating());
        TemplateLayout.Paragraph another = (TemplateLayout.Paragraph) blocks.get(2);
        assertFalse(another.repeating());
        // A repeated field's control elsewhere still shows as a fill spot.
        assertEquals(
                List.of(new TemplateLayout.FillSpot("action.item.task", "[again]", style(null, 22), "p2/sdt0", SpotOrigin.FORM, "Action item task")),
                another.inlines());
        assertFalse(((TemplateLayout.Paragraph) blocks.get(1)).anchorable());
        assertTrue(another.anchorable());
    }

    @Test
    void aTemplateWithNoRepeatedFieldsRepeatsNothing() {
        DocxStructuralGraph graph = graphOf(main(
                table("tbl0", row("tbl0/row0", cell("tbl0/row0/cell0", paragraph("tbl0/row0/cell0/p0", null,
                        control("tbl0/row0/cell0/p0/sdt0", "meeting.title", run("tbl0/row0/cell0/p0/sdt0/r0", "[title]", BODY))))))));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of(scalar("meeting.title")));

        TemplateLayout.Table table = (TemplateLayout.Table) layout.parts().getFirst().blocks().getFirst();
        assertFalse(table.rows().getFirst().repeating());
    }

    @Test
    void aStructuralNodeBindingAndATagFoundNowhereAreUnplacedInFieldOrder() {
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null, run("p0/r0", "Meeting Minutes", HEADING)),
                paragraph("p1", null, control("p1/sdt0", "meeting.title", run("p1/sdt0/r0", "[title]", BODY)))));
        FieldDefinition structural = new FieldDefinition(
                "meeting.heading", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p0/r0"));

        TemplateLayout layout = TemplateLayoutProjector.project(
                1L, 1L, graph, List.of(scalar("meeting.location"), structural, scalar("meeting.title")));

        assertEquals(List.of("meeting.location", "meeting.heading"), layout.unplacedFieldIds());
        // The node a structural binding names is still drawn, as the template's own text.
        assertEquals(
                List.of(new TemplateLayout.Text("Meeting Minutes", style(true, 32), 0, null)),
                ((TemplateLayout.Paragraph) layout.parts().getFirst().blocks().getFirst()).inlines());
    }

    @Test
    void headerAndFooterControlsStayTextBecauseTheFillerNeverWritesThereAndPartsComeMainFirst() {
        DocumentPart footer = new DocumentPart("word/footer1.xml", DocumentPartKind.FOOTER, body(
                paragraph("p0", null, run("p0/r0", "Page ", BODY), run("p0/r1", "", null), run("p0/r2", "1", BODY))));
        DocumentPart header = new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, body(
                paragraph("p0", null, control("p0/sdt0", "meeting.organization", run("p0/sdt0/r0", "[organization]", BODY)))));
        DocumentPart main = main(paragraph("p0", null, run("p0/r0", "Body", BODY)));
        DocxStructuralGraph graph = new DocxStructuralGraph(PARSER_VERSION, List.of(footer, header, main));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of(scalar("meeting.organization")));

        assertEquals(
                List.of(DocumentPartKind.MAIN_DOCUMENT, DocumentPartKind.HEADER, DocumentPartKind.FOOTER),
                layout.parts().stream().map(TemplateLayout.Part::kind).toList());
        TemplateLayout.Paragraph headerParagraph = (TemplateLayout.Paragraph) layout.parts().get(1).blocks().getFirst();
        assertEquals(List.of(new TemplateLayout.Text("[organization]", style(null, 22))), headerParagraph.inlines());
        TemplateLayout.Paragraph footerParagraph = (TemplateLayout.Paragraph) layout.parts().get(2).blocks().getFirst();
        assertEquals(List.of(new TemplateLayout.Text("Page 1", style(null, 22))), footerParagraph.inlines());
        assertEquals(List.of("meeting.organization"), layout.unplacedFieldIds());
    }

    @Test
    void emptyParagraphsAndImagesAreKeptAndTheBodyRootIsNotABlock() {
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null, image("p0/r0")),
                paragraph("p1", null),
                new StructuralNode("body2", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of()),
                paragraph("p3", null, control("p3/sdt0", "unbound", image("p3/sdt0/r0"), run("p3/sdt0/r1", "caption", BODY)))));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of());

        List<TemplateLayout.Block> blocks = layout.parts().getFirst().blocks();
        assertEquals(4, blocks.size());
        assertEquals(List.of(new TemplateLayout.Image()), ((TemplateLayout.Paragraph) blocks.get(0)).inlines());
        assertTrue(((TemplateLayout.Paragraph) blocks.get(1)).inlines().isEmpty());
        TemplateLayout.Paragraph blockLevelControl = (TemplateLayout.Paragraph) blocks.get(2);
        assertTrue(blockLevelControl.inlines().isEmpty());
        assertNull(blockLevelControl.alignment());
        assertNull(blockLevelControl.listLevel());
        assertEquals(
                List.of(new TemplateLayout.Image(), new TemplateLayout.Text("caption", style(null, 22), null, "p3/sdt0")),
                ((TemplateLayout.Paragraph) blocks.get(3)).inlines());
    }

    @Test
    void aFillSpotWithNoTextHasNoPlaceholderAndOneWithAnImageFirstHasNoStyle() {
        DocxStructuralGraph graph = graphOf(main(paragraph("p0", null,
                control("p0/sdt0", "meeting.title", run("p0/sdt0/r0", "   ", BODY)),
                control("p0/sdt1", "meeting.location", image("p0/sdt1/r0"), run("p0/sdt1/r1", "[where]", BODY)),
                control("p0/sdt2", "meeting.date"))));

        TemplateLayout layout = TemplateLayoutProjector.project(
                1L, 1L, graph, List.of(scalar("meeting.title"), scalar("meeting.location"), scalar("meeting.date")));

        assertEquals(
                List.of(
                        new TemplateLayout.FillSpot("meeting.title", null, style(null, 22), "p0/sdt0", SpotOrigin.FORM, "Meeting title"),
                        new TemplateLayout.FillSpot("meeting.location", "[where]", null, "p0/sdt1", SpotOrigin.FORM, "Meeting location"),
                        new TemplateLayout.FillSpot("meeting.date", null, null, "p0/sdt2", SpotOrigin.FORM, "Meeting date")),
                onlyParagraph(layout).inlines());
    }

    /**
     * A place is sent back as code points into the paragraph's own runs: a
     * character outside the Basic Multilingual Plane counts once, a control's
     * text is not counted, and the hash is of exactly that text.
     */
    @Test
    void anAnchorableParagraphCountsItsOwnRunsInCodePointsAndCarriesTheHashOfItsAnchorText() {
        String smile = new String(Character.toChars(0x1F600));
        DocxStructuralGraph graph = graphOf(main(paragraph("p0", null,
                run("p0/r0", smile + " Name: ", LABEL),
                control("p0/sdt1", "unbound", run("p0/sdt1/r0", "[x]", BODY)),
                run("p0/r2", "____", BODY),
                image("p0/r3"),
                run("p0/r4", " end", BODY))));

        TemplateLayout.Paragraph paragraph = onlyParagraph(TemplateLayoutProjector.project(1L, 1L, graph, List.of()));

        assertEquals("p0", paragraph.nodeId());
        assertTrue(paragraph.anchorable());
        assertEquals(DocxAnchor.hashOf(smile + " Name: ____ end"), paragraph.anchorTextHash());
        assertEquals(
                List.of(
                        new TemplateLayout.Text(smile + " Name: ", style(true, 22), 0, null),
                        new TemplateLayout.Text("[x]", style(null, 22), null, "p0/sdt1"),
                        new TemplateLayout.Text("____", style(null, 22), 8, null),
                        new TemplateLayout.Image(),
                        new TemplateLayout.Text(" end", style(null, 22), 12, null)),
                paragraph.inlines());
    }

    @Test
    void headersFootersAndControlsAroundWholeParagraphsAreNotAnchorable() {
        DocumentPart header = new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, body(
                paragraph("p0", null, run("p0/r0", "Company letterhead", BODY))));
        DocumentPart main = main(
                paragraph("p0", null, run("p0/r0", "Body", BODY)),
                new StructuralNode("body1", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of()),
                table("tbl2", row("tbl2/row0", cell("tbl2/row0/cell0", paragraph("tbl2/row0/cell0/p0", null, run("tbl2/row0/cell0/p0/r0", "Cell", BODY))))));
        DocxStructuralGraph graph = new DocxStructuralGraph(PARSER_VERSION, List.of(header, main));

        TemplateLayout layout = TemplateLayoutProjector.project(1L, 1L, graph, List.of());

        List<TemplateLayout.Block> body = layout.parts().getFirst().blocks();
        TemplateLayout.Paragraph bodyParagraph = (TemplateLayout.Paragraph) body.get(0);
        assertTrue(bodyParagraph.anchorable());
        assertEquals(DocxAnchor.hashOf("Body"), bodyParagraph.anchorTextHash());
        TemplateLayout.Paragraph blockControl = (TemplateLayout.Paragraph) body.get(1);
        assertFalse(blockControl.anchorable());
        assertNull(blockControl.anchorTextHash());
        TemplateLayout.Paragraph cellParagraph =
                (TemplateLayout.Paragraph) ((TemplateLayout.Table) body.get(2)).rows().getFirst().cells().getFirst().blocks().getFirst();
        assertTrue(cellParagraph.anchorable());
        assertEquals("tbl2/row0/cell0/p0", cellParagraph.nodeId());
        TemplateLayout.Paragraph headerParagraph = (TemplateLayout.Paragraph) layout.parts().get(1).blocks().getFirst();
        assertFalse(headerParagraph.anchorable());
        assertNull(headerParagraph.anchorTextHash());
        assertEquals(List.of(new TemplateLayout.Text("Company letterhead", style(null, 22))), headerParagraph.inlines());
    }

    @Test
    void aSpotCarriesWhoPlacedItAndTheLabelThePersonSees() {
        DocxStructuralGraph graph = graphOf(main(paragraph("p0", null,
                control("p0/sdt0", "company", run("p0/sdt0/r0", "____", BODY)),
                control("p0/sdt1", "birth.date", run("p0/sdt1/r0", "", BODY)))));
        FieldDefinition found = new FieldDefinition(
                "company", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag("company"),
                "Company name", SpotOrigin.FOUND_BY_BROWNIE, DocxControlOrigin.INSERTED_BY_BROWNIE, "____");
        FieldDefinition added = new FieldDefinition(
                "birth.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("birth.date"), null, SpotOrigin.ADDED_BY_PERSON, null, null);

        List<TemplateLayout.Inline> inlines = onlyParagraph(TemplateLayoutProjector.project(1L, 1L, graph, List.of(found, added))).inlines();

        assertEquals(new TemplateLayout.FillSpot("company", null, style(null, 22), "p0/sdt0", SpotOrigin.FOUND_BY_BROWNIE, "Company name"),
                inlines.get(0));
        assertEquals(new TemplateLayout.FillSpot("birth.date", null, style(null, 22), "p0/sdt1", SpotOrigin.ADDED_BY_PERSON, "Birth date"),
                inlines.get(1));
    }

    @Test
    void alignmentIsNormalizedToTheFourAPageCanShow() {
        assertEquals(TemplateLayout.Alignment.START, TemplateLayoutProjector.alignmentOf(paragraphStyle("left")));
        assertEquals(TemplateLayout.Alignment.START, TemplateLayoutProjector.alignmentOf(paragraphStyle("start")));
        assertEquals(TemplateLayout.Alignment.CENTER, TemplateLayoutProjector.alignmentOf(paragraphStyle("center")));
        assertEquals(TemplateLayout.Alignment.END, TemplateLayoutProjector.alignmentOf(paragraphStyle("right")));
        assertEquals(TemplateLayout.Alignment.END, TemplateLayoutProjector.alignmentOf(paragraphStyle("end")));
        assertEquals(TemplateLayout.Alignment.JUSTIFY, TemplateLayoutProjector.alignmentOf(paragraphStyle("both")));
        assertEquals(TemplateLayout.Alignment.JUSTIFY, TemplateLayoutProjector.alignmentOf(paragraphStyle("distribute")));
        assertNull(TemplateLayoutProjector.alignmentOf(paragraphStyle("mediumKashida")));
        assertNull(TemplateLayoutProjector.alignmentOf(paragraphStyle("numTab")));
        assertNull(TemplateLayoutProjector.alignmentOf(paragraphStyle(null)));
        assertNull(TemplateLayoutProjector.alignmentOf(null));

        DocxStructuralGraph graph = graphOf(main(paragraph("p0", paragraphStyle("center"), run("p0/r0", "Centred", BODY))));
        assertEquals(
                TemplateLayout.Alignment.CENTER,
                onlyParagraph(TemplateLayoutProjector.project(1L, 1L, graph, List.of())).alignment());
    }

    @Test
    void aNumberedParagraphCarriesItsLevelAndNumberingZeroIsNotAList() {
        ResolvedStyle numbered = new ResolvedStyle(null, null, null, null, null, null, null, 3, 2);
        ResolvedStyle numberedWithoutLevel = new ResolvedStyle(null, null, null, null, null, null, null, 3, null);
        ResolvedStyle numberingOff = new ResolvedStyle(null, null, null, null, null, null, null, 0, 0);

        assertEquals(2, TemplateLayoutProjector.listLevelOf(numbered));
        assertEquals(0, TemplateLayoutProjector.listLevelOf(numberedWithoutLevel));
        assertNull(TemplateLayoutProjector.listLevelOf(numberingOff));
        assertNull(TemplateLayoutProjector.listLevelOf(paragraphStyle("left")));
        assertNull(TemplateLayoutProjector.listLevelOf(null));
    }

    @Test
    void onlyASixDigitHexColourIsPassedOnAndAnUnsetStyleIsNull() {
        assertEquals("FF00aa", TemplateLayoutProjector.styleOf(colored("FF00aa")).colorHex());
        assertNull(TemplateLayoutProjector.styleOf(colored("auto")).colorHex());
        assertNull(TemplateLayoutProjector.styleOf(colored("#FF0000")).colorHex());
        assertNull(TemplateLayoutProjector.styleOf(colored("FFF")).colorHex());
        assertNull(TemplateLayoutProjector.styleOf(colored("FF0000;background:url(x)")).colorHex());
        assertNull(TemplateLayoutProjector.styleOf(new ResolvedStyle(null, null, null, null, null, "auto", null, null, null)));
        assertNull(TemplateLayoutProjector.styleOf(null));
        assertEquals(
                new TemplateLayout.Style(false, true, true, "Liberation Sans", 22, "000000"),
                TemplateLayoutProjector.styleOf(new ResolvedStyle(false, true, true, "Liberation Sans", 22, "000000", null, null, null)));
    }

    @Test
    void aTemplateHoldingMoreTextThanAnyRealOneIsRefused() {
        String chunk = "x".repeat(100_000);
        DocxStructuralGraph graph = graphOf(main(
                paragraph("p0", null, run("p0/r0", chunk, BODY), run("p0/r1", chunk, BODY)),
                paragraph("p1", null, run("p1/r0", chunk, BODY), run("p1/r1", chunk, BODY)),
                paragraph("p2", null, run("p2/r0", "y", BODY))));

        TemplateLayoutUnavailableException refused = assertThrows(
                TemplateLayoutUnavailableException.class, () -> TemplateLayoutProjector.project(5L, 6L, graph, List.of()));
        assertTrue(refused.getMessage().contains("version 6 of template 5"), refused.getMessage());

        DocxStructuralGraph exactlyAtTheLimit = graphOf(main(
                paragraph("p0", null, run("p0/r0", chunk, BODY), run("p0/r1", chunk, BODY)),
                paragraph("p1", null, run("p1/r0", chunk, BODY), run("p1/r1", chunk, BODY))));
        assertEquals(1, TemplateLayoutProjector.project(5L, 6L, exactlyAtTheLimit, List.of()).parts().size());
    }

    private static TemplateLayout.Paragraph onlyParagraph(TemplateLayout layout) {
        List<TemplateLayout.Block> blocks = layout.parts().getFirst().blocks();
        assertEquals(1, blocks.size());
        return assertInstanceOf(TemplateLayout.Paragraph.class, blocks.getFirst());
    }

    private static ResolvedStyle runStyle(Boolean bold, Integer halfPoints) {
        return new ResolvedStyle(bold, null, null, "Liberation Sans", halfPoints, "000000", null, null, null);
    }

    private static TemplateLayout.Style style(Boolean bold, Integer halfPoints) {
        return new TemplateLayout.Style(bold, null, null, "Liberation Sans", halfPoints, "000000");
    }

    private static ResolvedStyle paragraphStyle(String alignment) {
        return new ResolvedStyle(null, null, null, null, null, null, alignment, null, null);
    }

    private static ResolvedStyle colored(String colorHex) {
        return new ResolvedStyle(null, null, null, "Liberation Sans", null, colorHex, null, null, null);
    }

    private static StructuralNode run(String nodeId, String text, ResolvedStyle style) {
        return new StructuralNode(nodeId, StructuralNodeKind.RUN, style, text, null, null, List.of());
    }

    private static StructuralNode image(String nodeId) {
        return new StructuralNode(nodeId, StructuralNodeKind.IMAGE, null, null, null, "rId5", List.of());
    }

    private static StructuralNode control(String nodeId, String tag, StructuralNode... runs) {
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of(runs));
    }

    private static StructuralNode paragraph(String nodeId, ResolvedStyle style, StructuralNode... children) {
        return new StructuralNode(nodeId, StructuralNodeKind.PARAGRAPH, style, null, null, null, List.of(children));
    }

    private static StructuralNode table(String nodeId, StructuralNode... rows) {
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE, null, null, null, null, List.of(rows));
    }

    private static StructuralNode row(String nodeId, StructuralNode... cells) {
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(cells));
    }

    private static StructuralNode cell(String nodeId, StructuralNode... paragraphs) {
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(paragraphs));
    }

    private static StructuralNode body(StructuralNode... children) {
        return new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(children));
    }

    private static DocumentPart main(StructuralNode... children) {
        return new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body(children));
    }

    private static DocxStructuralGraph graphOf(DocumentPart main) {
        return new DocxStructuralGraph(PARSER_VERSION, List.of(main));
    }

    private static FieldDefinition scalar(String fieldId) {
        return new FieldDefinition(
                fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static FieldDefinition repeated(String fieldId) {
        return new FieldDefinition(
                fieldId, FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }
}
