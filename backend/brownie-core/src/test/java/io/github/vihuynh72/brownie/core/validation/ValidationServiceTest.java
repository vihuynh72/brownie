package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentContentHasher;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.PatchProposalRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateStatus;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers only this service's own guard clauses, which resolve entirely
 * against {@link RevisionService}/{@link TemplateRepository} before ever
 * touching a rule, evidence, artifact, filler, sanitizer, or extractor
 * dependency -- those are intentionally left {@code null} on the paths
 * that never reach them, the same shape {@code CompilationServiceTest}
 * already uses for the identical reason. The real fill/sanitize/re-extract
 * happy path is proven by a real HTTP/Postgres/Azurite/ClamAV integration
 * test at the API layer.
 */
class ValidationServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long DOCUMENT_ID = 55L;
    private static final long REVISION_ID = 200L;
    private static final long TEMPLATE_ID = 11L;
    private static final long TEMPLATE_VERSION_ID = 12L;

    @Test
    void missingDocumentIsReportedBeforeTouchingAnyOtherDependency() {
        ValidationService service = new ValidationService(
                new RevisionService(new EmptyDocumentRepository(), new UnreachableTemplateRepository(), new UnreachablePatchProposalRepository()),
                new UnreachableTemplateRepository(),
                new UnreachableRuleRepository(),
                new UnreachableSourceSpanRepository(),
                null, null, null, null, null, null, null, null);

        assertThrows(
                DocumentNotFoundException.class,
                () -> service.validate(WORKSPACE_ID, USER_ID, idempotencyKey(), requestHash(), DOCUMENT_ID, REVISION_ID));
    }

    @Test
    void inactiveTemplateVersionIsRefusedBeforeReadingAnyRuleOrEvidence() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        ValidationService service = new ValidationService(
                new RevisionService(documents, new DraftOnlyTemplateRepository(), new UnreachablePatchProposalRepository()),
                new DraftOnlyTemplateRepository(),
                new UnreachableRuleRepository(),
                new UnreachableSourceSpanRepository(),
                null, null, null, null, null, null, null, null);

        assertThrows(
                DocumentTemplateVersionUnavailableException.class,
                () -> service.validate(WORKSPACE_ID, USER_ID, idempotencyKey(), requestHash(), DOCUMENT_ID, REVISION_ID));
    }

    private static IdempotencyKey idempotencyKey() {
        return new IdempotencyKey("validate-test-key");
    }

    private static CanonicalRequestHash requestHash() {
        return CanonicalRequestHash.sha256OfCanonicalText("validate-test");
    }

    private static final class EmptyDocumentRepository extends FakeDocumentRepository {
        EmptyDocumentRepository() {
            super(false);
        }
    }

    private static class FakeDocumentRepository implements DocumentRepository {

        private final Document document = new Document(
                DOCUMENT_ID, WORKSPACE_ID, "Minutes", TEMPLATE_ID, TEMPLATE_VERSION_ID, REVISION_ID, OffsetDateTime.now());
        private final DocumentContent content = new DocumentContent(Map.of());
        private final DocumentRevision revision = new DocumentRevision(
                REVISION_ID, WORKSPACE_ID, DOCUMENT_ID, 1, null,
                content,
                DocumentContentHasher.sha256Hex(content),
                USER_ID, "initial draft", OffsetDateTime.now(), Map.of(), Map.of());
        private final boolean present;

        FakeDocumentRepository() {
            this(true);
        }

        FakeDocumentRepository(boolean present) {
            this.present = present;
        }

        @Override
        public Optional<io.github.vihuynh72.brownie.core.revision.DocumentMutationResult> findMutationResult(
                long workspaceId, long userId, io.github.vihuynh72.brownie.core.revision.DocumentCommandType commandType,
                IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash) {
            return Optional.empty();
        }

        @Override
        public io.github.vihuynh72.brownie.core.revision.DocumentMutationResult createIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, String title,
                long templateId, long templateVersionId, DocumentContent initialContent, Map<String, List<Long>> initialEvidence,
                Map<io.github.vihuynh72.brownie.core.revision.FieldItemRef, io.github.vihuynh72.brownie.core.revision.FieldState> initialFieldStates,
                String initialRevisionReason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Document> find(long workspaceId, long userId, long documentId) {
            return present && documentId == DOCUMENT_ID ? Optional.of(document) : Optional.empty();
        }

        @Override
        public Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId) {
            return present ? Optional.of(revision) : Optional.empty();
        }

        @Override
        public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
            return present && revisionId == REVISION_ID ? Optional.of(revision) : Optional.empty();
        }

        @Override
        public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
            return present ? List.of(revision) : List.of();
        }

        @Override
        public io.github.vihuynh72.brownie.core.revision.DocumentMutationResult appendRevisionIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, long documentId,
                long expectedRevisionId, DocumentContent content, Map<String, List<Long>> evidence,
                Map<io.github.vihuynh72.brownie.core.revision.FieldItemRef, io.github.vihuynh72.brownie.core.revision.FieldState> fieldStates,
                String editReason) {
            throw new UnsupportedOperationException("must not be reached once the template-version guard has already refused");
        }
    }

    private static final class UnreachableTemplateRepository implements TemplateRepository {

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            throw new UnsupportedOperationException("must not be reached when the document itself is missing");
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

    private static final class DraftOnlyTemplateRepository implements TemplateRepository {

        private final TemplateVersion draft = new TemplateVersion(
                TEMPLATE_VERSION_ID, WORKSPACE_ID, TEMPLATE_ID, 1, 20L, 21L,
                TemplateVersionStatus.DRAFT,
                List.of(new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title"))),
                OffsetDateTime.now(), null);

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            return Optional.of(new Template(TEMPLATE_ID, WORKSPACE_ID, "Minutes", TemplateStatus.DRAFT, null, OffsetDateTime.now()));
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            return Optional.of(draft);
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            return versionId == TEMPLATE_VERSION_ID ? Optional.of(draft) : Optional.empty();
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            throw new TemplateVersionStateConflictException("No draft replace needed in this test fake.");
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            throw new TemplateVersionStateConflictException("Not activated in this test fake.");
        }
    }

    private static final class UnreachablePatchProposalRepository implements PatchProposalRepository {

        @Override
        public PatchProposal create(
                long workspaceId, long userId, long documentId, long baseRevisionId,
                Map<String, io.github.vihuynh72.brownie.core.revision.FieldValue> proposedValues, Map<String, List<Long>> proposedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PatchProposal> find(long workspaceId, long userId, long documentId, long proposalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAccepted(long workspaceId, long userId, long documentId, long proposalId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnreachableRuleRepository implements RuleRepository {

        @Override
        public RuleRevision propose(
                long workspaceId, long userId, long templateId, long templateVersionId, RuleScope scope, RulePayload payload,
                String schemaVersion, String humanExplanation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }

        @Override
        public void recordProposalEvidence(long workspaceId, long userId, long ruleId, RuleProposalEvidence evidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuleRevision decide(long workspaceId, long userId, long templateId, long ruleId, RuleRevisionStatus decision) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnreachableSourceSpanRepository implements SourceSpanRepository {

        @Override
        public Optional<SourceSpan> find(long workspaceId, long userId, long spanId) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }

        @Override
        public SourceSpan create(
                long workspaceId, long userId, long sourceSnapshotId, String extractionParserVersion,
                io.github.vihuynh72.brownie.core.evidence.EvidenceLocator locator, String excerptHash) {
            throw new UnsupportedOperationException();
        }
    }
}
