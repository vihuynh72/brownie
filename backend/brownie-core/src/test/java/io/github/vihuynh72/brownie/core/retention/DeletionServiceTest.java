package io.github.vihuynh72.brownie.core.retention;

import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database routines answer in plain words; this is where each word
 * becomes what a caller is told. The distinctions matter to a person: "it
 * was already deleted", "it was restored first" and "try again in a moment"
 * are three different situations, and only the last one means waiting helps.
 */
class DeletionServiceTest {

    @Test
    void aRetentionOutsideItsBoundsStopsTheApplicationFromStarting() {
        assertThrows(IllegalArgumentException.class, () -> new DeletionService(new FakeRepository(), 0));
        assertThrows(IllegalArgumentException.class, () -> new DeletionService(new FakeRepository(), 366));
        assertEquals(30, new DeletionService(new FakeRepository(), 30).trashRetentionDays());
    }

    @Test
    void trashingPassesTheConfiguredRetentionAndADocumentThatIsNotThereIsNotFound() {
        FakeRepository repository = new FakeRepository();
        DeletionService service = new DeletionService(repository, 14);

        assertEquals(500, service.trashDocument(7, 1, 40).id());
        assertEquals(14, repository.retentionDaysSeen);

        repository.trashAnswer = Optional.empty();
        assertThrows(DocumentNotFoundException.class, () -> service.trashDocument(7, 1, 41));
    }

    @Test
    void eachRestoreOutcomeIsToldApart() {
        FakeRepository repository = new FakeRepository();
        DeletionService service = new DeletionService(repository, 30);

        repository.restoreAnswer = RestoreOutcome.RESTORED;
        assertEquals(500, service.restoreDocument(7, 1, 500).id());
        repository.restoreAnswer = RestoreOutcome.NOT_OPEN;
        assertThrows(DeletionStateConflictException.class, () -> service.restoreDocument(7, 1, 500));
        repository.restoreAnswer = RestoreOutcome.NOT_FOUND;
        assertThrows(DeletionRequestNotFoundException.class, () -> service.restoreDocument(7, 1, 500));
    }

    @Test
    void eachPurgeOutcomeIsToldApart() {
        FakeRepository repository = new FakeRepository();
        DeletionService service = new DeletionService(repository, 30);

        repository.purgeAnswer = PurgeOutcome.PURGED;
        assertEquals(500, service.purgeDocument(7, 1, 500).id());
        repository.purgeAnswer = PurgeOutcome.NOT_OPEN;
        assertThrows(DeletionStateConflictException.class, () -> service.purgeDocument(7, 1, 500));
        repository.purgeAnswer = PurgeOutcome.JOBS_STILL_STOPPING;
        assertThrows(DeletionWaitingForRunningWorkException.class, () -> service.purgeDocument(7, 1, 500));
        repository.purgeAnswer = PurgeOutcome.NOT_FOUND;
        assertThrows(DeletionRequestNotFoundException.class, () -> service.purgeDocument(7, 1, 500));
    }

    /** A workspace is deleted entirely or not at all; "wait a moment" must never be mistaken for "done". */
    @Test
    void aWorkspaceIsDeletedOrTheCallerIsToldToWaitOrRefusedAndNothingInBetween() {
        FakeRepository repository = new FakeRepository();
        DeletionService service = new DeletionService(repository, 30);

        assertEquals(900L, service.deleteWorkspace(7, 1));
        repository.workspaceAnswer = new WorkspaceDeletion(PurgeOutcome.JOBS_STILL_STOPPING, null);
        assertThrows(DeletionWaitingForRunningWorkException.class, () -> service.deleteWorkspace(7, 1));
        repository.workspaceAnswer = new WorkspaceDeletion(PurgeOutcome.NOT_FOUND, null);
        assertThrows(WorkspaceDeletionNotPermittedException.class, () -> service.deleteWorkspace(7, 1));
    }

    private static final class FakeRepository implements DeletionRepository {

        Optional<Long> trashAnswer = Optional.of(500L);
        RestoreOutcome restoreAnswer = RestoreOutcome.RESTORED;
        PurgeOutcome purgeAnswer = PurgeOutcome.PURGED;
        WorkspaceDeletion workspaceAnswer = new WorkspaceDeletion(PurgeOutcome.PURGED, 900L);
        int retentionDaysSeen;

        @Override
        public Optional<Long> trashDocument(long workspaceId, long userId, long documentId, int retentionDays) {
            retentionDaysSeen = retentionDays;
            return trashAnswer;
        }

        @Override
        public RestoreOutcome restoreDocument(long workspaceId, long userId, long requestId) {
            return restoreAnswer;
        }

        @Override
        public PurgeOutcome purgeDocument(long workspaceId, long userId, long requestId) {
            return purgeAnswer;
        }

        @Override
        public WorkspaceDeletion deleteWorkspace(long workspaceId, long userId) {
            return workspaceAnswer;
        }

        @Override
        public Optional<DeletionRequest> find(long workspaceId, long userId, long requestId) {
            return Optional.of(new DeletionRequest(
                    requestId, workspaceId, DeletionScope.DOCUMENT, 40, DeletionState.TRASHED, userId,
                    OffsetDateTime.now(), OffsetDateTime.now().plusDays(30), null, null, null, 0, "Minutes"));
        }

        @Override
        public List<DeletionRequest> findAll(long workspaceId, long userId) {
            return List.of();
        }
    }
}
