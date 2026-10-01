package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeping found spots: only a template's own fields, only real ids, all or nothing, and twice is once. */
class FillSpotReviewServiceTest {

    private static final long WORKSPACE = 1;
    private static final long USER = 2;
    private static final long TEMPLATE = 10;

    private final Reviews reviews = new Reviews();
    private final Templates templates = new Templates();
    private final FillSpotReviewService service = new FillSpotReviewService(templates, reviews);

    @Test
    void fieldsOfTheDraftOrTheActiveVersionCanBeKeptAndKeepingAgainChangesNothing() {
        service.keep(WORKSPACE, USER, TEMPLATE, List.of("full.name"));
        service.keep(WORKSPACE, USER, TEMPLATE, List.of("full.name", "town"));

        assertEquals(Set.of("full.name", "town"), service.keptFieldIds(WORKSPACE, USER, TEMPLATE));
    }

    @Test
    void anUnknownTemplateFieldOrIdIsRefusedAndNothingIsKept() {
        assertThrows(TemplateNotFoundException.class, () -> service.keep(WORKSPACE, USER, 99, List.of("full.name")));
        assertThrows(TemplateFieldNotFoundException.class, () -> service.keep(WORKSPACE, USER, TEMPLATE, List.of("full.name", "nope")));
        assertThrows(MalformedTemplateRequestException.class, () -> service.keep(WORKSPACE, USER, TEMPLATE, List.of("Full Name")));
        assertThrows(MalformedTemplateRequestException.class, () -> service.keep(WORKSPACE, USER, TEMPLATE, List.of()));
        assertTrue(service.keptFieldIds(WORKSPACE, USER, TEMPLATE).isEmpty());
    }

    private static final class Reviews implements FillSpotReviewRepository {
        final Set<String> kept = new LinkedHashSet<>();

        @Override
        public void keep(long workspaceId, long userId, long templateId, Collection<String> fieldIds) {
            kept.addAll(fieldIds);
        }

        @Override
        public Set<String> keptFieldIds(long workspaceId, long userId, long templateId) {
            return Set.copyOf(kept);
        }
    }

    /** One template: an active version with "full.name" and a draft that adds "town". */
    private static final class Templates implements TemplateRepository {
        private final Map<Long, TemplateVersion> versions = new HashMap<>();

        Templates() {
            versions.put(1L, version(1, TemplateVersionStatus.ACTIVATED, "full.name"));
            versions.put(2L, version(2, TemplateVersionStatus.DRAFT, "full.name", "town"));
        }

        private static TemplateVersion version(long id, TemplateVersionStatus status, String... fieldIds) {
            List<FieldDefinition> fields = java.util.Arrays.stream(fieldIds)
                    .map(fieldId -> new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                            new FieldBindingTarget.ContentControlTag(fieldId)))
                    .toList();
            return new TemplateVersion(id, WORKSPACE, TEMPLATE, (int) id, 7, 8, status, fields, OffsetDateTime.now(), null);
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            return templateId == TEMPLATE
                    ? Optional.of(new Template(TEMPLATE, WORKSPACE, "Form", TemplateStatus.ACTIVE, 1L, OffsetDateTime.now(), null))
                    : Optional.empty();
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            return Optional.of(versions.get(2L));
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            return Optional.ofNullable(versions.get(versionId));
        }

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId,
                                    List<PreparationNotice> preparationNotices) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId,
                                       List<PreparationNotice> preparationNotices) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Template> findAll(long workspaceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Template> findTrashed(long workspaceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Template trash(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Template restore(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            throw new UnsupportedOperationException();
        }
    }
}
