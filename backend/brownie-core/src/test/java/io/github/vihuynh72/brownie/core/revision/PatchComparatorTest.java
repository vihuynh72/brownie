package io.github.vihuynh72.brownie.core.revision;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link PatchComparator}, the pure three-way comparison {@link RevisionService#acceptPatch} relies on. */
class PatchComparatorTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long DOCUMENT_ID = 2L;

    @Test
    void aFieldCurrentStillAgreesWithBaseOnIsClean() {
        DocumentRevision base = revision(1, 1, null, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")), Map.of());
        DocumentRevision current = revision(2, 1, 1L, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")), Map.of());

        PatchComparison comparison = PatchComparator.compare(
                base, current, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync, revised")));

        assertEquals(PatchFieldStatus.CLEAN, comparison.fieldStatuses().get("meeting.title"));
        assertEquals(Set.of("meeting.title"), comparison.applicableFields());
        assertFalse(comparison.hasBlockedFields());
    }

    @Test
    void aFieldCurrentHasChangedSinceBaseIsAConflictAndNeverApplicable() {
        DocumentRevision base = revision(1, 1, null, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")), Map.of());
        DocumentRevision current = revision(2, 1, 1L, Map.of("meeting.title", new FieldValue.TextValue("Budget sync")), Map.of());

        PatchComparison comparison = PatchComparator.compare(
                base, current, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync, revised")));

        assertEquals(PatchFieldStatus.CONFLICT, comparison.fieldStatuses().get("meeting.title"));
        assertTrue(comparison.applicableFields().isEmpty());
        assertTrue(comparison.hasBlockedFields());
    }

    @Test
    void aLockedScalarFieldIsLockedEvenWhenItAlsoStillAgreesWithBase() {
        DocumentRevision base = revision(1, 1, null, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")), Map.of());
        DocumentRevision current = revision(
                2, 1, 1L, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")),
                Map.of(FieldItemRef.scalar("meeting.title"), locked()));

        PatchComparison comparison = PatchComparator.compare(
                base, current, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync, revised")));

        assertEquals(PatchFieldStatus.LOCKED, comparison.fieldStatuses().get("meeting.title"));
        assertTrue(comparison.applicableFields().isEmpty());
    }

    @Test
    void preserveOnRegenerationBlocksAcceptanceTheSameWayExplicitLockDoes() {
        DocumentRevision base = revision(1, 1, null, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")), Map.of());
        DocumentRevision current = revision(
                2, 1, 1L, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync")),
                Map.of(FieldItemRef.scalar("meeting.title"), new FieldState(
                        Authorship.USER_AUTHORED, EvidenceSupport.DIRECT, ValidationState.NOT_RUN, ReviewState.UNREVIEWED,
                        LockState.PRESERVE_ON_REGENERATION)));

        PatchComparison comparison = PatchComparator.compare(
                base, current, Map.of("meeting.title", new FieldValue.TextValue("Weekly sync, revised")));

        assertEquals(PatchFieldStatus.LOCKED, comparison.fieldStatuses().get("meeting.title"));
    }

    @Test
    void aLockedItemWithinARepeatedFieldLocksTheWholeField() {
        FieldValue.RepeatedTextValue tasks = new FieldValue.RepeatedTextValue(List.of("Send agenda", "Book room"));
        DocumentRevision base = revision(1, 1, null, Map.of("action.tasks", tasks), Map.of());
        DocumentRevision current = revision(
                2, 1, 1L, Map.of("action.tasks", tasks),
                Map.of(FieldItemRef.item("action.tasks", 1), locked()));

        PatchComparison comparison = PatchComparator.compare(
                base, current, Map.of("action.tasks", new FieldValue.RepeatedTextValue(List.of("Send agenda", "Book bigger room"))));

        assertEquals(PatchFieldStatus.LOCKED, comparison.fieldStatuses().get("action.tasks"));
    }

    @Test
    void onlyFieldsTheProposalActuallyTouchesAppearInTheComparison() {
        DocumentRevision base = revision(
                1, 1, null,
                Map.of("meeting.title", new FieldValue.TextValue("Weekly sync"), "meeting.date", new FieldValue.TextValue("2026-09-01")),
                Map.of());
        DocumentRevision current = revision(
                2, 1, 1L,
                Map.of("meeting.title", new FieldValue.TextValue("Budget sync"), "meeting.date", new FieldValue.TextValue("2026-09-01")),
                Map.of());

        PatchComparison comparison = PatchComparator.compare(base, current, Map.of("meeting.date", new FieldValue.TextValue("2026-09-02")));

        assertEquals(1, comparison.fieldStatuses().size());
        assertEquals(PatchFieldStatus.CLEAN, comparison.fieldStatuses().get("meeting.date"));
    }

    private static FieldState locked() {
        return new FieldState(
                Authorship.USER_AUTHORED, EvidenceSupport.DIRECT, ValidationState.NOT_RUN, ReviewState.UNREVIEWED, LockState.EXPLICITLY_LOCKED);
    }

    private static DocumentRevision revision(
            long id, int revisionNumber, Long parentRevisionId, Map<String, FieldValue> fields, Map<FieldItemRef, FieldState> fieldStates) {
        DocumentContent content = new DocumentContent(fields);
        return new DocumentRevision(
                id, WORKSPACE_ID, DOCUMENT_ID, revisionNumber, parentRevisionId, content,
                DocumentContentHasher.sha256Hex(content), 9L, "test revision", OffsetDateTime.now(), Map.of(), fieldStates);
    }

}
