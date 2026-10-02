package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.document.UnusablePdfFormException;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.rule.EmptyValueResolution;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleConflictException;
import io.github.vihuynh72.brownie.core.rule.RuleProblemReason;
import io.github.vihuynh72.brownie.core.rule.RuleValidationException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the draft/replace/activate lifecycle against fakes, not a real
 * database -- what matters here is the sequencing, validation-before-
 * persistence, and concurrency-guard logic {@link TemplateService} itself
 * owns, independent of any infrastructure. Mirrors {@code
 * ArtifactServiceTest}'s own fake-based style.
 */
class TemplateServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SOURCE_ARTIFACT_ID = 42L;
    private static final String PARSER_VERSION = "poi-docx-v1";

    @Test
    void createDraftSucceedsWhenSourceHasACompleteExtraction() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);

        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertEquals(TemplateStatus.DRAFT, template.status());
        assertEquals(null, template.currentActiveVersionId());
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(1, draft.versionNumber());
        assertTrue(draft.fieldDefinitions().isEmpty());
        assertEquals(TemplateVersionStatus.DRAFT, draft.status());
    }

    @Test
    void createDraftKeepsTheUploadsNoticesWithTheDraft() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        List<PreparationNotice> notices = List.of(
                new PreparationNotice(PreparationNotice.CONVERTED, 1, "WORD_97"),
                new PreparationNotice(PreparationNotice.PLACES_LEFT_OUT, 2, null));

        Template kept = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID, notices);
        Template none = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertEquals(notices, service.findDraftVersion(WORKSPACE_ID, USER_ID, kept.id()).orElseThrow().preparationNotices());
        assertEquals(null, service.findDraftVersion(WORKSPACE_ID, USER_ID, none.id()).orElseThrow().preparationNotices());
    }

    @Test
    void createDraftRefusesNoticesTheUploadStepCouldNotHaveGivenAndMakesNothing() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        TemplateService service = new TemplateService(templates, extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        PreparationNotice found = new PreparationNotice(PreparationNotice.SPOTS_FOUND, 3, null);

        for (List<PreparationNotice> refused : List.of(
                List.of(new PreparationNotice("SOMETHING_ELSE", 1, null)),
                List.of(new PreparationNotice(PreparationNotice.KEPT_AS_IS, 1, "x".repeat(TemplateService.MAX_NOTICE_DETAIL_LENGTH + 1))),
                Collections.nCopies(TemplateService.MAX_PREPARATION_NOTICES + 1, found))) {
            assertThrows(MalformedTemplateRequestException.class,
                    () -> service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID, refused));
        }
        assertTrue(templates.findAll(WORKSPACE_ID, USER_ID).isEmpty());

        List<PreparationNotice> most = Collections.nCopies(TemplateService.MAX_PREPARATION_NOTICES,
                new PreparationNotice(PreparationNotice.KEPT_AS_IS, 1, "x".repeat(TemplateService.MAX_NOTICE_DETAIL_LENGTH)));
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID, most);
        assertEquals(most, service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow().preparationNotices());
    }

    @Test
    void createDraftKeepsANoticeThatCountsManyThousandsOfThings() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        // A long form restyled while changes were tracked: one tracked change for every run of text.
        List<PreparationNotice> notices = List.of(new PreparationNotice(PreparationNotice.TRACKED_CHANGES_AND_COMMENTS, 12_000, null));

        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID, notices);

        assertEquals(notices, service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow().preparationNotices());
    }

    @Test
    void createDraftFailsWhenSourceHasNeverBeenExtracted() {
        TemplateService service =
                new TemplateService(new FakeTemplateRepository(), new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);

        assertThrows(
                TemplateSourceNotExtractableException.class,
                () -> service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID));
    }

    @Test
    void createDraftFailsWhenSourceExtractionIsUnsupportedNotComplete() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putUnsupported(SOURCE_ARTIFACT_ID, PARSER_VERSION);
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);

        assertThrows(
                TemplateSourceNotExtractableException.class,
                () -> service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID));
    }

    @Test
    void replaceDraftBindingsSucceedsAndAdvancesVersionNumber() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        TemplateVersion updated = service.replaceDraftBindings(
                WORKSPACE_ID,
                USER_ID,
                template.id(),
                1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));

        assertEquals(2, updated.versionNumber());
        assertEquals(1, updated.fieldDefinitions().size());
    }

    @Test
    void replaceDraftBindingsRejectsAnUnsupportedTargetAndPersistsNothing() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertThrows(
                TemplateBindingValidationException.class,
                () -> service.replaceDraftBindings(
                        WORKSPACE_ID,
                        USER_ID,
                        template.id(),
                        1,
                        List.of(field("nope", new FieldBindingTarget.ContentControlTag("no.such.tag")))));

        TemplateVersion stillDraft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(1, stillDraft.versionNumber(), "a rejected replace must not advance the draft's own version");
        assertTrue(stillDraft.fieldDefinitions().isEmpty());
    }

    @Test
    void replaceDraftBindingsRejectsAStaleExpectedVersionNumber() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertThrows(
                TemplateVersionStateConflictException.class,
                () -> service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 99, List.of()));
    }

    @Test
    void activateSucceedsAndPointsTheTemplateAtTheNewActiveVersion() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID,
                USER_ID,
                template.id(),
                1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
        Template reloaded = service.find(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(TemplateStatus.ACTIVE, reloaded.status());
        assertEquals(activated.id(), reloaded.currentActiveVersionId());
    }

    @Test
    void activateRecordsTheBaselineRenderOnlyAfterActivationItselfSucceeds() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeTemplateBaselineRenderRepository baselineRenders = new FakeTemplateBaselineRenderRepository();
        TemplateService service = new TemplateService(
                new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(),
                new FakePassingTemplateBaselineRenderer(), baselineRenders, new FakeArtifactRepository(),
                new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        BaselineRenderResult recorded = service.findBaselineRender(WORKSPACE_ID, USER_ID, activated.id()).orElseThrow();
        assertEquals("fake-renderer-v1", recorded.rendererVersion());
    }

    @Test
    void activateRefusesWhenTheBaselineRenderFailsItsOwnIntegrityCheck() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeTemplateBaselineRenderRepository baselineRenders = new FakeTemplateBaselineRenderRepository();
        TemplateBaselineRenderer failingRenderer = (workspaceId, userId, draftVersion) ->
                new BaselineRenderResult(1L, 2L, "fake-renderer-v1", List.of("meeting.title"));
        TemplateService service = new TemplateService(
                new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), failingRenderer,
                baselineRenders, new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        long draftVersionId = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow().id();

        TemplateBaselineIntegrityException exception = assertThrows(
                TemplateBaselineIntegrityException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 2));

        assertEquals(List.of("meeting.title"), exception.failedFieldIds());
        Template reloaded = service.find(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(TemplateStatus.DRAFT, reloaded.status());
        assertTrue(baselineRenders.findBaselineRender(WORKSPACE_ID, USER_ID, draftVersionId).isEmpty());
    }

    @Test
    void activateRefusesAnEmptyDraft() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertThrows(
                TemplateVersionStateConflictException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 1));
        assertThrows(
                TemplateVersionStateConflictException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 1, false));
    }

    @Test
    void anEmptyDraftActivatesWhenTheCallerSaysTheDocumentOpensWithNoPlacesYet() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeTemplateBaselineRenderRepository baselineRenders = new FakeTemplateBaselineRenderRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(),
                new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), baselineRenders, new FakeArtifactRepository(),
                new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Plain letter", SOURCE_ARTIFACT_ID);

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 1, true);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
        assertTrue(activated.fieldDefinitions().isEmpty());
        assertTrue(service.findBaselineRender(WORKSPACE_ID, USER_ID, activated.id()).isPresent(), "the baseline is still proven");
    }

    @Test
    void activateRefusesWhenTwoProposedRulesDirectlyContradictEachOther() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(draft.id(), new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.OMIT));
        rules.add(draft.id(), new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.BLANK));

        assertThrows(RuleConflictException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 2));
    }

    @Test
    void activateIgnoresARejectedRuleEvenWhenItWouldOtherwiseConflict() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(
                draft.id(), new RuleScope.WholeTemplate(), new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.OMIT));
        rules.add(
                draft.id(), new RuleScope.WholeTemplate(), new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.BLANK),
                RuleRevisionStatus.REJECTED);

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
    }

    @Test
    void activateRefusesWhenADraftBindingChangeMakesAnExistingRuleStale() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(
                SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithTags("meeting.title", "meeting.location"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID,
                USER_ID,
                template.id(),
                1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion titleDraft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(
                titleDraft.id(),
                new RuleScope.SingleField("meeting.title"),
                new RulePayload.MaxTextLength("meeting.title", 100));

        service.replaceDraftBindings(
                WORKSPACE_ID,
                USER_ID,
                template.id(),
                2,
                List.of(field("meeting.location", new FieldBindingTarget.ContentControlTag("meeting.location"))));

        RuleValidationException exception = assertThrows(
                RuleValidationException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 3));
        assertTrue(exception.problems().stream().anyMatch(problem ->
                problem.reason() == RuleProblemReason.UNKNOWN_FIELD && problem.detail().contains("meeting.title")));
        Template reloaded = service.find(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(TemplateStatus.DRAFT, reloaded.status());
        assertEquals(null, reloaded.currentActiveVersionId());
        assertEquals(TemplateVersionStatus.DRAFT, service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow().status());
    }

    @Test
    void activateSucceedsWhenRulesAreProposedButDoNotConflict() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(draft.id(), new RulePayload.MaxTextLength("meeting.title", 100));

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
    }

    @Test
    void activateRefusesWhenARequiredFieldDefinitionHasAMissingValueBehavior() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(draft.id(), new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.PLACEHOLDER_TEXT));

        assertThrows(RuleConflictException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 2));
    }

    @Test
    void activateLetsAFieldScopedRuleOverrideATemplateDefault() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        FakeRuleRepository rules = new FakeRuleRepository();
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), rules, new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);
        service.replaceDraftBindings(
                WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        rules.add(draft.id(), new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.LONG));
        rules.add(
                draft.id(), new RuleScope.SingleField("meeting.title"),
                new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.ISO));

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
    }

    @Test
    void findDraftStructuralGraphReturnsTheGraphTheDraftIsPinnedTo() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        DocxStructuralGraph graph = graphWithOneTag("meeting.title");
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graph);
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        DocxStructuralGraph found = service.findDraftStructuralGraph(WORKSPACE_ID, USER_ID, template.id());

        assertEquals(graph, found);
    }

    @Test
    void proposeCandidateBindingsProposesFromTheDraftsOwnGraph() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        var report = service.proposeCandidateBindings(WORKSPACE_ID, USER_ID, template.id());

        assertEquals(1, report.candidates().size());
        assertEquals("meeting.title", report.candidates().get(0).fieldId());
    }

    @Test
    void proposeCandidateBindingsFailsWhenTemplateHasNoOpenDraft() {
        TemplateService service =
                new TemplateService(new FakeTemplateRepository(), new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);

        assertThrows(
                TemplateNotFoundException.class, () -> service.proposeCandidateBindings(WORKSPACE_ID, USER_ID, 999L));
    }

    @Test
    void actingOnATemplateThatDoesNotExistReportsNotFound() {
        TemplateService service =
                new TemplateService(new FakeTemplateRepository(), new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);

        assertThrows(
                TemplateNotFoundException.class, () -> service.replaceDraftBindings(WORKSPACE_ID, USER_ID, 999L, 1, List.of()));
    }

    @Test
    void aTrashedTemplateLeavesTheListButStaysInTheTrashBinAndInWhatProvisioningSees() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template kept = service.createDraft(WORKSPACE_ID, USER_ID, "Kept", SOURCE_ARTIFACT_ID);
        Template first = service.createDraft(WORKSPACE_ID, USER_ID, "Trashed first", SOURCE_ARTIFACT_ID);
        Template second = service.createDraft(WORKSPACE_ID, USER_ID, "Trashed second", SOURCE_ARTIFACT_ID);

        Template trashed = service.trash(WORKSPACE_ID, USER_ID, first.id());
        service.trash(WORKSPACE_ID, USER_ID, second.id());

        assertTrue(trashed.trashedAt() != null);
        assertEquals(List.of(kept.id()), service.findAll(WORKSPACE_ID, USER_ID).stream().map(Template::id).toList());
        assertEquals(
                List.of(second.id(), first.id()),
                service.findTrashed(WORKSPACE_ID, USER_ID).stream().map(Template::id).toList());
        assertEquals(
                List.of(kept.id(), first.id(), second.id()),
                service.findAllIncludingTrashed(WORKSPACE_ID, USER_ID).stream().map(Template::id).toList());
    }

    @Test
    void trashingAndRestoringAgainChangeNothingAndAMissingTemplateIsNotFound() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor(), new FakeRuleRepository(), new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), new FakeArtifactRepository(), new FakePdfFormExtractionVersionRepository(), FAKE_PDF_FORM_READER);
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        Template trashed = service.trash(WORKSPACE_ID, USER_ID, template.id());
        Template trashedAgain = service.trash(WORKSPACE_ID, USER_ID, template.id());
        Template restored = service.restore(WORKSPACE_ID, USER_ID, template.id());
        Template restoredAgain = service.restore(WORKSPACE_ID, USER_ID, template.id());

        assertEquals(trashed, trashedAgain);
        assertEquals(null, restored.trashedAt());
        assertEquals(restored, restoredAgain);
        assertEquals(List.of(template.id()), service.findAll(WORKSPACE_ID, USER_ID).stream().map(Template::id).toList());
        assertThrows(TemplateNotFoundException.class, () -> service.trash(WORKSPACE_ID, USER_ID, 999L));
        assertThrows(TemplateNotFoundException.class, () -> service.restore(WORKSPACE_ID, USER_ID, 999L));
    }

    private static FieldDefinition field(String fieldId, FieldBindingTarget binding) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED, binding);
    }

    private static DocxStructuralGraph graphWithOneTag(String tag) {
        return graphWithTags(tag);
    }

    private static DocxStructuralGraph graphWithTags(String... tags) {
        List<StructuralNode> controls = new ArrayList<>();
        for (int index = 0; index < tags.length; index++) {
            controls.add(new StructuralNode(
                    "p" + index + "/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, tags[index], null, List.of()));
        }
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, controls);
        return new DocxStructuralGraph(PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static final class FakeDocxStructuralExtractor implements DocxStructuralExtractor {
        @Override
        public String parserVersion() {
            return PARSER_VERSION;
        }

        @Override
        public DocxExtractionOutcome extract(InputStream content) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }
    }

    private static final class FakeExtractionVersionRepository implements ExtractionVersionRepository {

        private final Map<Long, ExtractionVersion> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        void putComplete(long artifactId, String parserVersion, DocxStructuralGraph graph) {
            long id = ids.getAndIncrement();
            byId.put(
                    id,
                    new ExtractionVersion(
                            id, WORKSPACE_ID, artifactId, parserVersion, ExtractionStatus.COMPLETE, DocxFeatureReport.empty(), graph, null,
                            OffsetDateTime.now()));
        }

        void putUnsupported(long artifactId, String parserVersion) {
            long id = ids.getAndIncrement();
            byId.put(
                    id,
                    new ExtractionVersion(
                            id, WORKSPACE_ID, artifactId, parserVersion, ExtractionStatus.UNSUPPORTED, DocxFeatureReport.empty(), null, null,
                            OffsetDateTime.now()));
        }

        @Override
        public Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return byId.values().stream()
                    .filter(v -> v.workspaceId() == workspaceId && v.artifactId() == artifactId && v.parserVersion().equals(parserVersion))
                    .findFirst();
        }

        @Override
        public Optional<ExtractionVersion> findById(long workspaceId, long userId, long extractionVersionId) {
            ExtractionVersion version = byId.get(extractionVersionId);
            return version != null && version.workspaceId() == workspaceId ? Optional.of(version) : Optional.empty();
        }

        @Override
        public ExtractionVersion saveComplete(
                long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph, DocxFeatureReport keptAsIs) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public ExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport featureReport) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }
    }

    private static final class FakeRuleRepository implements RuleRepository {

        private final List<RuleRevision> revisions = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(1);

        /** Test convenience: adds a PROPOSED rule directly against {@code templateVersionId}, bypassing {@link #propose}'s own validation, since these tests only exercise {@code TemplateService}'s own conflict gate at activation. */
        void add(long templateVersionId, RulePayload payload) {
            add(templateVersionId, new RuleScope.WholeTemplate(), payload);
        }

        void add(long templateVersionId, RuleScope scope, RulePayload payload) {
            add(templateVersionId, scope, payload, RuleRevisionStatus.PROPOSED);
        }

        /** Lets a test seed an already-decided rule directly, to prove a decided rule's own effect on activation without exercising {@code decide} itself. */
        void add(long templateVersionId, RuleScope scope, RulePayload payload, RuleRevisionStatus status) {
            revisions.add(new RuleRevision(
                    ids.getAndIncrement(), WORKSPACE_ID, 0L, templateVersionId, payload.category(), scope, payload,
                    "test-v1", status, null, USER_ID, OffsetDateTime.now()));
        }

        @Override
        public RuleRevision propose(
                long workspaceId,
                long userId,
                long templateId,
                long templateVersionId,
                RuleScope scope,
                RulePayload payload,
                String schemaVersion,
                String humanExplanation) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            return revisions.stream().filter(r -> r.templateVersionId() == templateVersionId).toList();
        }

        @Override
        public void recordProposalEvidence(long workspaceId, long userId, long ruleId, io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence evidence) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public Optional<io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }

        @Override
        public RuleRevision decide(long workspaceId, long userId, long templateId, long ruleId, RuleRevisionStatus decision) {
            throw new UnsupportedOperationException("not needed by TemplateService");
        }
    }

    private static final class FakeTemplateRepository implements TemplateRepository {

        private final Map<Long, Template> templates = new HashMap<>();
        private final Map<Long, TemplateVersion> versions = new HashMap<>();
        private final AtomicLong templateIds = new AtomicLong(1);
        private final AtomicLong versionIds = new AtomicLong(1);

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId,
                                    List<PreparationNotice> preparationNotices) {
            long templateId = templateIds.getAndIncrement();
            long versionId = versionIds.getAndIncrement();
            templates.put(templateId, new Template(templateId, workspaceId, displayName, TemplateStatus.DRAFT, null, OffsetDateTime.now(), null));
            versions.put(
                    versionId,
                    new TemplateVersion(
                            versionId, workspaceId, templateId, 1, sourceArtifactId, TemplateKind.DOCX, extractionVersionId, null,
                            TemplateVersionStatus.DRAFT, List.of(), OffsetDateTime.now(), null, null, preparationNotices));
            return templates.get(templateId);
        }

        @Override
        public Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId,
                                       List<PreparationNotice> preparationNotices) {
            long templateId = templateIds.getAndIncrement();
            long versionId = versionIds.getAndIncrement();
            templates.put(templateId, new Template(templateId, workspaceId, displayName, TemplateStatus.DRAFT, null, OffsetDateTime.now(), null));
            versions.put(
                    versionId,
                    new TemplateVersion(
                            versionId, workspaceId, templateId, 1, sourceArtifactId, TemplateKind.PDF, null, pdfFormExtractionId,
                            TemplateVersionStatus.DRAFT, List.of(), OffsetDateTime.now(), null));
            return templates.get(templateId);
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            Template template = templates.get(templateId);
            return template != null && template.workspaceId() == workspaceId ? Optional.of(template) : Optional.empty();
        }

        @Override
        public List<Template> findAll(long workspaceId, long userId) {
            return templates.values().stream().filter(t -> t.workspaceId() == workspaceId).sorted(Comparator.comparing(Template::id)).toList();
        }

        @Override
        public List<Template> findTrashed(long workspaceId, long userId) {
            return findAll(workspaceId, userId).stream()
                    .filter(t -> t.trashedAt() != null)
                    .sorted(Comparator.comparing(Template::trashedAt).thenComparing(Template::id).reversed())
                    .toList();
        }

        @Override
        public Template trash(long workspaceId, long userId, long templateId) {
            Template template = find(workspaceId, userId, templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
            if (template.trashedAt() == null) {
                template = withTrashedAt(template, OffsetDateTime.now());
                templates.put(templateId, template);
            }
            return template;
        }

        @Override
        public Template restore(long workspaceId, long userId, long templateId) {
            Template template = find(workspaceId, userId, templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
            template = withTrashedAt(template, null);
            templates.put(templateId, template);
            return template;
        }

        private static Template withTrashedAt(Template template, OffsetDateTime trashedAt) {
            return new Template(
                    template.id(), template.workspaceId(), template.displayName(), template.status(), template.currentActiveVersionId(),
                    template.createdAt(), trashedAt);
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            return versions.values().stream()
                    .filter(v -> v.workspaceId() == workspaceId && v.templateId() == templateId && v.status() == TemplateVersionStatus.DRAFT)
                    .findFirst();
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            TemplateVersion version = versions.get(versionId);
            return version != null && version.workspaceId() == workspaceId && version.templateId() == templateId
                    ? Optional.of(version)
                    : Optional.empty();
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            TemplateVersion current = requireMatchingDraft(workspaceId, templateId, expectedVersionNumber);
            TemplateVersion updated = new TemplateVersion(
                    current.id(), current.workspaceId(), current.templateId(), current.versionNumber() + 1, current.sourceArtifactId(),
                    current.kind(), current.extractionVersionId(), current.pdfFormExtractionId(), TemplateVersionStatus.DRAFT,
                    List.copyOf(fieldDefinitions), current.createdAt(), null);
            versions.put(current.id(), updated);
            return updated;
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            TemplateVersion current = requireMatchingDraft(workspaceId, templateId, expectedVersionNumber);
            TemplateVersion activated = new TemplateVersion(
                    current.id(), current.workspaceId(), current.templateId(), current.versionNumber(), current.sourceArtifactId(),
                    current.kind(), current.extractionVersionId(), current.pdfFormExtractionId(), TemplateVersionStatus.ACTIVATED,
                    current.fieldDefinitions(), current.createdAt(), OffsetDateTime.now());
            versions.put(current.id(), activated);
            Template template = templates.get(templateId);
            templates.put(
                    templateId,
                    new Template(
                            template.id(), template.workspaceId(), template.displayName(), TemplateStatus.ACTIVE, activated.id(),
                            template.createdAt(), template.trashedAt()));
            return activated;
        }

        private TemplateVersion requireMatchingDraft(long workspaceId, long templateId, int expectedVersionNumber) {
            if (!templates.containsKey(templateId) || templates.get(templateId).workspaceId() != workspaceId) {
                throw new TemplateNotFoundException(templateId);
            }
            TemplateVersion draft = findDraftVersion(workspaceId, 0L, templateId).orElse(null);
            if (draft == null) {
                throw new TemplateVersionStateConflictException("Template " + templateId + " has no open draft version.");
            }
            if (draft.versionNumber() != expectedVersionNumber) {
                throw new TemplateVersionStateConflictException(
                        "Template " + templateId + " draft is at version " + draft.versionNumber() + ", not the expected "
                                + expectedVersionNumber + ".");
            }
            return draft;
        }
    }

    /** A baseline renderer that always reports every field passing -- what matters in this file is {@code TemplateService}'s own sequencing and exception mapping, not this pure interface's own implementation. */
    private static final class FakePassingTemplateBaselineRenderer implements TemplateBaselineRenderer {
        @Override
        public BaselineRenderResult renderBaseline(long workspaceId, long userId, TemplateVersion draftVersion) {
            return new BaselineRenderResult(1L, 2L, "fake-renderer-v1", List.of());
        }
    }

    private static final class FakeTemplateBaselineRenderRepository implements TemplateBaselineRenderRepository {
        private final Map<Long, BaselineRenderResult> byTemplateVersionId = new HashMap<>();

        @Override
        public void recordBaselineRender(long workspaceId, long userId, long templateVersionId, BaselineRenderResult result) {
            byTemplateVersionId.put(templateVersionId, result);
        }

        @Override
        public Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId) {
            return Optional.ofNullable(byTemplateVersionId.get(templateVersionId));
        }
    }

    // ---- PDF templates ----

    private static final long PDF_ARTIFACT_ID = 77L;

    @Test
    void aPdfSourceMakesAPdfDraftPinnedToItsFormReading() {
        PdfFixture pdf = new PdfFixture();
        long readingId = pdf.readings.putComplete(PDF_ARTIFACT_ID, pdfGraph());

        Template template = pdf.service().createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID);

        TemplateVersion draft = pdf.templates.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(TemplateKind.PDF, draft.kind());
        assertEquals(readingId, draft.pdfFormExtractionId());
        assertEquals(null, draft.extractionVersionId());
    }

    @Test
    void aPdfThatWasNeverPreparedOrThatWasRefusedCannotStartATemplate() {
        PdfFixture pdf = new PdfFixture();
        assertThrows(TemplateSourceNotExtractableException.class,
                () -> pdf.service().createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID));

        pdf.readings.putUnsupported(PDF_ARTIFACT_ID, UnsupportedPdfFormReason.SIGNED);
        UnusablePdfFormException refused = assertThrows(UnusablePdfFormException.class,
                () -> pdf.service().createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID));
        assertEquals(UnsupportedPdfFormReason.SIGNED, refused.reason());
    }

    @Test
    void aPdfDraftChecksItsBindingsAgainstItsFormReading() {
        PdfFixture pdf = new PdfFixture();
        pdf.readings.putComplete(PDF_ARTIFACT_ID, pdfGraph());
        TemplateService service = pdf.service();
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID);

        TemplateBindingValidationException refused = assertThrows(TemplateBindingValidationException.class,
                () -> service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 1, List.of(
                        field("tag", new FieldBindingTarget.ContentControlTag("meeting.title")),
                        field("reference", new FieldBindingTarget.AcroFormField("reference")),
                        field("missing", new FieldBindingTarget.AcroFormField("nothing")),
                        field("offPage", box(2, 72, 72, 100, 14)))));
        assertEquals(List.of(
                new UnsupportedBinding("tag", UnsupportedBindingReason.WRONG_FORMAT),
                new UnsupportedBinding("reference", UnsupportedBindingReason.NOT_FILLABLE),
                new UnsupportedBinding("missing", UnsupportedBindingReason.NOT_FOUND),
                new UnsupportedBinding("offPage", UnsupportedBindingReason.OFF_PAGE)), refused.problems());

        TemplateVersion replaced = service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 1, List.of(
                field("fullName", new FieldBindingTarget.AcroFormField("fullName")),
                field("note", box(1, 72, 400, 200, 14))));
        assertEquals(2, replaced.fieldDefinitions().size());
        assertEquals(TemplateKind.PDF, replaced.kind());
    }

    @Test
    void aPdfDraftActivatesWithItsOwnBaselineAndHasNoWordStructure() {
        PdfFixture pdf = new PdfFixture();
        pdf.readings.putComplete(PDF_ARTIFACT_ID, pdfGraph());
        TemplateService service = pdf.service();
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID);
        service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("fullName", new FieldBindingTarget.AcroFormField("fullName"))));

        assertEquals(0, service.proposeCandidateBindings(WORKSPACE_ID, USER_ID, template.id()).candidates().size());
        assertThrows(TemplateVersionStateConflictException.class,
                () -> service.findDraftStructuralGraph(WORKSPACE_ID, USER_ID, template.id()));

        TemplateVersion activated = service.activate(WORKSPACE_ID, USER_ID, template.id(), 2);

        assertEquals(TemplateVersionStatus.ACTIVATED, activated.status());
        assertTrue(service.findBaselineRender(WORKSPACE_ID, USER_ID, activated.id()).isPresent());
    }

    @Test
    void aProtectedRegionCannotBeAcceptedOnAPdfTemplate() {
        PdfFixture pdf = new PdfFixture();
        pdf.readings.putComplete(PDF_ARTIFACT_ID, pdfGraph());
        TemplateService service = pdf.service();
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Application", PDF_ARTIFACT_ID);
        TemplateVersion draft = service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 1,
                List.of(field("fullName", new FieldBindingTarget.AcroFormField("fullName"))));
        pdf.rules.add(draft.id(), new RulePayload.ProtectedRegion(new FieldBindingTarget.AcroFormField("fullName")));

        RuleValidationException refused =
                assertThrows(RuleValidationException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 2));
        assertEquals(RuleProblemReason.UNSUPPORTED_TARGET, refused.problems().get(0).reason());
    }

    private static FieldBindingTarget.PageBox box(int page, double x, double y, double width, double height) {
        return new FieldBindingTarget.PageBox(page, x, y, width, height, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.SHRINK_TO_FIT);
    }

    /** One Letter page with a fillable name field, a read-only reference field, and a label line. */
    static PdfFormGraph pdfGraph() {
        PdfFormGraph.Page page = new PdfFormGraph.Page(
                1, new PdfFormGraph.CropBox(0, 0, 612, 792), 0, 1, true, List.of(), List.of(), List.of(), List.of());
        PdfFormGraph.Field fullName = new PdfFormGraph.Field("fullName", PdfFormGraph.FieldKind.TEXT, false, false, false, false, null,
                null, null, List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 82, 300, 20))));
        PdfFormGraph.Field reference = new PdfFormGraph.Field("reference", PdfFormGraph.FieldKind.TEXT, true, false, false, false, null,
                null, null, List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 252, 200, 20))));
        return new PdfFormGraph("fake-form-v1", List.of(page),
                new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(fullName, reference), 0),
                new PdfFormGraph.Risks(false, false, false));
    }

    /** A service whose source artifact {@link #PDF_ARTIFACT_ID} is a PDF, with every fake reachable for assertions. */
    private static final class PdfFixture {
        final FakeTemplateRepository templates = new FakeTemplateRepository();
        final FakeRuleRepository rules = new FakeRuleRepository();
        final FakeArtifactRepository artifacts = new FakeArtifactRepository();
        final FakePdfFormExtractionVersionRepository readings = new FakePdfFormExtractionVersionRepository();

        PdfFixture() {
            artifacts.put(PDF_ARTIFACT_ID, SupportedMediaType.PDF);
        }

        TemplateService service() {
            return new TemplateService(templates, new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor(), rules,
                    new FakePassingTemplateBaselineRenderer(), new FakeTemplateBaselineRenderRepository(), artifacts, readings,
                    FAKE_PDF_FORM_READER);
        }
    }

    private static final PdfFormReader FAKE_PDF_FORM_READER = new PdfFormReader() {
        @Override
        public String parserVersion() {
            return "fake-form-v1";
        }

        @Override
        public PdfFormReading read(byte[] pdf) {
            throw new UnsupportedOperationException("TemplateService never reads a PDF itself");
        }
    };

    /** Knows only each artifact's detected type; nothing here uploads anything. */
    private static final class FakeArtifactRepository implements ArtifactRepository {
        private final Map<Long, Artifact> artifacts = new HashMap<>();

        void put(long artifactId, SupportedMediaType type) {
            artifacts.put(artifactId, new Artifact(artifactId, WORKSPACE_ID, "blob-" + artifactId, ArtifactStatus.READY, 100L,
                    "a".repeat(64), type, "form", null, OffsetDateTime.now(), OffsetDateTime.now()));
        }

        @Override
        public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
            return Optional.ofNullable(artifacts.get(artifactId));
        }

        @Override
        public Artifact initiateUpload(long workspaceId, long userId, String displayFilename) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact recordUploadedContent(
                long workspaceId, long userId, long artifactId, long byteCount, String sha256, SupportedMediaType detectedMediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact beginScanning(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact markReady(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact revertToQuarantined(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakePdfFormExtractionVersionRepository implements PdfFormExtractionVersionRepository {
        private final Map<Long, PdfFormExtractionVersion> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(500);

        long putComplete(long artifactId, PdfFormGraph graph) {
            return saveComplete(WORKSPACE_ID, USER_ID, artifactId, "fake-form-v1", graph).id();
        }

        void putUnsupported(long artifactId, UnsupportedPdfFormReason reason) {
            saveUnsupported(WORKSPACE_ID, USER_ID, artifactId, "fake-form-v1", reason, "found by the fake");
        }

        @Override
        public Optional<PdfFormExtractionVersion> findById(long workspaceId, long userId, long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<PdfFormExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return byId.values().stream()
                    .filter(reading -> reading.artifactId() == artifactId && reading.parserVersion().equals(parserVersion))
                    .findFirst();
        }

        @Override
        public Optional<Long> findCompleteIdByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return findByArtifact(workspaceId, userId, artifactId, parserVersion)
                    .filter(reading -> reading.status() == ExtractionStatus.COMPLETE)
                    .map(PdfFormExtractionVersion::id);
        }

        @Override
        public PdfFormExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, PdfFormGraph graph) {
            return save(new PdfFormExtractionVersion(ids.getAndIncrement(), workspaceId, artifactId, parserVersion, ExtractionStatus.COMPLETE,
                    null, null, graph, OffsetDateTime.now()));
        }

        @Override
        public PdfFormExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfFormReason reason, String detail) {
            return save(new PdfFormExtractionVersion(ids.getAndIncrement(), workspaceId, artifactId, parserVersion,
                    ExtractionStatus.UNSUPPORTED, reason, detail, null, OffsetDateTime.now()));
        }

        private PdfFormExtractionVersion save(PdfFormExtractionVersion reading) {
            Optional<PdfFormExtractionVersion> existing =
                    findByArtifact(reading.workspaceId(), 0, reading.artifactId(), reading.parserVersion());
            if (existing.isPresent()) {
                return existing.get();
            }
            byId.put(reading.id(), reading);
            return reading;
        }
    }
}
