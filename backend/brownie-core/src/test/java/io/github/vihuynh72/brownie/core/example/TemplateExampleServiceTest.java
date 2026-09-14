package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.rule.RuleVocabulary;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateSourceNotExtractableException;
import io.github.vihuynh72.brownie.core.template.TemplateStatus;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fakes, not a real database -- mirrors {@code TemplateServiceTest}'s own style; what matters here is the sequencing and exception mapping this service itself owns. */
class TemplateExampleServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long TEMPLATE_SOURCE_ARTIFACT_ID = 42L;
    private static final long EXAMPLE_SOURCE_ARTIFACT_ID = 99L;
    private static final String PARSER_VERSION = "poi-docx-v1";

    @Test
    void attachExampleAlignsAndPersistsWhenEveryBoundTagIsPresent() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        extractions.putComplete(EXAMPLE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))));
        FakeTemplateExampleRepository examples = new FakeTemplateExampleRepository();
        TemplateExampleService service = newService(examples, templates, extractions);

        TemplateExample attached = service.attachExample(WORKSPACE_ID, USER_ID, templateId, EXAMPLE_SOURCE_ARTIFACT_ID);

        assertEquals(ExampleAlignmentStatus.ALIGNED, attached.alignmentStatus());
        assertEquals(1, service.findForDraft(WORKSPACE_ID, USER_ID, templateId).size());
    }

    @Test
    void attachExamplePersistsAMismatchRatherThanRejectingIt() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        extractions.putComplete(EXAMPLE_SOURCE_ARTIFACT_ID, graphWithTags("unrelated.tag"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))));
        FakeTemplateExampleRepository examples = new FakeTemplateExampleRepository();
        TemplateExampleService service = newService(examples, templates, extractions);

        TemplateExample attached = service.attachExample(WORKSPACE_ID, USER_ID, templateId, EXAMPLE_SOURCE_ARTIFACT_ID);

        assertEquals(ExampleAlignmentStatus.MISMATCHED_FAMILY, attached.alignmentStatus());
    }

    @Test
    void attachExampleFailsWhenTheDraftHasNoComparableField() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        extractions.putComplete(EXAMPLE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of());
        TemplateExampleService service = newService(new FakeTemplateExampleRepository(), templates, extractions);

        assertThrows(
                NoComparableFieldBindingsException.class,
                () -> service.attachExample(WORKSPACE_ID, USER_ID, templateId, EXAMPLE_SOURCE_ARTIFACT_ID));
    }

    @Test
    void attachExampleFailsWhenTheExampleArtifactHasNoCompleteExtraction() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateExampleService service = newService(new FakeTemplateExampleRepository(), templates, extractions);

        assertThrows(
                TemplateSourceNotExtractableException.class,
                () -> service.attachExample(WORKSPACE_ID, USER_ID, templateId, EXAMPLE_SOURCE_ARTIFACT_ID));
    }

    @Test
    void attachExampleFailsWhenTheTemplateHasNoOpenDraft() {
        TemplateExampleService service =
                newService(new FakeTemplateExampleRepository(), new FakeTemplateRepository(), new FakeExtractionVersionRepository());

        assertThrows(
                TemplateNotFoundException.class,
                () -> service.attachExample(WORKSPACE_ID, USER_ID, 999L, EXAMPLE_SOURCE_ARTIFACT_ID));
    }

    @Test
    void proposeRulesFromExamplesProposesMaxTextLengthAndDateDisplayFormatFromAlignedExamplesOnly() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title", "meeting.date"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title")),
                new FieldDefinition(
                        "meeting.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.date"))));
        FakeTemplateExampleRepository examples = new FakeTemplateExampleRepository();
        RuleService ruleService = newRuleService(templates, extractions);
        TemplateExampleService service = new TemplateExampleService(examples, templates, extractions, new FakeDocxStructuralExtractor(), ruleService);
        long alignedId1 = extractions.putComplete(
                EXAMPLE_SOURCE_ARTIFACT_ID, graphWithTaggedText("meeting.title", "Short", "meeting.date", "2026-09-10"));
        long alignedId2 = extractions.putComplete(
                EXAMPLE_SOURCE_ARTIFACT_ID + 1, graphWithTaggedText("meeting.title", "A somewhat longer title", "meeting.date", "2026-10-01"));
        long mismatchedArtifactId = EXAMPLE_SOURCE_ARTIFACT_ID + 2;
        extractions.putComplete(mismatchedArtifactId, graphWithTaggedText("some.other.field", "irrelevant", "meeting.date", "9/10/2026"));
        examples.attach(WORKSPACE_ID, USER_ID, templateId, templates.findDraftVersion(WORKSPACE_ID, USER_ID, templateId).orElseThrow().id(),
                EXAMPLE_SOURCE_ARTIFACT_ID, alignedId1, ExampleAlignmentStatus.ALIGNED);
        examples.attach(WORKSPACE_ID, USER_ID, templateId, templates.findDraftVersion(WORKSPACE_ID, USER_ID, templateId).orElseThrow().id(),
                EXAMPLE_SOURCE_ARTIFACT_ID + 1, alignedId2, ExampleAlignmentStatus.ALIGNED);
        long mismatchedExtractionId = extractions.idFor(mismatchedArtifactId);
        examples.attach(WORKSPACE_ID, USER_ID, templateId, templates.findDraftVersion(WORKSPACE_ID, USER_ID, templateId).orElseThrow().id(),
                mismatchedArtifactId, mismatchedExtractionId, ExampleAlignmentStatus.MISMATCHED_FAMILY);

        List<RuleRevision> proposed = service.proposeRulesFromExamples(WORKSPACE_ID, USER_ID, templateId);

        assertEquals(2, proposed.size());
        RuleRevision titleRule = proposed.stream().filter(r -> r.payload() instanceof RulePayload.MaxTextLength).findFirst().orElseThrow();
        assertEquals(new RulePayload.MaxTextLength("meeting.title", "A somewhat longer title".length()), titleRule.payload());
        RuleProposalEvidence titleEvidence = ruleService.findProposalEvidence(WORKSPACE_ID, USER_ID, titleRule.id()).orElseThrow();
        assertEquals(2, titleEvidence.supportingExampleCount());
        assertEquals(0, titleEvidence.contradictingExampleCount());

        RuleRevision dateRule = proposed.stream().filter(r -> r.payload() instanceof RulePayload.DateDisplayFormat).findFirst().orElseThrow();
        assertEquals(new RulePayload.DateDisplayFormat("meeting.date", DateFormatStyle.ISO), dateRule.payload());
        RuleProposalEvidence dateEvidence = ruleService.findProposalEvidence(WORKSPACE_ID, USER_ID, dateRule.id()).orElseThrow();
        assertEquals(2, dateEvidence.supportingExampleCount());
        assertEquals(0, dateEvidence.contradictingExampleCount());

        assertTrue(
                titleRule.humanExplanation() != null && !titleRule.humanExplanation().contains("Short")
                        && !titleRule.humanExplanation().contains("2026-09-10"),
                "the persisted rule must never quote an example's own literal text");
    }

    @Test
    void proposeRulesFromExamplesReturnsNothingWhenNoExamplesAreAligned() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(TEMPLATE_SOURCE_ARTIFACT_ID, graphWithTags("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        long templateId = templates.seedDraft(TEMPLATE_SOURCE_ARTIFACT_ID, extractions.idFor(TEMPLATE_SOURCE_ARTIFACT_ID), List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateExampleService service = newService(new FakeTemplateExampleRepository(), templates, extractions);

        assertEquals(List.of(), service.proposeRulesFromExamples(WORKSPACE_ID, USER_ID, templateId));
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

    /** {@code tagsAndText} alternates tag, text, tag, text -- a content control per pair, its own text carried by one child RUN node, mirroring how a real extracted document actually nests them. */
    private static DocxStructuralGraph graphWithTaggedText(String... tagsAndText) {
        List<StructuralNode> controls = new ArrayList<>();
        for (int index = 0; index < tagsAndText.length; index += 2) {
            String tag = tagsAndText[index];
            String text = tagsAndText[index + 1];
            StructuralNode run = new StructuralNode(tag + "/r0", StructuralNodeKind.RUN, null, text, null, null, List.of());
            controls.add(new StructuralNode(tag + "/sdt", StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of(run)));
        }
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, controls);
        return new DocxStructuralGraph(PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static TemplateExampleService newService(
            FakeTemplateExampleRepository examples, FakeTemplateRepository templates, FakeExtractionVersionRepository extractions) {
        return new TemplateExampleService(examples, templates, extractions, new FakeDocxStructuralExtractor(), newRuleService(templates, extractions));
    }

    private static RuleService newRuleService(FakeTemplateRepository templates, FakeExtractionVersionRepository extractions) {
        return new RuleService(new FakeRuleRepository(), templates, extractions, RuleVocabulary.SCHEMA_VERSION);
    }

    private static final class FakeDocxStructuralExtractor implements DocxStructuralExtractor {
        @Override
        public String parserVersion() {
            return PARSER_VERSION;
        }

        @Override
        public DocxExtractionOutcome extract(InputStream content) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }
    }

    private static final class FakeExtractionVersionRepository implements ExtractionVersionRepository {

        private final Map<Long, ExtractionVersion> byId = new HashMap<>();
        private final Map<Long, Long> extractionIdByArtifact = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        long putComplete(long artifactId, DocxStructuralGraph graph) {
            long id = ids.getAndIncrement();
            byId.put(
                    id,
                    new ExtractionVersion(
                            id, WORKSPACE_ID, artifactId, PARSER_VERSION, ExtractionStatus.COMPLETE, DocxFeatureReport.empty(), graph, null,
                            OffsetDateTime.now()));
            extractionIdByArtifact.put(artifactId, id);
            return id;
        }

        long idFor(long artifactId) {
            return extractionIdByArtifact.get(artifactId);
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
        public ExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public ExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport featureReport) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }
    }

    /** Only what {@link TemplateExampleService} actually calls -- {@code seedDraft} is a test-only convenience to skip a full create/replace-bindings dance. */
    private static final class FakeTemplateRepository implements TemplateRepository {

        private final Map<Long, TemplateVersion> draftByTemplate = new HashMap<>();
        private final AtomicLong templateIds = new AtomicLong(1);
        private final AtomicLong versionIds = new AtomicLong(1);

        long seedDraft(long sourceArtifactId, long extractionVersionId, List<FieldDefinition> fieldDefinitions) {
            long templateId = templateIds.getAndIncrement();
            long versionId = versionIds.getAndIncrement();
            draftByTemplate.put(
                    templateId,
                    new TemplateVersion(
                            versionId, WORKSPACE_ID, templateId, 1, sourceArtifactId, extractionVersionId, TemplateVersionStatus.DRAFT,
                            fieldDefinitions, OffsetDateTime.now(), null));
            return templateId;
        }

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            TemplateVersion draft = draftByTemplate.get(templateId);
            return draft == null
                    ? Optional.empty()
                    : Optional.of(new Template(templateId, WORKSPACE_ID, "Example Template", TemplateStatus.DRAFT, null, OffsetDateTime.now()));
        }

        @Override
        public List<Template> findAll(long workspaceId, long userId) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            return Optional.ofNullable(draftByTemplate.get(templateId));
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }
    }

    private static final class FakeTemplateExampleRepository implements TemplateExampleRepository {

        private final List<TemplateExample> examples = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public TemplateExample attach(
                long workspaceId,
                long userId,
                long templateId,
                long templateVersionId,
                long sourceArtifactId,
                long extractionVersionId,
                ExampleAlignmentStatus alignmentStatus) {
            TemplateExample example = new TemplateExample(
                    ids.getAndIncrement(), workspaceId, templateId, templateVersionId, sourceArtifactId, extractionVersionId,
                    alignmentStatus, OffsetDateTime.now());
            examples.add(example);
            return example;
        }

        @Override
        public List<TemplateExample> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            return examples.stream().filter(e -> e.templateVersionId() == templateVersionId).toList();
        }
    }

    /** Backs {@link #newRuleService} -- a real {@link RuleService} wrapping this in-memory fake, the same style {@code RuleServiceTest}'s own fake already uses. */
    private static final class FakeRuleRepository implements RuleRepository {
        private final List<RuleRevision> revisions = new ArrayList<>();
        private final Map<Long, RuleProposalEvidence> evidenceByRuleId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

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
            RuleRevision revision = new RuleRevision(
                    ids.getAndIncrement(), workspaceId, templateId, templateVersionId, payload.category(), scope, payload, schemaVersion,
                    RuleRevisionStatus.PROPOSED, humanExplanation, userId, OffsetDateTime.now());
            revisions.add(revision);
            return revision;
        }

        @Override
        public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
            return revisions.stream().filter(r -> r.id() == ruleId && r.workspaceId() == workspaceId).findFirst();
        }

        @Override
        public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            return revisions.stream().filter(r -> r.workspaceId() == workspaceId && r.templateVersionId() == templateVersionId).toList();
        }

        @Override
        public void recordProposalEvidence(long workspaceId, long userId, long ruleId, RuleProposalEvidence evidence) {
            evidenceByRuleId.put(ruleId, evidence);
        }

        @Override
        public Optional<RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId) {
            return Optional.ofNullable(evidenceByRuleId.get(ruleId));
        }

        @Override
        public RuleRevision decide(long workspaceId, long userId, long templateId, long ruleId, RuleRevisionStatus decision) {
            throw new UnsupportedOperationException("not needed by TemplateExampleService");
        }
    }
}
