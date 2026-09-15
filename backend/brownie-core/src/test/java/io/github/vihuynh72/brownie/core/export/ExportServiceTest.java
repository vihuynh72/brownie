package io.github.vihuynh72.brownie.core.export;

import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationFindingCode;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ExportService#approve}'s and {@link ExportService#export}'s
 * own guard clauses against fakes, the same style {@code
 * ValidationServiceTest}/{@code CompilationServiceTest} already use.
 * {@code ArtifactService} is a concrete class with real storage/scanner
 * dependencies, not an interface -- left {@code null} on every path here,
 * since every case below is refused before it would ever be reached; the
 * real artifact-matching happy path is proven by a real HTTP/Postgres/
 * Azurite/ClamAV/Docker integration test at the API layer.
 */
class ExportServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long DOCUMENT_ID = 55L;
    private static final long CURRENT_REVISION_ID = 300L;
    private static final long TEMPLATE_VERSION_ID = 12L;
    private static final long MANIFEST_ID = 900L;

    @Test
    void approveRefusesBeforeReadingAnyManifestWhenTheDocumentIsMissing() {
        ExportService service = new ExportService(
                new RevisionService(new EmptyDocumentRepository(), null, null),
                new UnreachableValidationRepository(),
                null,
                new UnreachableExportApprovalRepository(),
                new UnreachableExportRepository());

        assertThrows(
                DocumentNotFoundException.class,
                () -> service.approve(WORKSPACE_ID, USER_ID, DOCUMENT_ID, MANIFEST_ID, ExportFormat.BOTH));
    }

    @Test
    void approveRefusesAManifestForAnOldRevisionAsStale() {
        RevisionService revisionService = new RevisionService(new FakeDocumentRepository(), null, null);
        ValidationManifest staleManifest = manifest(CURRENT_REVISION_ID - 1, List.of());
        ExportService service = new ExportService(
                revisionService,
                new FixedValidationRepository(staleManifest),
                null,
                new UnreachableExportApprovalRepository(),
                new UnreachableExportRepository());

        assertThrows(
                StaleExportApprovalException.class,
                () -> service.approve(WORKSPACE_ID, USER_ID, DOCUMENT_ID, MANIFEST_ID, ExportFormat.BOTH));
    }

    @Test
    void approveRefusesAManifestWithAnUnresolvedBlockingFinding() {
        RevisionService revisionService = new RevisionService(new FakeDocumentRepository(), null, null);
        ValidationManifest blockingManifest = manifest(
                CURRENT_REVISION_ID,
                List.of(new ValidationFinding(ValidationFindingCode.MISSING_REQUIRED_FIELD, "meeting.title", "missing")));
        ExportService service = new ExportService(
                revisionService,
                new FixedValidationRepository(blockingManifest),
                null,
                new UnreachableExportApprovalRepository(),
                new UnreachableExportRepository());

        assertThrows(
                BlockingValidationFindingsException.class,
                () -> service.approve(WORKSPACE_ID, USER_ID, DOCUMENT_ID, MANIFEST_ID, ExportFormat.BOTH));
    }

    @Test
    void approveAcceptsACleanManifestForTheCurrentRevision() {
        RevisionService revisionService = new RevisionService(new FakeDocumentRepository(), null, null);
        ValidationManifest cleanManifest = manifest(CURRENT_REVISION_ID, List.of());
        RecordingExportApprovalRepository approvals = new RecordingExportApprovalRepository();
        ExportService service = new ExportService(
                revisionService, new FixedValidationRepository(cleanManifest), null, approvals, new UnreachableExportRepository());

        ExportApproval approval = service.approve(WORKSPACE_ID, USER_ID, DOCUMENT_ID, MANIFEST_ID, ExportFormat.BOTH);

        assertTrue(approvals.saved);
        assertTrue(approval.format() == ExportFormat.BOTH);
    }

    @Test
    void exportRefusesBeforeReadingAnyApprovalWhenTheDocumentIsMissing() {
        ExportService service = new ExportService(
                new RevisionService(new EmptyDocumentRepository(), null, null),
                new UnreachableValidationRepository(),
                null,
                new UnreachableExportApprovalRepository(),
                new UnreachableExportRepository());

        assertThrows(DocumentNotFoundException.class, () -> service.export(WORKSPACE_ID, USER_ID, DOCUMENT_ID));
    }

    @Test
    void exportRefusesWhenNoApprovalHasEverBeenRecorded() {
        RevisionService revisionService = new RevisionService(new FakeDocumentRepository(), null, null);
        ExportService service = new ExportService(
                revisionService,
                new UnreachableValidationRepository(),
                null,
                new EmptyExportApprovalRepository(),
                new UnreachableExportRepository());

        assertThrows(ExportNotApprovedException.class, () -> service.export(WORKSPACE_ID, USER_ID, DOCUMENT_ID));
    }

    @Test
    void exportRefusesAnApprovalWhoseOwnRevisionIsNoLongerCurrent() {
        RevisionService revisionService = new RevisionService(new FakeDocumentRepository(), null, null);
        ExportApproval staleApproval = new ExportApproval(
                1L, WORKSPACE_ID, DOCUMENT_ID, CURRENT_REVISION_ID - 1, TEMPLATE_VERSION_ID, MANIFEST_ID, ExportFormat.BOTH,
                USER_ID, OffsetDateTime.now());
        ExportService service = new ExportService(
                revisionService,
                new UnreachableValidationRepository(),
                null,
                new FixedExportApprovalRepository(staleApproval),
                new UnreachableExportRepository());

        assertThrows(StaleExportApprovalException.class, () -> service.export(WORKSPACE_ID, USER_ID, DOCUMENT_ID));
    }

    private static ValidationManifest manifest(long revisionId, List<ValidationFinding> findings) {
        return new ValidationManifest(
                MANIFEST_ID, WORKSPACE_ID, DOCUMENT_ID, revisionId, 1L, TEMPLATE_VERSION_ID,
                1L, "a".repeat(64), null, null, findings, OffsetDateTime.now());
    }

    private static final class UnreachableValidationRepository implements ValidationRepository {
        @Override
        public ValidationManifest save(
                long workspaceId, long userId, long documentId, long revisionId, long templateId, long templateVersionId,
                long docxArtifactId, String docxSha256, Long pdfArtifactId, String pdfSha256, List<ValidationFinding> findings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ValidationManifest> find(long workspaceId, long userId, long documentId, long manifestId) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }

        @Override
        public Optional<ValidationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FixedValidationRepository implements ValidationRepository {
        private final ValidationManifest manifest;

        FixedValidationRepository(ValidationManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public ValidationManifest save(
                long workspaceId, long userId, long documentId, long revisionId, long templateId, long templateVersionId,
                long docxArtifactId, String docxSha256, Long pdfArtifactId, String pdfSha256, List<ValidationFinding> findings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ValidationManifest> find(long workspaceId, long userId, long documentId, long manifestId) {
            return manifestId == MANIFEST_ID ? Optional.of(manifest) : Optional.empty();
        }

        @Override
        public Optional<ValidationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnreachableExportApprovalRepository implements ExportApprovalRepository {
        @Override
        public ExportApproval save(
                long workspaceId, long userId, long documentId, long revisionId, long templateVersionId,
                long validationManifestId, ExportFormat format) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }

        @Override
        public Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }
    }

    private static final class EmptyExportApprovalRepository implements ExportApprovalRepository {
        @Override
        public ExportApproval save(
                long workspaceId, long userId, long documentId, long revisionId, long templateVersionId,
                long validationManifestId, ExportFormat format) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId) {
            return Optional.empty();
        }
    }

    private static final class FixedExportApprovalRepository implements ExportApprovalRepository {
        private final ExportApproval approval;

        FixedExportApprovalRepository(ExportApproval approval) {
            this.approval = approval;
        }

        @Override
        public ExportApproval save(
                long workspaceId, long userId, long documentId, long revisionId, long templateVersionId,
                long validationManifestId, ExportFormat format) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId) {
            return Optional.of(approval);
        }
    }

    private static final class RecordingExportApprovalRepository implements ExportApprovalRepository {
        private boolean saved;

        @Override
        public ExportApproval save(
                long workspaceId, long userId, long documentId, long revisionId, long templateVersionId,
                long validationManifestId, ExportFormat format) {
            saved = true;
            return new ExportApproval(1L, workspaceId, documentId, revisionId, templateVersionId, validationManifestId, format, userId, OffsetDateTime.now());
        }

        @Override
        public Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId) {
            return Optional.empty();
        }
    }

    private static final class UnreachableExportRepository implements ExportRepository {
        @Override
        public ExportReceipt save(
                long workspaceId, long userId, long documentId, long revisionId, long templateVersionId, long exportApprovalId,
                long validationManifestId, long docxArtifactId, String docxSha256, Long pdfArtifactId, String pdfSha256, ExportFormat format) {
            throw new UnsupportedOperationException("must not be reached once an earlier guard has already refused");
        }

        @Override
        public Optional<ExportReceipt> findLatest(long workspaceId, long userId, long documentId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyDocumentRepository implements DocumentRepository {
        @Override
        public Optional<DocumentMutationResult> findMutationResult(
                long workspaceId, long userId, DocumentCommandType commandType, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash) {
            return Optional.empty();
        }

        @Override
        public DocumentMutationResult createIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, String title,
                long templateId, long templateVersionId, DocumentContent initialContent, Map<String, List<Long>> initialEvidence,
                Map<FieldItemRef, FieldState> initialFieldStates, String initialRevisionReason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Document> find(long workspaceId, long userId, long documentId) {
            return Optional.empty();
        }

        @Override
        public List<Document> findAllForWorkspace(long workspaceId, long userId) {
            return List.of();
        }

        @Override
        public Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId) {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
            return Optional.empty();
        }

        @Override
        public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
            return List.of();
        }

        @Override
        public DocumentMutationResult appendRevisionIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, long documentId,
                long expectedRevisionId, DocumentContent content, Map<String, List<Long>> evidence,
                Map<FieldItemRef, FieldState> fieldStates, String editReason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeDocumentRepository implements DocumentRepository {
        private final Document document = new Document(
                DOCUMENT_ID, WORKSPACE_ID, "Minutes", 1L, TEMPLATE_VERSION_ID, CURRENT_REVISION_ID, OffsetDateTime.now());

        @Override
        public Optional<DocumentMutationResult> findMutationResult(
                long workspaceId, long userId, DocumentCommandType commandType, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash) {
            return Optional.empty();
        }

        @Override
        public DocumentMutationResult createIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, String title,
                long templateId, long templateVersionId, DocumentContent initialContent, Map<String, List<Long>> initialEvidence,
                Map<FieldItemRef, FieldState> initialFieldStates, String initialRevisionReason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Document> find(long workspaceId, long userId, long documentId) {
            return documentId == DOCUMENT_ID ? Optional.of(document) : Optional.empty();
        }

        @Override
        public List<Document> findAllForWorkspace(long workspaceId, long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
            return List.of();
        }

        @Override
        public DocumentMutationResult appendRevisionIdempotently(
                long workspaceId, long userId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, long documentId,
                long expectedRevisionId, DocumentContent content, Map<String, List<Long>> evidence,
                Map<FieldItemRef, FieldState> fieldStates, String editReason) {
            throw new UnsupportedOperationException();
        }
    }
}
