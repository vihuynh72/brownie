package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import org.junit.jupiter.api.Test;

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
    void aContentControlInsideATableCellIsProposedAsRepeated() {
        StructuralNode control = contentControl("p0/tbl0/tr0/tc0/sdt0", "action.item.task");
        StructuralNode cell = new StructuralNode("p0/tbl0/tr0/tc0", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(control));
        StructuralNode row = new StructuralNode("p0/tbl0/tr0", StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(cell));
        StructuralNode table = new StructuralNode("p0/tbl0", StructuralNodeKind.TABLE, null, null, null, null, List.of(row));
        DocxStructuralGraph graph = graphWithBody(table);

        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph);

        assertEquals(1, report.candidates().size());
        assertEquals(FieldCardinality.REPEATED, report.candidates().get(0).cardinality());
    }

    @Test
    void aContentControlOutsideAnyTableIsProposedAsScalarEvenNextToATable() {
        StructuralNode outside = contentControl("p1/sdt0", "meeting.title");
        StructuralNode cellControl = contentControl("p0/tbl0/tr0/tc0/sdt0", "action.item.task");
        StructuralNode cell = new StructuralNode("p0/tbl0/tr0/tc0", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(cellControl));
        StructuralNode row = new StructuralNode("p0/tbl0/tr0", StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(cell));
        StructuralNode table = new StructuralNode("p0/tbl0", StructuralNodeKind.TABLE, null, null, null, null, List.of(row));
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

    private static StructuralNode contentControl(String nodeId, String tag) {
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
    }

    private static DocxStructuralGraph graphWithBody(StructuralNode... topLevelChildren) {
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(topLevelChildren));
        return new DocxStructuralGraph(PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }
}
