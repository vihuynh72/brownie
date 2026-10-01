package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the proposer against hand-built graphs, the same fake-graph
 * style {@link TemplateBindingValidatorTest} already uses -- what matters
 * here is the grouping/ambiguity/cardinality-inference logic, independent
 * of any real POI parsing.
 */
class FieldBindingCandidateProposerTest {

    private static final String PARSER_VERSION = "test-v1";

    @Test
    void aSingleContentControlTagIsProposedAsAScalarTextField() {
        StructuralNode control = contentControl("p0/sdt0", "meeting.title");
        DocxStructuralGraph graph = graphWithBody(control);

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(1, report.candidates().size());
        CandidateFieldBinding candidate = report.candidates().get(0);
        assertEquals("meeting.title", candidate.fieldId());
        assertEquals(FieldType.TEXT, candidate.type());
        assertEquals(FieldCardinality.SCALAR, candidate.cardinality());
        assertEquals(new FieldBindingTarget.ContentControlTag("meeting.title"), candidate.binding());
        assertTrue(report.ambiguousContentControlTags().isEmpty());
    }

    @Test
    void aTagContainingDateIsProposedAsADateField() {
        DocxStructuralGraph graph = graphWithBody(contentControl("p0/sdt0", "meeting.date"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(FieldType.DATE, report.candidates().get(0).type());
    }

    @Test
    void aTagContainingDateInAnyCaseIsStillProposedAsADateField() {
        DocxStructuralGraph graph = graphWithBody(contentControl("p0/sdt0", "Next.DUE.Date"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(FieldType.DATE, report.candidates().get(0).type());
    }

    @Test
    void aTagInsideAnotherWordIsNotADateField() {
        DocxStructuralGraph graph = graphWithBody(
                contentControl("p0/sdt0", "candidate.name"), contentControl("p1/sdt0", "updatedBy"), contentControl("p2/sdt0", "DueDate"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(FieldType.TEXT, typeOf(report, "candidate.name"));
        assertEquals(FieldType.TEXT, typeOf(report, "updatedBy"));
        assertEquals(FieldType.DATE, typeOf(report, "DueDate"));
    }

    @Test
    void aDateWordNextToDigitsOrCapitalsIsStillADateField() {
        DocxStructuralGraph graph = graphWithBody(
                contentControl("p0/sdt0", "Date1"), contentControl("p1/sdt0", "startDate2"), contentControl("p2/sdt0", "DOBDate"),
                contentControl("p3/sdt0", "UPDATED"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(FieldType.DATE, typeOf(report, "Date1"));
        assertEquals(FieldType.DATE, typeOf(report, "startDate2"));
        assertEquals(FieldType.DATE, typeOf(report, "DOBDate"));
        assertEquals(FieldType.TEXT, typeOf(report, "UPDATED"));
    }

    @Test
    void theOnePrototypeRowUnderAHeaderInTheFirstTableIsProposedAsRepeated() {
        StructuralNode table = table("p0/tbl0", headerRow("p0/tbl0/tr0", "Task"), controlRow("p0/tbl0/tr1", "action.item.task", "action.item.owner"));
        DocxStructuralGraph graph = graphWithBody(table);

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(2, report.candidates().size());
        assertTrue(report.candidates().stream().allMatch(candidate -> candidate.cardinality() == FieldCardinality.REPEATED));
    }

    /** A form laid out as a table of labels and answer boxes is not a list: each box is filled once. */
    @Test
    void aTableOfLabelsAndBoxesIsProposedAsSingleValues() {
        StructuralNode boxesInEveryRow = table(
                "p0/tbl0", controlRow("p0/tbl0/tr0", "applicant.name"), controlRow("p0/tbl0/tr1", "applicant.email"));
        StructuralNode oneRowOnly = table("p1/tbl0", controlRow("p1/tbl0/tr0", "applicant.phone"));
        DocxStructuralGraph graph = graphWithBody(boxesInEveryRow);
        DocxStructuralGraph single = graphWithBody(oneRowOnly);

        assertTrue(FieldBindingCandidateProposer.propose(graph).candidates().stream()
                .allMatch(candidate -> candidate.cardinality() == FieldCardinality.SCALAR));
        assertEquals(FieldCardinality.SCALAR, FieldBindingCandidateProposer.propose(single).candidates().get(0).cardinality());
    }

    /** The filler repeats a row of the first table only, so a prototype row in any later table is a single value. */
    @Test
    void aPrototypeRowInALaterTableIsProposedAsSingleValues() {
        StructuralNode first = table("p0/tbl0", headerRow("p0/tbl0/tr0", "Heading"), headerRow("p0/tbl0/tr1", "Text"));
        StructuralNode second = table("p1/tbl0", headerRow("p1/tbl0/tr0", "Task"), controlRow("p1/tbl0/tr1", "action.item.task"));
        DocxStructuralGraph graph = graphWithBody(first, second);

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(FieldCardinality.SCALAR, report.candidates().get(0).cardinality());
    }

    @Test
    void aContentControlOutsideAnyTableIsProposedAsScalarEvenNextToATable() {
        StructuralNode outside = contentControl("p1/sdt0", "meeting.title");
        StructuralNode table = table("p0/tbl0", headerRow("p0/tbl0/tr0", "Task"), controlRow("p0/tbl0/tr1", "action.item.task"));
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(table, outside));
        DocxStructuralGraph graph = new DocxStructuralGraph(
                PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(2, report.candidates().size());
        assertEquals(
                FieldCardinality.SCALAR,
                report.candidates().stream().filter(c -> c.fieldId().equals("meeting.title")).findFirst().orElseThrow().cardinality());
        assertEquals(
                FieldCardinality.REPEATED,
                report.candidates().stream().filter(c -> c.fieldId().equals("action.item.task")).findFirst().orElseThrow().cardinality());
    }

    @Test
    void aTagAppearingAtMoreThanOneLocationIsReportedAsAmbiguousNotProposed() {
        StructuralNode first = contentControl("p0/sdt0", "meeting.title");
        StructuralNode second = contentControl("p1/sdt0", "meeting.title");
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(first, second));
        DocxStructuralGraph graph = new DocxStructuralGraph(
                PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertTrue(report.candidates().isEmpty());
        assertEquals(List.of("meeting.title"), report.ambiguousContentControlTags());
    }

    /** An empty tag, or one of nothing but spaces, names nothing: two of them are not one shared tag either. */
    @Test
    void aBlankTagIsTreatedLikeNoTagAtAll() {
        DocxStructuralGraph graph = graphWithBody(
                contentControl("p0/sdt0", ""),
                contentControl("p1/sdt0", ""),
                contentControl("p2/sdt0", "   "),
                contentControl("p3/sdt0", "meeting.title"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(List.of("meeting.title"), report.candidates().stream().map(CandidateFieldBinding::fieldId).toList());
        assertTrue(report.ambiguousContentControlTags().isEmpty());
    }

    /** A blank-tagged box in the header row (a check box, say) does not stop the row under it from repeating. */
    @Test
    void aBlankTaggedControlAboveThePrototypeRowDoesNotStopItRepeating() {
        StructuralNode blankTaggedHeader = new StructuralNode(
                "p0/tbl0/tr0", StructuralNodeKind.TABLE_ROW, null, null, null, null,
                List.of(new StructuralNode(
                        "p0/tbl0/tr0/tc0", StructuralNodeKind.TABLE_CELL, null, null, null, null,
                        List.of(contentControl("p0/tbl0/tr0/tc0/sdt0", " ")))));
        StructuralNode table = table("p0/tbl0", blankTaggedHeader, controlRow("p0/tbl0/tr1", "action.item.task"));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graphWithBody(table));

        assertEquals(FieldCardinality.REPEATED, report.candidates().get(0).cardinality());
    }

    /** Only the body's untagged controls are counted: in a header or footer a tag would not get one filled. */
    @Test
    void theBodysControlsWithNoUsableTagAreCounted() {
        StructuralNode body = new StructuralNode(
                "body", StructuralNodeKind.BODY, null, null, null, null,
                List.of(
                        contentControl("p0/sdt0", null),
                        contentControl("p1/sdt0", ""),
                        table("p2/tbl0", controlRow("p2/tbl0/tr0", "  ")),
                        contentControl("p3/sdt0", "meeting.title")));
        StructuralNode header = new StructuralNode(
                "header", StructuralNodeKind.BODY, null, null, null, null, List.of(contentControl("p0/sdt0", null)));
        DocxStructuralGraph graph = new DocxStructuralGraph(
                PARSER_VERSION,
                List.of(
                        new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body),
                        new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, header)));

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(3, report.untaggedContentControlCount());
        assertEquals(1, report.candidates().size());
        assertEquals(0, FieldBindingCandidateProposer.propose(graphWithBody(contentControl("p0/sdt0", "meeting.title"))).untaggedContentControlCount());
    }

    @Test
    void aGraphWithNoContentControlsProposesNothing() {
        StructuralNode paragraph = new StructuralNode(
                "p0", StructuralNodeKind.PARAGRAPH, null, null, null, null,
                List.of(new StructuralNode("p0/r0", StructuralNodeKind.RUN, null, "Meeting Title:", null, null, List.of())));
        DocxStructuralGraph graph = graphWithBody(paragraph);

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertTrue(report.candidates().isEmpty());
        assertTrue(report.ambiguousContentControlTags().isEmpty());
    }

    private static FieldType typeOf(CandidateBindingReport report, String fieldId) {
        return report.candidates().stream().filter(c -> c.fieldId().equals(fieldId)).findFirst().orElseThrow().type();
    }

    private static StructuralNode table(String nodeId, StructuralNode... rows) {
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE, null, null, null, null, List.of(rows));
    }

    private static StructuralNode headerRow(String nodeId, String text) {
        StructuralNode run = new StructuralNode(nodeId + "/tc0/p0/r0", StructuralNodeKind.RUN, null, text, null, null, List.of());
        StructuralNode paragraph = new StructuralNode(nodeId + "/tc0/p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run));
        StructuralNode cell = new StructuralNode(nodeId + "/tc0", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(paragraph));
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(cell));
    }

    private static StructuralNode controlRow(String nodeId, String... tags) {
        List<StructuralNode> cells = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) {
            StructuralNode control = contentControl(nodeId + "/tc" + i + "/sdt0", tags[i]);
            cells.add(new StructuralNode(nodeId + "/tc" + i, StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(control)));
        }
        return new StructuralNode(nodeId, StructuralNodeKind.TABLE_ROW, null, null, null, null, cells);
    }

    private static StructuralNode contentControl(String nodeId, String tag) {
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
    }

    private static DocxStructuralGraph graphWithBody(StructuralNode... topLevelChildren) {
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(topLevelChildren));
        return new DocxStructuralGraph(PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }
}
