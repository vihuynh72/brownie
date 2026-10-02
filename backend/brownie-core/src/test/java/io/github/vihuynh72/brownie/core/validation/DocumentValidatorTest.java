package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleVocabulary;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises each pure check independently against hand-built content/rules/graphs, the same fake-graph style {@code FieldBindingCandidateProposerTest} already uses. */
class DocumentValidatorTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long TEMPLATE_ID = 1L;
    private static final long TEMPLATE_VERSION_ID = 1L;
    private static final long AUTHOR_USER_ID = 7L;

    // --- checkRequiredness ---

    @Test
    void aTemplateRequiredFieldWithNoValueIsMissing() {
        DocumentContent content = new DocumentContent(Map.of());
        List<FieldDefinition> fields = List.of(field("meeting.title", FieldRequiredness.REQUIRED));

        List<ValidationFinding> findings = DocumentValidator.checkRequiredness(content, fields, List.of());

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.MISSING_REQUIRED_FIELD, findings.get(0).code());
        assertEquals("meeting.title", findings.get(0).fieldId());
    }

    @Test
    void aTemplateRequiredFieldWithABlankValueIsStillMissing() {
        DocumentContent content = new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("   ")));
        List<FieldDefinition> fields = List.of(field("meeting.title", FieldRequiredness.REQUIRED));

        assertEquals(1, DocumentValidator.checkRequiredness(content, fields, List.of()).size());
    }

    @Test
    void anOptionalFieldWithNoValueIsNotReported() {
        DocumentContent content = new DocumentContent(Map.of());
        List<FieldDefinition> fields = List.of(field("meeting.location", FieldRequiredness.OPTIONAL));

        assertTrue(DocumentValidator.checkRequiredness(content, fields, List.of()).isEmpty());
    }

    @Test
    void anAcceptedRequiredFieldsRuleNamingAFieldWithNoValueIsMissing() {
        DocumentContent content = new DocumentContent(Map.of());
        RuleRevision rule = ruleOf(new RulePayload.RequiredFields(List.of("meeting.organization")));

        List<ValidationFinding> findings = DocumentValidator.checkRequiredness(content, List.of(), List.of(rule));

        assertEquals(1, findings.size());
        assertEquals("meeting.organization", findings.get(0).fieldId());
    }

    @Test
    void aRuleListIsAppliedRegardlessOfItsOwnStatusSinceFilteringByAcceptedIsTheCallersJob() {
        // DocumentValidator trusts its caller to pass only accepted rules; this documents that it applies
        // whatever list it is given without re-filtering by status itself.
        DocumentContent content = new DocumentContent(Map.of());
        RulePayload payload = new RulePayload.RequiredFields(List.of("x"));
        RuleRevision proposed = new RuleRevision(
                1L, WORKSPACE_ID, TEMPLATE_ID, TEMPLATE_VERSION_ID, payload.category(),
                new RuleScope.WholeTemplate(), payload, RuleVocabulary.SCHEMA_VERSION,
                RuleRevisionStatus.PROPOSED, "explanation", AUTHOR_USER_ID, OffsetDateTime.now());
        assertEquals(1, DocumentValidator.checkRequiredness(content, List.of(), List.of(proposed)).size());
    }

    // --- checkContentRules ---

    @Test
    void textLongerThanTheAcceptedMaxIsReported() {
        DocumentContent content = new DocumentContent(Map.of("notes", new FieldValue.TextValue("0123456789")));
        RuleRevision rule = ruleOf(new RulePayload.MaxTextLength("notes", 5));

        List<ValidationFinding> findings = DocumentValidator.checkContentRules(content, List.of(rule));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.TEXT_LENGTH_EXCEEDED, findings.get(0).code());
    }

    @Test
    void textWithinTheAcceptedMaxIsNotReported() {
        DocumentContent content = new DocumentContent(Map.of("notes", new FieldValue.TextValue("short")));
        RuleRevision rule = ruleOf(new RulePayload.MaxTextLength("notes", 5));

        assertTrue(DocumentValidator.checkContentRules(content, List.of(rule)).isEmpty());
    }

    @Test
    void moreItemsThanTheAcceptedMaxIsReported() {
        DocumentContent content = new DocumentContent(Map.of(
                "action.items", new FieldValue.RepeatedTextValue(List.of("a", "b", "c"))));
        RuleRevision rule = ruleOf(new RulePayload.MaxItemCount("action.items", 2));

        List<ValidationFinding> findings = DocumentValidator.checkContentRules(content, List.of(rule));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.ITEM_COUNT_EXCEEDED, findings.get(0).code());
    }

    // --- checkFieldContentInOutput ---

    @Test
    void fieldTextMissingFromTheReopenedDocumentIsReported() {
        Map<String, List<String>> intended = Map.of("meeting.title", List.of("Annual Meeting"));

        List<ValidationFinding> findings = DocumentValidator.checkFieldContentInOutput(intended, "Some other body text.");

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.FIELD_CONTENT_NOT_IN_OUTPUT, findings.get(0).code());
    }

    @Test
    void fieldTextPresentModuloWhitespaceIsNotReported() {
        Map<String, List<String>> intended = Map.of("meeting.title", List.of("Annual   Meeting"));

        assertTrue(DocumentValidator.checkFieldContentInOutput(intended, "...\nAnnual Meeting\n...").isEmpty());
    }

    @Test
    void blankIntendedTextIsNeverReported() {
        Map<String, List<String>> intended = Map.of("meeting.location", List.of(""));

        assertTrue(DocumentValidator.checkFieldContentInOutput(intended, "unrelated").isEmpty());
    }

    // --- checkPackageIntegrity ---

    @Test
    void anUnsupportedFeatureReportProducesAFinding() {
        DocxFeatureReport report = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, paragraph 2", "author X")));

        List<ValidationFinding> findings =
                DocumentValidator.checkPackageIntegrity(DocxFeatureReport.empty(), new DocxExtractionOutcome.Unsupported(report));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, findings.get(0).code());
        assertEquals(ValidationSeverity.BLOCKING, findings.get(0).severity());
    }

    /** Only what stops the read is reported for a document that could not be read; what it also keeps as it is is not a reason. */
    @Test
    void anUnsupportedFilledDocumentIsReportedOncePerRefusedFindingAndNotForWhatItKeeps() {
        DocxFeatureReport report = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, p0", "a text box"),
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p1", "A tracked insertion."),
                DocxFeatureFinding.field(UnsupportedDocxFeature.UNSUPPORTED_FIELD, "word/document.xml, p2", "INCLUDETEXT \"c:\\a.docx\"")));

        List<ValidationFinding> findings =
                DocumentValidator.checkPackageIntegrity(DocxFeatureReport.empty(), new DocxExtractionOutcome.Unsupported(report));

        assertEquals(2, findings.size());
        assertTrue(findings.get(0).message().contains("TRACKED_CHANGES"));
        assertTrue(findings.get(1).message().contains("UNSUPPORTED_FIELD"));
    }

    /**
     * A template made while an earlier reader took its file (a tracked paragraph mark) fills to a copy today's reader
     * refuses for the same reason; only a refused kind the template does not have is the fill's doing.
     */
    @Test
    void aRefusedKindTheTemplateItselfHasIsNotTheFillsDoing() {
        DocxFeatureReport template = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p0", "A tracked paragraph mark."),
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, p3", "a text box")));
        DocxFeatureReport sameAsTemplate = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p0", "A tracked paragraph mark."),
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, p3", "a text box")));
        DocxFeatureReport withMore = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p0", "A tracked paragraph mark."),
                DocxFeatureFinding.field(UnsupportedDocxFeature.UNSUPPORTED_FIELD, "word/document.xml, p2", "INCLUDETEXT \"c:\\a.docx\"")));

        assertTrue(DocumentValidator.checkPackageIntegrity(template, new DocxExtractionOutcome.Unsupported(sameAsTemplate)).isEmpty());
        List<ValidationFinding> findings = DocumentValidator.checkPackageIntegrity(template, new DocxExtractionOutcome.Unsupported(withMore));
        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().message().contains("UNSUPPORTED_FIELD"));
    }

    /** A repeated row cloned per item repeats the template's shapes and fields; more of what the template has is not a finding. */
    @Test
    void whatTheTemplateKeepsAsItIsMayAppearAnyNumberOfTimesInTheFilledDocument() {
        DocxFeatureReport template = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, tbl3", "a text box"),
                DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/footer1.xml, p0", "PAGE \\* MERGEFORMAT")));
        DocxFeatureReport filled = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, tbl3", "a text box"),
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, tbl3", "another text box"),
                new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, tbl4", "a text box"),
                DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/footer1.xml, p0", " page ")));

        assertTrue(DocumentValidator.checkPackageIntegrity(template, supported(filled)).isEmpty());
    }

    @Test
    void aKindTheTemplateDoesNotKeepIsReportedOnceHoweverOftenItAppears() {
        DocxFeatureReport filled = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.NESTED_TABLE, "word/document.xml, tbl1", "A table cell contains another table."),
                new DocxFeatureFinding(UnsupportedDocxFeature.NESTED_TABLE, "word/document.xml, tbl2", "A table cell contains another table.")));

        List<ValidationFinding> findings = DocumentValidator.checkPackageIntegrity(DocxFeatureReport.empty(), supported(filled));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, findings.get(0).code());
        assertTrue(findings.get(0).message().contains("NESTED_TABLE"));
    }

    /** A page number in the template does not make a date field in the filled document the template's own. */
    @Test
    void aFieldIsComparedByItsKindOfFieldAsWell() {
        DocxFeatureReport template = new DocxFeatureReport(List.of(
                DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/footer1.xml, p0", "PAGE")));
        DocxFeatureReport filled = new DocxFeatureReport(List.of(
                DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/footer1.xml, p0", "PAGE"),
                DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/document.xml, p4", "DATE \\@ \"d MMMM yyyy\"")));

        List<ValidationFinding> findings = DocumentValidator.checkPackageIntegrity(template, supported(filled));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("DYNAMIC_FIELD DATE"), findings.get(0).message());
    }

    // --- checkPageRaster ---

    @Test
    void aDifferentPageCountIsReportedAsInformational() {
        PageRasterComparison comparison = new PageRasterComparison(1, 2, List.of(0.0));

        List<ValidationFinding> findings = DocumentValidator.checkPageRaster(comparison);

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_PAGE_COUNT_CHANGED, findings.get(0).code());
        assertEquals(ValidationSeverity.INFORMATIONAL, findings.get(0).severity());
    }

    @Test
    void aPageWithinTheDifferenceThresholdProducesNoFinding() {
        PageRasterComparison comparison = new PageRasterComparison(1, 1, List.of(0.01));

        assertTrue(DocumentValidator.checkPageRaster(comparison).isEmpty());
    }

    @Test
    void aPageExceedingTheDifferenceThresholdIsReportedAsAWarning() {
        PageRasterComparison comparison = new PageRasterComparison(2, 2, List.of(0.01, 0.5));

        List<ValidationFinding> findings = DocumentValidator.checkPageRaster(comparison);

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.LAYOUT_VISUAL_DIFFERENCE_DETECTED, findings.get(0).code());
        assertEquals(ValidationSeverity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("Page 2"));
    }

    @Test
    void anEmptyFeatureReportProducesNoFindings() {
        assertTrue(DocumentValidator.checkPackageIntegrity(DocxFeatureReport.empty(), supported(DocxFeatureReport.empty())).isEmpty());
    }

    private static DocxExtractionOutcome supported(DocxFeatureReport keptAsIs) {
        return new DocxExtractionOutcome.Supported(new DocxStructuralGraph("test-v1", List.of()), keptAsIs);
    }

    // --- checkProtectedRegions ---

    @Test
    void anUnchangedProtectedContentControlProducesNoFinding() {
        DocxStructuralGraph baseline = graphWithDisclaimer("Confidential draft.");
        DocxStructuralGraph filled = graphWithDisclaimer("Confidential draft.");
        RuleRevision rule = ruleOf(new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("disclaimer")));

        assertTrue(DocumentValidator.checkProtectedRegions(baseline, filled, List.of(rule)).isEmpty());
    }

    @Test
    void aChangedProtectedContentControlTextIsReported() {
        DocxStructuralGraph baseline = graphWithDisclaimer("Confidential draft.");
        DocxStructuralGraph filled = graphWithDisclaimer("Something else entirely.");
        RuleRevision rule = ruleOf(new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("disclaimer")));

        List<ValidationFinding> findings = DocumentValidator.checkProtectedRegions(baseline, filled, List.of(rule));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.PROTECTED_REGION_MODIFIED, findings.get(0).code());
    }

    @Test
    void aChangedProtectedContentControlStyleIsReported() {
        DocxStructuralGraph baseline = graphWithDisclaimer("Confidential draft.", new ResolvedStyle(true, null, null, null, null, null, null, null, null));
        DocxStructuralGraph filled = graphWithDisclaimer("Confidential draft.", new ResolvedStyle(false, null, null, null, null, null, null, null, null));
        RuleRevision rule = ruleOf(new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("disclaimer")));

        List<ValidationFinding> findings = DocumentValidator.checkProtectedRegions(baseline, filled, List.of(rule));

        assertEquals(1, findings.size());
    }

    @Test
    void aProtectedTargetMissingFromEitherGraphIsReported() {
        DocxStructuralGraph baseline = graphWithDisclaimer("Confidential draft.");
        DocxStructuralGraph filledWithoutTarget = graphWithBody();
        RuleRevision rule = ruleOf(new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("disclaimer")));

        List<ValidationFinding> findings = DocumentValidator.checkProtectedRegions(baseline, filledWithoutTarget, List.of(rule));

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.PROTECTED_REGION_MODIFIED, findings.get(0).code());
    }

    private static FieldDefinition field(String fieldId, FieldRequiredness requiredness) {
        return new FieldDefinition(
                fieldId, FieldType.TEXT, FieldCardinality.SCALAR, requiredness, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static RuleRevision ruleOf(RulePayload payload) {
        return new RuleRevision(
                1L, WORKSPACE_ID, TEMPLATE_ID, TEMPLATE_VERSION_ID, payload.category(), new RuleScope.WholeTemplate(), payload,
                RuleVocabulary.SCHEMA_VERSION, RuleRevisionStatus.ACCEPTED, "explanation", AUTHOR_USER_ID, OffsetDateTime.now());
    }

    private static DocxStructuralGraph graphWithDisclaimer(String text) {
        return graphWithDisclaimer(text, null);
    }

    private static DocxStructuralGraph graphWithDisclaimer(String text, ResolvedStyle style) {
        StructuralNode run = new StructuralNode("p0/sdt0/r0", StructuralNodeKind.RUN, style, text, null, null, List.of());
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "disclaimer", null, List.of(run));
        return graphWithBody(control);
    }

    private static DocxStructuralGraph graphWithBody(StructuralNode... topLevelChildren) {
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(topLevelChildren));
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    // ---- PDF templates ----

    @Test
    void whatFillingAPdfFoundIsSaidInWordsWithTheRightWeight() {
        List<ValidationFinding> findings = DocumentValidator.checkPdfFill(List.of(
                new PdfFillFinding("name", PdfFillFindingCode.FIELD_TEXT_SHRUNK, "8.5"),
                new PdfFillFinding("note", PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null),
                new PdfFillFinding("zip", PdfFillFindingCode.MAX_LENGTH_EXCEEDED, "5"),
                new PdfFillFinding("city", PdfFillFindingCode.UNSUPPORTED_CHARACTER, "\u2603"),
                new PdfFillFinding("greeting", PdfFillFindingCode.SCRIPT_NOT_SUPPORTED, "\u0645"),
                new PdfFillFinding("email", PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT, "other text"),
                new PdfFillFinding(null, PdfFillFindingCode.OTHER_FIELD_CHANGED, "reference")));

        assertEquals(List.of(
                ValidationFindingCode.FIELD_TEXT_SHRUNK,
                ValidationFindingCode.FIXED_FIELD_OVERFLOW,
                ValidationFindingCode.FIXED_FIELD_OVERFLOW,
                ValidationFindingCode.UNSUPPORTED_CHARACTER,
                ValidationFindingCode.UNSUPPORTED_CHARACTER,
                ValidationFindingCode.FIELD_CONTENT_NOT_IN_OUTPUT,
                ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS), findings.stream().map(ValidationFinding::code).toList());
        assertEquals("Made the text smaller (8.5 pt) to fit.", findings.get(0).message());
        assertEquals(ValidationSeverity.INFORMATIONAL, findings.get(0).severity());
        assertEquals(ValidationSeverity.BLOCKING, findings.get(1).severity());
        assertTrue(findings.get(2).message().contains("5 characters at most"));
        assertTrue(findings.get(3).message().contains("\u2603") && findings.get(3).message().contains("U+2603"), findings.get(3).message());
        assertEquals("zip", findings.get(2).fieldId());
        assertEquals(null, findings.get(6).fieldId());
    }

    @Test
    void aPageThatChangedOutsideItsPlacesBlocksAndAPictureNotComparedOnlyWarns() {
        MaskedRasterComparison comparison = new MaskedRasterComparison(2, 2, List.of(
                new MaskedPageComparison(1, MaskedPageComparison.Outcome.COMPARED, 1_000_000, 4, true),
                new MaskedPageComparison(2, MaskedPageComparison.Outcome.COMPARED, 1_000_000, 900, false)));

        List<ValidationFinding> findings = DocumentValidator.checkMaskedRaster(comparison);

        assertEquals(List.of(ValidationFindingCode.PICTURE_NOT_COMPARED, ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS),
                findings.stream().map(ValidationFinding::code).toList());
        assertEquals(ValidationSeverity.WARNING, findings.get(0).severity());
        assertEquals("Brownie could not draw the scanned picture on page 1 to check it; it checked the text it added.",
                findings.get(0).message());
        assertEquals("Page 2 changed outside the places Brownie filled.", findings.get(1).message());
    }

    @Test
    void aDifferentPageCountBlocksAndAPageNotDrawnIsAGapNotAChange() {
        MaskedRasterComparison comparison = new MaskedRasterComparison(1, 2, List.of(
                new MaskedPageComparison(1, MaskedPageComparison.Outcome.NOT_DRAWN_TOO_SLOW, 0, 0, false)));

        List<ValidationFinding> findings = DocumentValidator.checkMaskedRaster(comparison);

        assertEquals(List.of(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE),
                findings.stream().map(ValidationFinding::code).toList());
    }

    @Test
    void thePlacesMaskedAreEveryBoxAndEveryPlaceABoundFormFieldIsShown() {
        PdfFormGraph.Field name = new PdfFormGraph.Field("fullName", PdfFormGraph.FieldKind.TEXT, false, false, false, false, null, null,
                null, List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 82, 300, 20)), new PdfFormGraph.Widget(2, new PdfRect(10, 20, 30, 40))));
        PdfFormGraph graph = new PdfFormGraph("v", List.of(), new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(name), 0),
                new PdfFormGraph.Risks(false, false, false));
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("name", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.AcroFormField("fullName")),
                new FieldDefinition("note", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.PageBox(1, 72, 400, 200, 14, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.BLOCK)));

        assertEquals(List.of(new RasterMask(1, 150, 82, 300, 20), new RasterMask(2, 10, 20, 30, 40), new RasterMask(1, 72, 400, 200, 14)),
                DocumentValidator.fillSpotMasks(fields, graph));
    }

    @Test
    void aProtectedRegionNamingAPlaceOnAPdfIsNeverFoundInAWordFile() {
        DocxStructuralGraph graph = new DocxStructuralGraph("v", List.of());
        RuleRevision rule = ruleOf(new RulePayload.ProtectedRegion(new FieldBindingTarget.AcroFormField("fullName")));

        List<ValidationFinding> findings = DocumentValidator.checkProtectedRegions(graph, graph, List.of(rule));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("PDF form field \"fullName\""), findings.get(0).message());
    }
}
