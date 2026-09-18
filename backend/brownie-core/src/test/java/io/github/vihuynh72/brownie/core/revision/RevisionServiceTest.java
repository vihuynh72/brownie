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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document initialDocument = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-typed"),
                hash("create-typed"),
                "September minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                Map.of(),
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
                Map.of(),
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
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-invalid"),
                hash("create-invalid"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                Map.of(),
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
                        Map.of(),
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
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-stale"),
                hash("create-stale"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                Map.of(),
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
                Map.of(),
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
                        Map.of(),
                        "stale edit"));

        assertEquals(initialRevisionId, exception.expectedRevisionId());
        assertEquals(2, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
    }

    @Test
    void matchingEditRetryReturnsItsOriginalRevisionEvenAfterThePointerAdvances() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                WORKSPACE_ID,
                USER_ID,
                key("create-retry"),
                hash("create-retry"),
                "Minutes",
                TEMPLATE_ID,
                TEMPLATE_VERSION_ID,
                initialContent(),
                Map.of(),
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
                Map.of(),
                "first edit");
        DocumentMutationResult replay = service.applyUserEdits(
                WORKSPACE_ID,
                USER_ID,
                key("edit-retry"),
                hash("edit-retry"),
                document.id(),
                initialRevisionId,
                List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Updated"))),
                Map.of(),
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
                        Map.of(),
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

    @Test
    void evidenceIsCarriedForwardForUntouchedFieldsAndClearedForFieldsAnEditSets() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        DocumentRevision initial = service.createDocument(
                        WORKSPACE_ID,
                        USER_ID,
                        key("create-with-evidence"),
                        hash("create-with-evidence"),
                        "Minutes",
                        TEMPLATE_ID,
                        TEMPLATE_VERSION_ID,
                        initialContent(),
                        Map.of("meeting.title", List.of(501L), "meeting.date", List.of(502L, 503L)),
                        "initial draft")
                .revision();
        assertEquals(List.of(501L), initial.evidence().get("meeting.title"));
        assertEquals(List.of(502L, 503L), initial.evidence().get("meeting.date"));

        DocumentRevision edited = service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-with-evidence"),
                        hash("edit-with-evidence"),
                        initial.documentId(),
                        initial.id(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("October minutes"))),
                        Map.of("meeting.title", List.of(504L)),
                        "corrected title from a new source")
                .revision();

        assertEquals(List.of(504L), edited.evidence().get("meeting.title"));
        assertEquals(List.of(502L, 503L), edited.evidence().get("meeting.date"));

        DocumentRevision cleared = service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("clear-with-evidence"),
                        hash("clear-with-evidence"),
                        initial.documentId(),
                        edited.id(),
                        List.of(new DocumentFieldEdit.ClearValue("meeting.date")),
                        Map.of(),
                        "removed unconfirmed date")
                .revision();

        assertTrue(!cleared.evidence().containsKey("meeting.date"));
        assertEquals(List.of(504L), cleared.evidence().get("meeting.title"));
    }

    @Test
    void rejectsEvidenceForAFieldTheEditDoesNotSet() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID,
                        USER_ID,
                        key("create-for-evidence-rejection"),
                        hash("create-for-evidence-rejection"),
                        "Minutes",
                        TEMPLATE_ID,
                        TEMPLATE_VERSION_ID,
                        initialContent(),
                        Map.of(),
                        "initial draft")
                .document();

        DocumentContentValidationException exception = assertThrows(
                DocumentContentValidationException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-unrelated-evidence"),
                        hash("edit-unrelated-evidence"),
                        document.id(),
                        document.currentRevisionId(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Updated"))),
                        Map.of("meeting.date", List.of(9L)),
                        "unrelated evidence"));

        assertTrue(exception.problems().stream().anyMatch(problem ->
                problem.reason() == DocumentContentProblemReason.INVALID_EVIDENCE_REFERENCE));
    }

    @Test
    void newFieldsAreFreshlyUserAuthoredAndUntouchedFieldsKeepTheirStateAcrossAnEdit() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        DocumentRevision initial = service.createDocument(
                        WORKSPACE_ID,
                        USER_ID,
                        key("create-with-states"),
                        hash("create-with-states"),
                        "Minutes",
                        TEMPLATE_ID,
                        TEMPLATE_VERSION_ID,
                        initialContent(),
                        Map.of("meeting.title", List.of(501L)),
                        "initial draft")
                .revision();

        FieldState titleState = initial.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals(new FieldState(
                Authorship.USER_AUTHORED, EvidenceSupport.DIRECT, ValidationState.NOT_RUN, ReviewState.UNREVIEWED, LockState.EDITABLE),
                titleState);
        FieldState dateState = initial.fieldStates().get(FieldItemRef.scalar("meeting.date"));
        assertEquals(EvidenceSupport.MISSING, dateState.evidenceSupport());

        DocumentRevision edited = service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-with-states"),
                        hash("edit-with-states"),
                        initial.documentId(),
                        initial.id(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("October minutes"))),
                        Map.of(),
                        "corrected title without a citation this time")
                .revision();

        assertEquals(dateState, edited.fieldStates().get(FieldItemRef.scalar("meeting.date")));
        FieldState editedTitleState = edited.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals(EvidenceSupport.MISSING, editedTitleState.evidenceSupport());
        assertEquals(Authorship.USER_AUTHORED, editedTitleState.authorship());
    }

    @Test
    void aRepeatedFieldEditGivesEveryItemItsOwnFreshlyIndexedState() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID,
                        USER_ID,
                        key("create-for-repeated-states"),
                        hash("create-for-repeated-states"),
                        "Minutes",
                        TEMPLATE_ID,
                        TEMPLATE_VERSION_ID,
                        initialContent(),
                        Map.of(),
                        "initial draft")
                .document();

        DocumentRevision edited = service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("edit-repeated-states"),
                        hash("edit-repeated-states"),
                        document.id(),
                        document.currentRevisionId(),
                        List.of(new DocumentFieldEdit.SetValue(
                                "action.tasks", new FieldValue.RepeatedTextValue(List.of("Send agenda", "Book room")))),
                        Map.of(),
                        "add tasks")
                .revision();

        assertTrue(edited.fieldStates().containsKey(FieldItemRef.item("action.tasks", 0)));
        assertTrue(edited.fieldStates().containsKey(FieldItemRef.item("action.tasks", 1)));
        assertTrue(!edited.fieldStates().containsKey(FieldItemRef.item("action.tasks", 2)));
        assertTrue(!edited.fieldStates().containsKey(FieldItemRef.scalar("action.tasks")));
    }

    @Test
    void clearingAFieldRemovesItsFieldStateEntirely() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID,
                        USER_ID,
                        key("create-for-clear-state"),
                        hash("create-for-clear-state"),
                        "Minutes",
                        TEMPLATE_ID,
                        TEMPLATE_VERSION_ID,
                        initialContent(),
                        Map.of(),
                        "initial draft")
                .document();

        DocumentRevision cleared = service.applyUserEdits(
                        WORKSPACE_ID,
                        USER_ID,
                        key("clear-state"),
                        hash("clear-state"),
                        document.id(),
                        document.currentRevisionId(),
                        List.of(new DocumentFieldEdit.ClearValue("meeting.date")),
                        Map.of(),
                        "removed unconfirmed date")
                .revision();

        assertTrue(!cleared.fieldStates().containsKey(FieldItemRef.scalar("meeting.date")));
        assertTrue(cleared.fieldStates().containsKey(FieldItemRef.scalar("meeting.title")));
    }

    @Test
    void proposePatchValidatesAPartialFieldSetAgainstTheTemplate() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-propose"), hash("create-for-propose"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long baseRevisionId = document.currentRevisionId();

        assertThrows(
                DocumentContentValidationException.class,
                () -> service.proposePatch(
                        WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                        Map.of("unknown.field", new FieldValue.TextValue("x")), Map.of()));

        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                Map.of("meeting.title", new FieldValue.TextValue("Concise title")), Map.of());

        assertEquals(PatchProposalStatus.PROPOSED, proposal.status());
        assertEquals(baseRevisionId, proposal.baseRevisionId());
    }

    @Test
    void acceptPatchAppliesACleanFieldAsAiComposedWithRecheckedEvidence() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-accept"), hash("create-for-accept"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long baseRevisionId = document.currentRevisionId();
        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                Map.of("meeting.title", new FieldValue.TextValue("Concise title")),
                Map.of("meeting.title", List.of(701L)));

        PatchAcceptanceResult result = service.acceptPatch(
                WORKSPACE_ID, USER_ID, key("accept-clean"), hash("accept-clean"), document.id(), proposal.id(),
                baseRevisionId, "accepted composed title");

        assertTrue(result.mutation().isPresent());
        DocumentRevision accepted = result.mutation().get().revision();
        assertEquals("Concise title", ((FieldValue.TextValue) accepted.content().fields().get("meeting.title")).value());
        FieldState titleState = accepted.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals(Authorship.AI_COMPOSED, titleState.authorship());
        assertEquals(EvidenceSupport.DIRECT, titleState.evidenceSupport());
        assertEquals(List.of(701L), accepted.evidence().get("meeting.title"));
    }

    @Test
    void acceptPatchLeavesAConflictingFieldUntouchedWhileApplyingACleanOneAlongsideIt() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-conflict"), hash("create-for-conflict"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long baseRevisionId = document.currentRevisionId();
        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                Map.of(
                        "meeting.title", new FieldValue.TextValue("Concise title"),
                        "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 10, 15))),
                Map.of());
        // The user edits meeting.title themselves after the proposal was generated, but before it is accepted.
        DocumentRevision userEdited = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("meanwhile-edit"), hash("meanwhile-edit"), document.id(), baseRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("User's own title"))),
                        Map.of(), "user typed their own title")
                .revision();

        PatchAcceptanceResult result = service.acceptPatch(
                WORKSPACE_ID, USER_ID, key("accept-partial"), hash("accept-partial"), document.id(), proposal.id(),
                userEdited.id(), "accept the non-conflicting field only");

        assertEquals(PatchFieldStatus.CONFLICT, result.comparison().fieldStatuses().get("meeting.title"));
        assertEquals(PatchFieldStatus.CLEAN, result.comparison().fieldStatuses().get("meeting.date"));
        DocumentRevision accepted = result.mutation().orElseThrow().revision();
        assertEquals("User's own title", ((FieldValue.TextValue) accepted.content().fields().get("meeting.title")).value());
        assertEquals(LocalDate.of(2026, 10, 15), ((FieldValue.DateValue) accepted.content().fields().get("meeting.date")).value());
        assertEquals(Authorship.USER_AUTHORED, accepted.fieldStates().get(FieldItemRef.scalar("meeting.title")).authorship());
        assertEquals(Authorship.AI_COMPOSED, accepted.fieldStates().get(FieldItemRef.scalar("meeting.date")).authorship());
    }

    @Test
    void acceptPatchWithEveryFieldBlockedProducesNoMutationAndLeavesTheProposalAcceptable() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-full-conflict"), hash("create-for-full-conflict"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long baseRevisionId = document.currentRevisionId();
        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                Map.of("meeting.title", new FieldValue.TextValue("Concise title")), Map.of());
        DocumentRevision userEdited = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("meanwhile-edit-2"), hash("meanwhile-edit-2"), document.id(), baseRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("User's own title"))),
                        Map.of(), "user typed their own title")
                .revision();

        PatchAcceptanceResult result = service.acceptPatch(
                WORKSPACE_ID, USER_ID, key("accept-blocked"), hash("accept-blocked"), document.id(), proposal.id(),
                userEdited.id(), "nothing to apply");

        assertFalse(result.mutation().isPresent());
        assertEquals(userEdited.id(), documents.findCurrentRevision(WORKSPACE_ID, USER_ID, document.id()).orElseThrow().id());
    }

    @Test
    void acceptingAnAlreadyAcceptedProposalThrows() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-double-accept"), hash("create-for-double-accept"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long baseRevisionId = document.currentRevisionId();
        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, document.id(), baseRevisionId,
                Map.of("meeting.title", new FieldValue.TextValue("Concise title")), Map.of());
        DocumentMutationResult first = service.acceptPatch(
                WORKSPACE_ID, USER_ID, key("first-accept"), hash("first-accept"), document.id(), proposal.id(),
                baseRevisionId, "first accept").mutation().orElseThrow();

        assertThrows(
                PatchProposalNotFoundException.class,
                () -> service.acceptPatch(
                        WORKSPACE_ID, USER_ID, key("second-accept"), hash("second-accept"), document.id(), proposal.id(),
                        first.revision().id(), "second accept"));
    }

    @Test
    void recordReviewDecisionChangesOnlyReviewLeavingValueEvidenceAuthorshipAndLockUntouched() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        DocumentRevision initial = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-review"), hash("create-for-review"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of("meeting.title", List.of(501L)), "initial draft")
                .revision();

        DocumentRevision reviewed = service.recordReviewDecision(
                        WORKSPACE_ID, USER_ID, key("review-title"), hash("review-title"), initial.documentId(), initial.id(),
                        FieldItemRef.scalar("meeting.title"), ReviewState.ACCEPTED, "reviewer accepted the title")
                .revision();

        FieldState reviewedState = reviewed.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals(ReviewState.ACCEPTED, reviewedState.review());
        assertEquals(Authorship.USER_AUTHORED, reviewedState.authorship());
        assertEquals(EvidenceSupport.DIRECT, reviewedState.evidenceSupport());
        assertEquals(LockState.EDITABLE, reviewedState.lock());
        assertEquals("September minutes", ((FieldValue.TextValue) reviewed.content().fields().get("meeting.title")).value());
        assertEquals(List.of(501L), reviewed.evidence().get("meeting.title"));
    }

    @Test
    void setFieldLockChangesOnlyLockLeavingValueEvidenceAuthorshipAndReviewUntouched() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-lock"), hash("create-for-lock"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();

        DocumentRevision locked = service.setFieldLock(
                        WORKSPACE_ID, USER_ID, key("lock-title"), hash("lock-title"), document.id(), document.currentRevisionId(),
                        FieldItemRef.scalar("meeting.title"), LockState.EXPLICITLY_LOCKED, "protect the finalized title")
                .revision();

        FieldState lockedState = locked.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals(LockState.EXPLICITLY_LOCKED, lockedState.lock());
        assertEquals(ReviewState.UNREVIEWED, lockedState.review());
        assertEquals("September minutes", ((FieldValue.TextValue) locked.content().fields().get("meeting.title")).value());
    }

    @Test
    void directEditOfAnExplicitlyLockedFieldThrowsAndAppendsNothing() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-lock-block"), hash("create-for-lock-block"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        DocumentRevision locked = service.setFieldLock(
                        WORKSPACE_ID, USER_ID, key("lock-title-block"), hash("lock-title-block"), document.id(),
                        document.currentRevisionId(), FieldItemRef.scalar("meeting.title"), LockState.EXPLICITLY_LOCKED,
                        "protect the finalized title")
                .revision();

        FieldLockedException exception = assertThrows(
                FieldLockedException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("edit-locked-title"), hash("edit-locked-title"), document.id(), locked.id(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Sneaky change"))),
                        Map.of(), "attempted edit of a locked field"));

        assertEquals("meeting.title", exception.fieldId());
        assertEquals(2, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
    }

    @Test
    void aDirectEditOfAPreserveOnRegenerationFieldSucceedsAndKeepsThatSameLockAcrossTheEdit() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-preserve"), hash("create-for-preserve"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        DocumentRevision preserved = service.setFieldLock(
                        WORKSPACE_ID, USER_ID, key("preserve-title"), hash("preserve-title"), document.id(),
                        document.currentRevisionId(), FieldItemRef.scalar("meeting.title"), LockState.PRESERVE_ON_REGENERATION,
                        "protect from automated regeneration only")
                .revision();
        service.recordReviewDecision(
                WORKSPACE_ID, USER_ID, key("accept-before-edit"), hash("accept-before-edit"), document.id(), preserved.id(),
                FieldItemRef.scalar("meeting.title"), ReviewState.ACCEPTED, "accepted before the edit below");
        DocumentRevision beforeEdit = documents.findCurrentRevision(WORKSPACE_ID, USER_ID, document.id()).orElseThrow();

        DocumentRevision edited = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("edit-preserved-title"), hash("edit-preserved-title"), document.id(),
                        beforeEdit.id(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("A human's own change"))),
                        Map.of(), "a person directly edits a preserve-on-regeneration field")
                .revision();

        FieldState editedState = edited.fieldStates().get(FieldItemRef.scalar("meeting.title"));
        assertEquals("A human's own change", ((FieldValue.TextValue) edited.content().fields().get("meeting.title")).value());
        assertEquals(LockState.PRESERVE_ON_REGENERATION, editedState.lock());
        // Invalidation after edits: the prior ACCEPTED review does not survive a value change.
        assertEquals(ReviewState.UNREVIEWED, editedState.review());
    }

    @Test
    void undoToRevisionAppendsANewRevisionExactlyMatchingAnEarlierOnesContentEvidenceAndFieldStates() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        DocumentRevision initial = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-undo"), hash("create-for-undo"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of("meeting.title", List.of(501L)), "initial draft")
                .revision();
        service.recordReviewDecision(
                WORKSPACE_ID, USER_ID, key("accept-initial-title"), hash("accept-initial-title"), initial.documentId(), initial.id(),
                FieldItemRef.scalar("meeting.title"), ReviewState.ACCEPTED, "accepted the original title");
        DocumentRevision reviewedInitial = documents.findCurrentRevision(WORKSPACE_ID, USER_ID, initial.documentId()).orElseThrow();
        DocumentRevision edited = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("edit-away-from-undo-target"), hash("edit-away-from-undo-target"),
                        initial.documentId(), reviewedInitial.id(),
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("A change to undo"))),
                        Map.of(), "a change that will be undone")
                .revision();

        DocumentRevision undone = service.undoToRevision(
                        WORKSPACE_ID, USER_ID, key("undo-title-change"), hash("undo-title-change"), initial.documentId(), edited.id(),
                        reviewedInitial.id(), "reverted to the reviewed original title")
                .revision();

        assertEquals("September minutes", ((FieldValue.TextValue) undone.content().fields().get("meeting.title")).value());
        assertEquals(reviewedInitial.evidence(), undone.evidence());
        assertEquals(reviewedInitial.fieldStates(), undone.fieldStates());
        // initial (1) -> reviewed (2) -> edited (3) -> undone (4).
        assertEquals(4, service.findHistory(WORKSPACE_ID, USER_ID, initial.documentId()).size());
        // Undo never rewrites history -- the revision being undone away from is still there, unchanged.
        assertEquals(
                "A change to undo",
                ((FieldValue.TextValue) service.findRevision(WORKSPACE_ID, USER_ID, initial.documentId(), edited.id())
                        .orElseThrow()
                        .content()
                        .fields()
                        .get("meeting.title"))
                        .value());
    }

    // --- Adversarial sweep: simultaneous edits, edited owner/date values, locked sections,
    // --- unsupported user assertions, and targeted rewrites, each proven against a real, named scenario
    // --- rather than assumed correct from the individual pieces' own unit tests above.

    @Test
    void simultaneousEditsFromTwoActorsProduceAReviewableConflictNeverLastWriteWinsDataLoss() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-race"), hash("create-for-race"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        long sharedBaseRevisionId = document.currentRevisionId();

        // Actor A and actor B both read the same current revision, then both attempt to edit it.
        DocumentRevision actorAResult = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("actor-a-edit"), hash("actor-a-edit"), document.id(), sharedBaseRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Actor A's title"))),
                        Map.of(), "actor A's own edit")
                .revision();

        DocumentRevisionConflictException conflict = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID, 2L, key("actor-b-edit"), hash("actor-b-edit"), document.id(), sharedBaseRevisionId,
                        List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Actor B's title"))),
                        Map.of(), "actor B's own edit, based on the now-stale revision"));

        // The conflict names the real current revision so actor B's own client can re-fetch and retry --
        // never silently discarded, and actor A's own work is never overwritten by B's stale attempt.
        assertEquals(sharedBaseRevisionId, conflict.expectedRevisionId());
        assertEquals(actorAResult.id(), conflict.currentRevisionId());
        assertEquals("Actor A's title", ((FieldValue.TextValue) documents.findCurrentRevision(WORKSPACE_ID, USER_ID, document.id())
                .orElseThrow()
                .content()
                .fields()
                .get("meeting.title"))
                .value());
        assertEquals(2, service.findHistory(WORKSPACE_ID, USER_ID, document.id()).size());
    }

    @Test
    void editingARepeatedFieldWithOneLockedItemBlocksTheWholeFieldEditRatherThanSilentlyPartiallyApplying() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-item-lock"), hash("create-for-item-lock"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        DocumentRevision withTasks = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("add-tasks"), hash("add-tasks"), document.id(), document.currentRevisionId(),
                        List.of(new DocumentFieldEdit.SetValue(
                                "action.tasks", new FieldValue.RepeatedTextValue(List.of("Send agenda", "Book room")))),
                        Map.of(), "add two tasks")
                .revision();
        DocumentRevision locked = service.setFieldLock(
                        WORKSPACE_ID, USER_ID, key("lock-second-task"), hash("lock-second-task"), document.id(), withTasks.id(),
                        FieldItemRef.item("action.tasks", 1), LockState.EXPLICITLY_LOCKED, "the second task is finalized")
                .revision();

        // Even though only item 1 is locked, a repeated field edit replaces the whole list at once (it is
        // deliberately not a general per-item patch -- see DocumentFieldEdit's own javadoc), so the whole
        // edit is refused rather than silently rewriting the locked item alongside the unlocked one.
        assertThrows(
                FieldLockedException.class,
                () -> service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("edit-tasks-with-locked-item"), hash("edit-tasks-with-locked-item"),
                        document.id(), locked.id(),
                        List.of(new DocumentFieldEdit.SetValue(
                                "action.tasks", new FieldValue.RepeatedTextValue(List.of("Send updated agenda", "Book room")))),
                        Map.of(), "attempt to change item 0 only"));
    }

    @Test
    void editedOwnerAndDateValuesOnDifferentActionItemsRemainIndependentAcrossAnEdit() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        Document document = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-owner-date"), hash("create-for-owner-date"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "initial draft")
                .document();
        DocumentRevision withOwners = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("set-owners"), hash("set-owners"), document.id(), document.currentRevisionId(),
                        List.of(new DocumentFieldEdit.SetValue(
                                "action.item.owner", new FieldValue.RepeatedTextValue(List.of("Alex", "Priya")))),
                        Map.of(), "assign owners")
                .revision();
        // Lock item 0's own owner ("Alex", on action.item.owner) -- a different field entirely from due dates.
        DocumentRevision lockedOwner = service.setFieldLock(
                        WORKSPACE_ID, USER_ID, key("lock-owner-0"), hash("lock-owner-0"), document.id(), withOwners.id(),
                        FieldItemRef.item("action.item.owner", 0), LockState.EXPLICITLY_LOCKED, "confirmed owner, do not reassign")
                .revision();

        // Editing action.item.due (a sibling repeated field, unrelated to action.item.owner) must succeed:
        // one item's own lock on one field must never bleed into a same-index item on a different field.
        DocumentRevision withDueDates = service.applyUserEdits(
                        WORKSPACE_ID, USER_ID, key("set-due-dates"), hash("set-due-dates"), document.id(), lockedOwner.id(),
                        List.of(new DocumentFieldEdit.SetValue(
                                "action.item.due",
                                new FieldValue.RepeatedDateValue(List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8))))),
                        Map.of(), "assign due dates")
                .revision();

        assertEquals(
                List.of("Alex", "Priya"),
                ((FieldValue.RepeatedTextValue) withDueDates.content().fields().get("action.item.owner")).values());
        assertEquals(
                LockState.EXPLICITLY_LOCKED,
                withDueDates.fieldStates().get(FieldItemRef.item("action.item.owner", 0)).lock());
        assertEquals(
                List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)),
                ((FieldValue.RepeatedDateValue) withDueDates.content().fields().get("action.item.due")).values());
    }

    @Test
    void aUserTypedValueWithNoCitationIsRecordedAsUnsupportedNeverSilentlyTreatedAsDirectlyEvidenced() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());

        // An unsupported assertion: the person asserts a meeting date with no citation backing it at all.
        DocumentRevision created = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-unsupported"), hash("create-unsupported"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of(), "typed from memory, no source open")
                .revision();

        FieldState dateState = created.fieldStates().get(FieldItemRef.scalar("meeting.date"));
        assertEquals(EvidenceSupport.MISSING, dateState.evidenceSupport());
        assertEquals(Authorship.USER_AUTHORED, dateState.authorship());
        // Unsupported is not the same as absent or invalid -- the value itself is still recorded and usable.
        assertEquals(LocalDate.of(2026, 9, 1), ((FieldValue.DateValue) created.content().fields().get("meeting.date")).value());
    }

    @Test
    void aTargetedRewriteViaAnAcceptedPatchLeavesEveryOtherFieldByteForByteUntouched() {
        FakeDocumentRepository documents = new FakeDocumentRepository();
        RevisionService service = new RevisionService(documents, new ActiveTemplateRepository(), new FakePatchProposalRepository());
        DocumentRevision initial = service.createDocument(
                        WORKSPACE_ID, USER_ID, key("create-for-targeted-rewrite"), hash("create-for-targeted-rewrite"), "Minutes",
                        TEMPLATE_ID, TEMPLATE_VERSION_ID, initialContent(), Map.of("meeting.date", List.of(801L)), "initial draft")
                .revision();
        FieldState dateStateBefore = initial.fieldStates().get(FieldItemRef.scalar("meeting.date"));

        // A targeted rewrite: only meeting.title is proposed and accepted, exactly "show the
        // proposed replacement and its affected scope" -- the scope here is the single field the proposal names.
        PatchProposal proposal = service.proposePatch(
                WORKSPACE_ID, USER_ID, initial.documentId(), initial.id(),
                Map.of("meeting.title", new FieldValue.TextValue("Rewritten, concise title")), Map.of());
        DocumentRevision rewritten = service.acceptPatch(
                        WORKSPACE_ID, USER_ID, key("accept-targeted-rewrite"), hash("accept-targeted-rewrite"),
                        initial.documentId(), proposal.id(), initial.id(), "targeted rewrite of the title only")
                .mutation()
                .orElseThrow()
                .revision();

        assertEquals("Rewritten, concise title", ((FieldValue.TextValue) rewritten.content().fields().get("meeting.title")).value());
        // Every dimension of the untouched sibling field is identical, not just its value.
        assertEquals(initial.content().fields().get("meeting.date"), rewritten.content().fields().get("meeting.date"));
        assertEquals(initial.evidence().get("meeting.date"), rewritten.evidence().get("meeting.date"));
        assertEquals(dateStateBefore, rewritten.fieldStates().get(FieldItemRef.scalar("meeting.date")));
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
                        field("action.tasks", FieldType.TEXT, FieldCardinality.REPEATED),
                        field("action.item.owner", FieldType.TEXT, FieldCardinality.REPEATED),
                        field("action.item.due", FieldType.DATE, FieldCardinality.REPEATED)),
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
        public List<Template> findAll(long workspaceId, long userId) {
            throw new UnsupportedOperationException();
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
                Map<String, List<Long>> initialEvidence,
                Map<FieldItemRef, FieldState> initialFieldStates,
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
                    OffsetDateTime.now(),
                    initialEvidence,
                    initialFieldStates);
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
        public List<Document> findAllForWorkspace(long workspaceId, long userId) {
            throw new UnsupportedOperationException();
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
                Map<String, List<Long>> evidence,
                Map<FieldItemRef, FieldState> fieldStates,
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
                    OffsetDateTime.now(),
                    evidence,
                    fieldStates);
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

    private static final class FakePatchProposalRepository implements PatchProposalRepository {

        private final AtomicLong proposalIds = new AtomicLong();
        private final Map<Long, PatchProposal> proposals = new HashMap<>();

        @Override
        public PatchProposal create(
                long workspaceId,
                long userId,
                long documentId,
                long baseRevisionId,
                Map<String, FieldValue> proposedValues,
                Map<String, List<Long>> proposedEvidence) {
            long id = proposalIds.incrementAndGet();
            PatchProposal proposal = new PatchProposal(
                    id, workspaceId, documentId, baseRevisionId, proposedValues, proposedEvidence,
                    PatchProposalStatus.PROPOSED, OffsetDateTime.now());
            proposals.put(id, proposal);
            return proposal;
        }

        @Override
        public Optional<PatchProposal> find(long workspaceId, long userId, long documentId, long proposalId) {
            PatchProposal proposal = proposals.get(proposalId);
            return proposal != null && proposal.workspaceId() == workspaceId && proposal.documentId() == documentId
                    ? Optional.of(proposal)
                    : Optional.empty();
        }

        @Override
        public void markAccepted(long workspaceId, long userId, long documentId, long proposalId) {
            PatchProposal proposal = proposals.get(proposalId);
            if (proposal == null
                    || proposal.workspaceId() != workspaceId
                    || proposal.documentId() != documentId
                    || proposal.status() != PatchProposalStatus.PROPOSED) {
                throw new PatchProposalNotFoundException(proposalId);
            }
            proposals.put(proposalId, new PatchProposal(
                    proposal.id(), proposal.workspaceId(), proposal.documentId(), proposal.baseRevisionId(),
                    proposal.proposedValues(), proposal.proposedEvidence(), PatchProposalStatus.ACCEPTED, proposal.createdAt()));
        }
    }
}
