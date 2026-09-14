package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
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

        List<ValidationFinding> findings = DocumentValidator.checkPackageIntegrity(report);

        assertEquals(1, findings.size());
        assertEquals(ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, findings.get(0).code());
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
        assertTrue(DocumentValidator.checkPackageIntegrity(DocxFeatureReport.empty()).isEmpty());
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
}
