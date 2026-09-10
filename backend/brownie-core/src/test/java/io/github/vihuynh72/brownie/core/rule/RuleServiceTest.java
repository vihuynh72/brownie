package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import org.junit.jupiter.api.Test;

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

/** Exercises the propose/find lifecycle against fakes, not a real database -- the same fake-based style {@code TemplateServiceTest} and {@code ArtifactServiceTest} already use. */
class RuleServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long TEMPLATE_ID = 1L;
    private static final long EXTRACTION_ID = 1L;

    @Test
    void proposeSucceedsForAValidRuleAgainstTheCurrentDraft() {
        RuleService service = newService(fieldDefinitions());

        RuleRevision revision = service.propose(
                WORKSPACE_ID, USER_ID, TEMPLATE_ID, new RuleScope.WholeTemplate(),
                new RulePayload.RequiredFields(List.of("meeting.title")), "Meeting title must always be present.");

        assertEquals(RuleCategory.VALIDATION, revision.category());
        assertEquals(RuleRevisionStatus.PROPOSED, revision.status());
        assertEquals(RuleVocabulary.SCHEMA_VERSION, revision.schemaVersion());
    }

    @Test
    void proposeFailsWhenTheTemplateHasNoOpenDraft() {
        RuleService service = newService(List.of());

        assertThrows(
                RuleTemplateVersionStateException.class,
                () -> service.propose(
                        WORKSPACE_ID, USER_ID, 999L, new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("x")), null));
    }

    @Test
    void proposeRejectsAnInvalidPayloadAndPersistsNothing() {
        RuleService service = newService(fieldDefinitions());

        assertThrows(
                RuleValidationException.class,
                () -> service.propose(
                        WORKSPACE_ID, USER_ID, TEMPLATE_ID, new RuleScope.WholeTemplate(),
                        new RulePayload.RequiredFields(List.of("no.such.field")), null));

        assertTrue(service.findForDraft(WORKSPACE_ID, USER_ID, TEMPLATE_ID).isEmpty());
    }

    @Test
    void findForDraftReturnsEveryRuleProposedAgainstTheCurrentDraft() {
        RuleService service = newService(fieldDefinitions());
        service.propose(
                WORKSPACE_ID, USER_ID, TEMPLATE_ID, new RuleScope.WholeTemplate(),
                new RulePayload.RequiredFields(List.of("meeting.title")), null);

        assertEquals(1, service.findForDraft(WORKSPACE_ID, USER_ID, TEMPLATE_ID).size());
    }

    private static List<FieldDefinition> fieldDefinitions() {
        return List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));
    }

    private static RuleService newService(List<FieldDefinition> fields) {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.put(EXTRACTION_ID, graphWithOneTag("meeting.title"));
        FakeTemplateRepository templates = new FakeTemplateRepository();
        templates.putDraft(TEMPLATE_ID, EXTRACTION_ID, fields);
        return new RuleService(new FakeRuleRepository(), templates, extractions, RuleVocabulary.SCHEMA_VERSION);
    }

    private static DocxStructuralGraph graphWithOneTag(String tag) {
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static final class FakeExtractionVersionRepository implements ExtractionVersionRepository {
        private final Map<Long, ExtractionVersion> byId = new HashMap<>();

        void put(long id, DocxStructuralGraph graph) {
            byId.put(
                    id,
                    new ExtractionVersion(
                            id, WORKSPACE_ID, 1L, "test-v1", ExtractionStatus.COMPLETE, DocxFeatureReport.empty(), graph, null,
                            OffsetDateTime.now()));
        }

        @Override
        public Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public Optional<ExtractionVersion> findById(long workspaceId, long userId, long extractionVersionId) {
            ExtractionVersion version = byId.get(extractionVersionId);
            return version != null && version.workspaceId() == workspaceId ? Optional.of(version) : Optional.empty();
        }

        @Override
        public ExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public ExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport featureReport) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }
    }

    private static final class FakeTemplateRepository implements TemplateRepository {
        private final Map<Long, TemplateVersion> drafts = new HashMap<>();

        void putDraft(long templateId, long extractionVersionId, List<FieldDefinition> fields) {
            drafts.put(
                    templateId,
                    new TemplateVersion(
                            templateId, WORKSPACE_ID, templateId, 1, 1L, extractionVersionId, TemplateVersionStatus.DRAFT, fields,
                            OffsetDateTime.now(), null));
        }

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            TemplateVersion draft = drafts.get(templateId);
            return draft != null && draft.workspaceId() == workspaceId ? Optional.of(draft) : Optional.empty();
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            throw new UnsupportedOperationException("not needed by RuleService");
        }
    }

    private static final class FakeRuleRepository implements RuleRepository {
        private final List<RuleRevision> revisions = new ArrayList<>();
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
    }
}
