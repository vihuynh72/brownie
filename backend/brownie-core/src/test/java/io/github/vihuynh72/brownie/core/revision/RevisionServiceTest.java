package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long TEMPLATE_ID = 11L;
    private static final long TEMPLATE_VERSION_ID = 12L;

    @Test
    void typedEditsAppendAnImmutableChildAndMoveTheCurrentPointer() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository());
        Document initialDocument = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-typed"),
                hash("create-typed"),
                "September minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                "initial draft").document();
        DocumentRevision initial = documents.findCurrentRevision(WORKSPACE_ID, USER_ID, initialDocument.id()).orElseThrow();

        DocumentRevision edited = service.applyUserEdits(
                WORKSPACE_ID,
                USER_ID,
                key("edit-typed"),
                hash("edit-typed"),
                initialDocument.id(),
                initial.id(),
                List.of(
                        new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("October minutes")),
                        new DocumentFieldEdit.SetValue(
                                "action.tasks", new FieldValue.RepeatedTextValue(List.of("Send agenda", "Book room")))),
                "corrected meeting title").revision();

        assertEquals(initial.id(), edited.parentRevisionId());
        assertEquals(2, edited.revisionNumber());
        assertEquals("October minutes", ((FieldValue.TextValue) edited.content().fields().get("meeting.title")).value());
        assertEquals(
                List.of("Send agenda", "Book room"),
                ((FieldValue.RepeatedTextValue) edited.content().fields().get("action.tasks")).values());
        assertEquals("September minutes", ((FieldValue.TextValue) initial.content().fields().get("meeting.title")).value());
        assertNotEquals(initial.contentHash(), edited.contentHash());
        assertEquals(edited.id(), documents.find(WORKSPACE_ID, USER_ID, initialDocument.id()).orElseThrow().currentRevisionId());
        assertEquals(List.of(initial, edited), service.findHistory(WORKSPACE_ID, USER_ID, initialDocument.id()));
    }

    @Test
    void rejectsUnknownWronglyTypedAndDuplicateEditsBeforeAppendingAnything() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-invalid"),
                hash("create-invalid"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                "initial draft").document();
        long initialRevisionId = document.currentRevisionId();

        DocumentContentValidationException exception = assertThrows(
                DocumentContentValidationException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-invalid"),
                        hash("edit-invalid"),
                        document.id(),
                        initialRevisionId,
                        List.of(
                                new DocumentFieldEdit.SetValue("meeting.date", new FieldValue.TextValue("2026-10-01")),
                                new DocumentFieldEdit.SetValue("unknown.field", new FieldValue.TextValue("no")),
                                new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("one")),
                                new DocumentFieldEdit.ClearValue("meeting.title")),
                        "invalid change"));

        assertTrue(exception.problems().stream().anyMatch(problem ->
                problem.reason() == DocumentContentProblemReason.TYPE_MISMATCH));
        assertTrue(exception.problems().stream().anyMatch(problem ->
                problem.reason() == DocumentContentProblemReason.UNKNOWN_FIELD));
        assertTrue(exception.problems().stream().anyMatch(problem ->
                problem.reason() == DocumentContentProblemReason.DUPLICATE_EDIT));
        assertEquals(1, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
    }

    @Test
    void rejectsStaleExpectedRevisionWithoutCreatingAnotherHistoryEntry() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-stale"),
                hash("create-stale"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                "initial draft").document();
        long initialRevisionId = document.currentRevisionId();
        service.applyUserEdits(
                WORKSPACE_ID,
                USER_ID,
                key("edit-fresh"),
                hash("edit-fresh"),
                document.id(),
                initialRevisionId,
                List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Updated"))),
                "first edit");

        DocumentRevisionConflictException exception = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-stale"),
                        hash("edit-stale"),
                        document.id(),
                        initialRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Stale"))),
                        "stale edit"));

        assertEquals(initialRevisionId, exception.expectedRevisionId());
        assertEquals(2, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
    }

    @Test
    void matchingEditRetryReturnsItsOriginalRevisionEvenAfterThePointerAdvances() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-retry"),
                hash("create-retry"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                "initial draft").document();
        long initialRevisionId = document.currentRevisionId();

        DocumentMutationResult first = service.applyUserEdits(
                WORKSPACE_ID,
                USER_ID,
                key("edit-retry"),
                hash("edit-retry"),
                document.id(),
                initialRevisionId,
                List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Updated"))),
                "first edit");
        DocumentMutationResult replay = service.applyUserEdits(
                WORKSPACE_ID,
                USER_ID,
                key("edit-retry"),
                hash("edit-retry"),
                document.id(),
                initialRevisionId,
                List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Updated"))),
                "first edit");

        assertEquals(first.commandId(), replay.commandId());
        assertEquals(first.revision(), replay.revision());
        assertEquals(2, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
        assertThrows(
                DocumentIdempotencyConflictException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-retry"),
                        hash("different-request"),
                        document.id(),
                        initialRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Different"))),
                        "different edit"));
    }

    @Test
    void contentHashIsIndependentOfMapInsertionOrderAndChangesWithTypedContent() {
        Map<String, FieldValue> firstOrder = new HashMap<>();
        firstOrder.put("meeting.title", new FieldValue.TextValue("Minutes"));
        firstOrder.put("meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 10, 1)));
        Map<String, FieldValue> secondOrder = new HashMap<>();
        secondOrder.put("meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 10, 1)));
        secondOrder.put("meeting.title", new FieldValue.TextValue("Minutes"));

        String firstHash = DocumentContentHasher.sha256Hex(new DocumentContent(firstOrder));
        String secondHash = DocumentContentHasher.sha256Hex(new DocumentContent(secondOrder));
        String changedHash = DocumentContentHasher.sha256Hex(new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue("Changed"),
                "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 10, 1)))));

        assertEquals(firstHash, secondHash);
        assertNotEquals(firstHash, changedHash);
    }

    private static DocumentContent initialContent() {
        return new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue("September minutes"),
                "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 9, 1))));
    }

    private static IdempotencyKey key(String value) {
        return new IdempotencyKey(value);
    }

    private static CanonicalRequestHash hash(String value) {
        return CanonicalRequestHash.sha256OfCanonicalText(value);
    }

    private static final class ActiveTemplateRepository implements TemplateRepository {

        private final TemplateVersion version = new TemplateVersion(
                TEMPLATE_VERSION_ID,
                WORKSPACE_ID,
                TEMPLATE_ID,
                1,
                20L,
                21L,
                TemplateVersionStatus.ACTIVATED,
                List.of(
                        field("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR),
                        field("meeting.date", FieldType.DATE, FieldCardinality.SCALAR),
                        field("action.tasks", FieldType.TEXT, FieldCardinality.REPEATED)),
                OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                OffsetDateTime.parse("2026-09-01T00:00:01Z"));

        @Override
        public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Template> find(long workspaceId, long userId, long templateId) {
            return templateId == TEMPLATE_ID
                    ? Optional.of(new Template(TEMPLATE_ID, WORKSPACE_ID, "Minutes", TemplateStatus.ACTIVE, version.id(), OffsetDateTime.now()))
                    : Optional.empty();
        }

        @Override
        public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
            return Optional.empty();
        }

        @Override
        public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
            return workspaceId == WORKSPACE_ID && templateId == TEMPLATE_ID && versionId == TEMPLATE_VERSION_ID
                    ? Optional.of(version)
                    : Optional.empty();
        }

        @Override
        public TemplateVersion replaceDraftBindings(
                long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
            throw new TemplateVersionStateConflictException("No draft exists in this test fake.");
        }

        @Override
        public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
            throw new TemplateVersionStateConflictException("No draft exists in this test fake.");
        }

        private static FieldDefinition field(String fieldId, FieldType type, FieldCardinality cardinality) {
            return new FieldDefinition(
                    fieldId,
                    type,
                    cardinality,
                    FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.ContentControlTag(fieldId));
        }
    }

    private static final class FakeDocumentRepository implements DocumentRepository {

        private final AtomicLong documentIds = new AtomicLong(100);
        private final AtomicLong revisionIds = new AtomicLong();
        private final Map<Long, Document> documents = new HashMap<>();
        private final Map<Long, List<DocumentRevision>> histories = new HashMap<>();
        private final Map<MutationKey, DocumentMutationResult> mutations = new HashMap<>();

        @Override
        public Optional<DocumentMutationResult> findMutationResult(
                long workspaceId,
                long userId,
                DocumentCommandType commandType,
                IdempotencyKey idempotencyKey,
                CanonicalRequestHash requestHash) {
            DocumentMutationResult result = mutations.get(new MutationKey(workspaceId, userId, commandType, idempotencyKey));
            if (result == null) {
                return Optional.empty();
            }
            if (!result.requestHash().equals(requestHash)) {
                throw new DocumentIdempotencyConflictException(idempotencyKey, commandType);
            }
            return Optional.of(result);
        }

        @Override
        public DocumentMutationResult createIdempotently(
                long workspaceId,
                long userId,
                IdempotencyKey idempotencyKey,
                CanonicalRequestHash requestHash,
                String title,
                long templateId,
                long templateVersionId,
                DocumentContent initialContent,
                String initialRevisionReason) {
            Optional<DocumentMutationResult> existing = findMutationResult(
                    workspaceId, userId, DocumentCommandType.CREATE, idempotencyKey, requestHash);
            if (existing.isPresent()) {
                return existing.get();
            }
            long documentId = documentIds.incrementAndGet();
            long revisionId = revisionIds.incrementAndGet();
            DocumentRevision revision = new DocumentRevision(
                    revisionId,
                    workspaceId,
                    documentId,
                    1,
                    null,
                    initialContent,
                    DocumentContentHasher.sha256Hex(initialContent),
                    userId,
                    initialRevisionReason,
                    OffsetDateTime.now());
            Document document = new Document(
                    documentId,
                    workspaceId,
                    title,
                    templateId,
                    templateVersionId,
                    revisionId,
                    OffsetDateTime.now());
            documents.put(documentId, document);
            histories.put(documentId, new ArrayList<>(List.of(revision)));
            return remember(
                    workspaceId,
                    userId,
                    DocumentCommandType.CREATE,
                    idempotencyKey,
                    new DocumentMutationResult(
                            UUID.randomUUID(),
                            DocumentCommandType.CREATE,
                            document,
                            revision,
                            requestHash,
                            OffsetDateTime.now()));
        }

        @Override
        public Optional<Document> find(long workspaceId, long userId, long documentId) {
            return Optional.ofNullable(documents.get(documentId)).filter(document -> document.workspaceId() == workspaceId);
        }

        @Override
        public Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId) {
            return find(workspaceId, userId, documentId).flatMap(document -> findRevision(
                    workspaceId, userId, documentId, document.currentRevisionId()));
        }

        @Override
        public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
            return histories.getOrDefault(documentId, List.of()).stream().filter(revision -> revision.id() == revisionId).findFirst();
        }

        @Override
        public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
            return List.copyOf(histories.getOrDefault(documentId, List.of()));
        }

        @Override
        public DocumentMutationResult appendRevisionIdempotently(
                long workspaceId,
                long userId,
                IdempotencyKey idempotencyKey,
                CanonicalRequestHash requestHash,
                long documentId,
                long expectedRevisionId,
                DocumentContent content,
                String editReason) {
            Optional<DocumentMutationResult> existing = findMutationResult(
                    workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
            if (existing.isPresent()) {
                return existing.get();
            }
            Document document = find(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
            if (document.currentRevisionId() != expectedRevisionId) {
                throw new DocumentRevisionConflictException(documentId, expectedRevisionId, document.currentRevisionId());
            }
            List<DocumentRevision> history = histories.get(documentId);
            DocumentRevision revision = new DocumentRevision(
                    revisionIds.incrementAndGet(),
                    workspaceId,
                    documentId,
                    history.size() + 1,
                    expectedRevisionId,
                    content,
                    DocumentContentHasher.sha256Hex(content),
                    userId,
                    editReason,
                    OffsetDateTime.now());
            history.add(revision);
            documents.put(documentId, new Document(
                    document.id(),
                    document.workspaceId(),
                    document.title(),
                    document.templateId(),
                    document.templateVersionId(),
                    revision.id(),
                    document.createdAt()));
            return remember(
                    workspaceId,
                    userId,
                    DocumentCommandType.EDIT_CONTENT,
                    idempotencyKey,
                    new DocumentMutationResult(
                            UUID.randomUUID(),
                            DocumentCommandType.EDIT_CONTENT,
                            documents.get(documentId),
                            revision,
                            requestHash,
                            OffsetDateTime.now()));
        }

        private DocumentMutationResult remember(
                long workspaceId,
                long userId,
                DocumentCommandType commandType,
                IdempotencyKey idempotencyKey,
                DocumentMutationResult result) {
            mutations.put(new MutationKey(workspaceId, userId, commandType, idempotencyKey), result);
            return result;
        }

        private record MutationKey(
                long workspaceId, long userId, DocumentCommandType commandType, IdempotencyKey idempotencyKey) {
        }
    }
}
