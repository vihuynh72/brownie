package io.github.vihuynh72.brownie.core.template;

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
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
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
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());

        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertEquals(TemplateStatus.DRAFT, template.status());
        assertEquals(null, template.currentActiveVersionId());
        TemplateVersion draft = service.findDraftVersion(WORKSPACE_ID, USER_ID, template.id()).orElseThrow();
        assertEquals(1, draft.versionNumber());
        assertTrue(draft.fieldDefinitions().isEmpty());
        assertEquals(TemplateVersionStatus.DRAFT, draft.status());
    }

    @Test
    void createDraftFailsWhenSourceHasNeverBeenExtracted() {
        TemplateService service =
                new TemplateService(new FakeTemplateRepository(), new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor());

        assertThrows(
                TemplateSourceNotExtractableException.class,
                () -> service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID));
    }

    @Test
    void createDraftFailsWhenSourceExtractionIsUnsupportedNotComplete() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putUnsupported(SOURCE_ARTIFACT_ID, PARSER_VERSION);
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());

        assertThrows(
                TemplateSourceNotExtractableException.class,
                () -> service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID));
    }

    @Test
    void replaceDraftBindingsSucceedsAndAdvancesVersionNumber() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());
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
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());
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
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertThrows(
                TemplateVersionStateConflictException.class,
                () -> service.replaceDraftBindings(WORKSPACE_ID, USER_ID, template.id(), 99, List.of()));
    }

    @Test
    void activateSucceedsAndPointsTheTemplateAtTheNewActiveVersion() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());
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
    void activateRefusesAnEmptyDraft() {
        FakeExtractionVersionRepository extractions = new FakeExtractionVersionRepository();
        extractions.putComplete(SOURCE_ARTIFACT_ID, PARSER_VERSION, graphWithOneTag("meeting.title"));
        TemplateService service = new TemplateService(new FakeTemplateRepository(), extractions, new FakeDocxStructuralExtractor());
        Template template = service.createDraft(WORKSPACE_ID, USER_ID, "Club Minutes", SOURCE_ARTIFACT_ID);

        assertThrows(
                TemplateVersionStateConflictException.class, () -> service.activate(WORKSPACE_ID, USER_ID, template.id(), 1));
    }

    @Test
    void actingOnATemplateThatDoesNotExistReportsNotFound() {
        TemplateService service =
                new TemplateService(new FakeTemplateRepository(), new FakeExtractionVersionRepository(), new FakeDocxStructuralExtractor());

        assertThrows(
                TemplateNotFoundException.class, () -> service.replaceDraftBindings(WORKSPACE_ID, USER_ID, 999L, 1, List.of()));
    }

    private static FieldDefinition field(String fieldId, FieldBindingTarget binding) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED, binding);
    }

    private static DocxStructuralGraph graphWithOneTag(String tag) {
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
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
        public ExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph) {
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

    private static final class FakeTemplateRepository implements TemplateRepository {

        private final Map<Long, Template> templates = new HashMap<>();
        private final Map<Long, TemplateVersion> versions = new HashMap<>();
        private final AtomicLong templateIds = new AtomicLong(1);
        private final AtomicLong versionIds = new AtomicLong(1);

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            long templateId = templateIds.getAndIncrement();
            long versionId = versionIds.getAndIncrement();
            templates.put(templateId, new Template(templateId, workspaceId, displayName, TemplateStatus.DRAFT, null, OffsetDateTime.now()));
            versions.put(
                    versionId,
                    new TemplateVersion(
                            versionId, workspaceId, templateId, 1, sourceArtifactId, extractionVersionId, TemplateVersionStatus.DRAFT,
                            List.of(), OffsetDateTime.now(), null));
            return templates.get(templateId);
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            Template template = templates.get(templateId);
            return template != null && template.workspaceId() == workspaceId ? Optional.of(template) : Optional.empty();
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
                    current.extractionVersionId(), TemplateVersionStatus.DRAFT, List.copyOf(fieldDefinitions), current.createdAt(), null);
            versions.put(current.id(), updated);
            return updated;
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            TemplateVersion current = requireMatchingDraft(workspaceId, templateId, expectedVersionNumber);
            TemplateVersion activated = new TemplateVersion(
                    current.id(), current.workspaceId(), current.templateId(), current.versionNumber(), current.sourceArtifactId(),
                    current.extractionVersionId(), TemplateVersionStatus.ACTIVATED, current.fieldDefinitions(), current.createdAt(),
                    OffsetDateTime.now());
            versions.put(current.id(), activated);
            Template template = templates.get(templateId);
            templates.put(
                    templateId,
                    new Template(
                            template.id(), template.workspaceId(), template.displayName(), TemplateStatus.ACTIVE, activated.id(),
                            template.createdAt()));
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
}
