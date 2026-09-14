package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link LayoutComparator} against hand-built graphs, the same fake-graph style {@code FieldBindingCandidateProposerTest}/{@code DocumentValidatorTest} already use. */
class LayoutComparatorTest {

    private static final List<FieldDefinition> SCALAR_FIELD = List.of(new FieldDefinition(
            "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
            new FieldBindingTarget.ContentControlTag("meeting.title")));

    private static final List<FieldDefinition> REPEATED_FIELD = List.of(new FieldDefinition(
            "action.item.task", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
            new FieldBindingTarget.ContentControlTag("action.item.task")));

    @Test
    void identicalProtectedContentProducesNoFinding() {
        DocxStructuralGraph baseline = graphWithHeadingAndField("Club Minutes", "Sample value");
        DocxStructuralGraph filled = graphWithHeadingAndField("Club Minutes", "Real meeting title");

        assertTrue(LayoutComparator.compareStructure(baseline, filled, SCALAR_FIELD).isEmpty());
    }

    @Test
    void changedProtectedHeadingTextIsReported() {
        DocxStructuralGraph baseline = graphWithHeadingAndField("Club Minutes", "Sample value");
        DocxStructuralGraph filled = graphWithHeadingAndField("Something Else Entirely", "Real meeting title");

        List<ValidationFinding> findings = LayoutComparator.compareStructure(baseline, filled, SCALAR_FIELD);

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_PROTECTED_REGION_CHANGE, findings.get(0).code());
    }

    @Test
    void aDifferentNumberOfRepeatedRowsDoesNotBreakAlignmentOfSurroundingProtectedContent() {
        DocxStructuralGraph baseline = graphWithHeadingTableAndFooter("Action Items", "Closing note", 2);
        DocxStructuralGraph filled = graphWithHeadingTableAndFooter("Action Items", "Closing note", 4);

        assertTrue(LayoutComparator.compareStructure(baseline, filled, REPEATED_FIELD).isEmpty());
    }

    @Test
    void aChangedFooterAfterARepeatedRegionIsStillDetectedDespiteTheRowCountDifference() {
        DocxStructuralGraph baseline = graphWithHeadingTableAndFooter("Action Items", "Closing note", 2);
        DocxStructuralGraph filled = graphWithHeadingTableAndFooter("Action Items", "A different closing note", 3);

        List<ValidationFinding> findings = LayoutComparator.compareStructure(baseline, filled, REPEATED_FIELD);

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_PROTECTED_REGION_CHANGE, findings.get(0).code());
    }

    @Test
    void aChangedProtectedImageRelationshipIsReported() {
        DocxStructuralGraph baseline = graphWithImage("rId5");
        DocxStructuralGraph filled = graphWithImage("rId9");

        List<ValidationFinding> findings = LayoutComparator.compareStructure(baseline, filled, List.of());

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_PROTECTED_REGION_CHANGE, findings.get(0).code());
    }

    /**
     * A genuine, unexplained count difference cannot be safely aligned
     * further -- which leaf on one side corresponds to which on the other
     * is ambiguous -- so this is reported as an informational expected
     * insertion, not asserted as a specific, possibly-wrong blocking
     * defect. This mirrors the real asymmetry a non-empty sample baseline
     * has against a genuinely empty optional/repeated real revision (see
     * {@link LayoutComparator}'s own javadoc).
     */
    @Test
    void extraProtectedContentInTheFilledDocumentIsReportedAsAnExpectedInsertionNotABlockingChange() {
        DocxStructuralGraph baseline = graphWithBody(run("Only heading"));
        DocxStructuralGraph filled = graphWithBody(run("Only heading"), run("An unexpected extra paragraph"));

        List<ValidationFinding> findings = LayoutComparator.compareStructure(baseline, filled, List.of());

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_EXPECTED_INSERTION, findings.get(0).code());
        assertEquals(ValidationSeverity.INFORMATIONAL, findings.get(0).severity());
    }

    /**
     * A genuinely separate real limitation, named rather than hidden: the
     * common-prefix/common-suffix approach isolates exactly one contiguous
     * ambiguous region. Two independent, non-adjacent perturbations at
     * once -- a real tamper plus an unrelated insertion elsewhere, outside
     * any excluded repeated region -- merge into one combined ambiguous
     * middle and are reported together as one expected-insertion finding,
     * not as a separately caught blocking change. {@link
     * #aChangedFooterAfterARepeatedRegionIsStillDetectedDespiteTheRowCountDifference}
     * above still catches a real tamper alongside a count difference
     * because that count difference is resolved by the earlier, separate
     * repeated-row exclusion, not by this diff step at all -- the
     * combination this test documents as not caught is two perturbations
     * both left for the prefix/suffix step itself to reconcile.
     */
    @Test
    void twoIndependentPerturbationsMergeIntoOneAmbiguousFindingRatherThanBeingSeparatelyDetected() {
        DocxStructuralGraph baseline = graphWithBody(run("Confidential draft."), run("Only heading"));
        DocxStructuralGraph filled = graphWithBody(run("Tampered disclaimer."), run("Only heading"), run("An unexpected extra paragraph"));

        List<ValidationFinding> findings = LayoutComparator.compareStructure(baseline, filled, List.of());

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_EXPECTED_INSERTION, findings.get(0).code());
    }

    /**
     * A real, held-out-shaped table: a static header row ("Task"/"Owner"/
     * "Due date", no content controls at all) ahead of a three-field
     * repeated-row group, baseline and filled diverging by more than one
     * item (2 vs. 5) -- the exact shape of the real table-led built-in
     * template. Proves the header row's own static cells are correctly
     * treated as fixed protected content (never excluded, since they
     * contain no repeated tag) while the group rows are correctly
     * excluded regardless of a large row-count difference.
     */
    @Test
    void aStaticHeaderRowAheadOfAMultiFieldRepeatedGroupIsUnaffectedByALargeItemCountDifference() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("action.item.task", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.ContentControlTag("action.item.task")),
                new FieldDefinition("action.item.owner", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.ContentControlTag("action.item.owner")),
                new FieldDefinition("action.item.due", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.ContentControlTag("action.item.due")));

        DocxStructuralGraph baseline = threeFieldTableGraph(2);
        DocxStructuralGraph filled = threeFieldTableGraph(5);

        assertTrue(LayoutComparator.compareStructure(baseline, filled, fields).isEmpty());
    }

    private static DocxStructuralGraph threeFieldTableGraph(int rowCount) {
        StructuralNode headerRow = new StructuralNode("tbl0/row0", StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(
                cellWithText("tbl0/row0/cell0", "Task"),
                cellWithText("tbl0/row0/cell1", "Owner"),
                cellWithText("tbl0/row0/cell2", "Due date")));
        List<StructuralNode> rows = new java.util.ArrayList<>();
        rows.add(headerRow);
        for (int i = 0; i < rowCount; i++) {
            rows.add(new StructuralNode("tbl0/row" + (i + 1), StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(
                    cellWithControl("tbl0/row" + (i + 1) + "/cell0", "action.item.task#" + i, "Task " + i),
                    cellWithControl("tbl0/row" + (i + 1) + "/cell1", "action.item.owner#" + i, "Owner " + i),
                    cellWithControl("tbl0/row" + (i + 1) + "/cell2", "action.item.due#" + i, "Due " + i))));
        }
        StructuralNode table = new StructuralNode("tbl0", StructuralNodeKind.TABLE, null, null, null, null, rows);
        return graphWithBody(table);
    }

    private static StructuralNode cellWithText(String id, String text) {
        StructuralNode paragraph = new StructuralNode(id + "/p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run(text)));
        return new StructuralNode(id, StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(paragraph));
    }

    private static StructuralNode cellWithControl(String id, String tag, String text) {
        StructuralNode control = new StructuralNode(id + "/p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of(run(text)));
        StructuralNode paragraph = new StructuralNode(id + "/p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(control));
        return new StructuralNode(id, StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(paragraph));
    }

    private static DocxStructuralGraph graphWithHeadingAndField(String headingText, String fieldText) {
        StructuralNode heading = run(headingText);
        StructuralNode fieldRun = run(fieldText);
        StructuralNode field = new StructuralNode("p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of(fieldRun));
        return graphWithBody(heading, field);
    }

    /**
     * Each row also carries a static "Owner:" label cell alongside the
     * bound content control -- a row prototype's own non-field content,
     * repeated once per cloned row rather than fixed exactly once in the
     * document. This is exactly the shape that broke an earlier version
     * of {@link LayoutComparator} against a real built-in template: that
     * version excluded only the bound cell, so a different row count
     * changed the total protected-leaf count even though nothing
     * protected actually changed.
     *
     * <p>Each clone's own content control tag is rewritten as {@code
     * "action.item.task#" + index}, exactly matching {@code
     * PoiTemplateFiller.bindGroupControls}'s own real rewrite -- a second,
     * separate real bug (matching only the bare tag, so no cloned row was
     * ever actually recognized as repeated content) was caught only once
     * this test fixture stopped using the unrealistic bare tag on every
     * row and a real end-to-end run against an actual built-in template
     * exposed it.
     */
    private static DocxStructuralGraph graphWithHeadingTableAndFooter(String headingText, String footerText, int rowCount) {
        StructuralNode heading = run(headingText);
        List<StructuralNode> rows = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            StructuralNode itemRun = run("Task " + i);
            StructuralNode control = new StructuralNode(
                    "p0/tbl0/tr" + i + "/tc0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "action.item.task#" + i, null, List.of(itemRun));
            StructuralNode boundCell = new StructuralNode("p0/tbl0/tr" + i + "/tc0", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(control));
            StructuralNode labelCell = new StructuralNode(
                    "p0/tbl0/tr" + i + "/tc1", StructuralNodeKind.TABLE_CELL, null, null, null, null, List.of(run("Owner:")));
            rows.add(new StructuralNode("p0/tbl0/tr" + i, StructuralNodeKind.TABLE_ROW, null, null, null, null, List.of(boundCell, labelCell)));
        }
        StructuralNode table = new StructuralNode("p0/tbl0", StructuralNodeKind.TABLE, null, null, null, null, rows);
        StructuralNode footer = run(footerText);
        return graphWithBody(heading, table, footer);
    }

    private static DocxStructuralGraph graphWithImage(String relationshipId) {
        StructuralNode image = new StructuralNode("p0/img0", StructuralNodeKind.IMAGE, null, null, null, relationshipId, List.of());
        return graphWithBody(image);
    }

    private static StructuralNode run(String text) {
        return new StructuralNode("r" + text.hashCode(), StructuralNodeKind.RUN, null, text, null, null, List.of());
    }

    private static DocxStructuralGraph graphWithBody(StructuralNode... topLevelChildren) {
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(topLevelChildren));
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }
}
