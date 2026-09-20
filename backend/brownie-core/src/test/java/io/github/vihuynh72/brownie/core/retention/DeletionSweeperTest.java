package io.github.vihuynh72.brownie.core.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule that keeps permanent deletion honest when storage
 * misbehaves: an object is recorded as gone only after the store has
 * actually accepted its removal, and one object that cannot be removed
 * right now neither stops the others nor gets forgotten.
 */
class DeletionSweeperTest {

    @Test
    void anObjectTheStoreRefusesToRemoveStaysQueuedWhileTheOthersAreRecordedAsGone() {
        FakeSweepRepository repository = new FakeSweepRepository();
        repository.pending.add(new DeletionBlobTask(1, 7, 100, "workspace-7/first", 1));
        repository.pending.add(new DeletionBlobTask(2, 7, 100, "workspace-7/stuck", 3));
        repository.pending.add(new DeletionBlobTask(3, 7, 100, "workspace-7/third", 1));
        FakeBlobStore blobStore = new FakeBlobStore("workspace-7/stuck");

        DeletionSweeper.Result result = new DeletionSweeper(repository, blobStore).sweepOnce(10);

        assertEquals(2, result.objectsRemoved());
        assertEquals(1, result.objectsFailed());
        assertEquals(List.of("workspace-7/first", "workspace-7/third"), blobStore.deleted);
        assertEquals(List.of("workspace-7/first", "workspace-7/third"), repository.markedDeleted);
    }

    /** A storage client can fail with something other than an I/O error; one such object must not cost the rest of the pass. */
    @Test
    void anUnexpectedFailureOnOneObjectDoesNotStopTheBatchOrTheClosingRecount() {
        FakeSweepRepository repository = new FakeSweepRepository();
        repository.pending.add(new DeletionBlobTask(1, 7, 100, "workspace-7/explodes", 9));
        repository.pending.add(new DeletionBlobTask(2, 7, 100, "workspace-7/fine", 1));
        repository.unverified.add(21L);
        FakeBlobStore blobStore = new FakeBlobStore(null);
        blobStore.keyThatThrowsUnchecked = "workspace-7/explodes";

        DeletionSweeper.Result result = new DeletionSweeper(repository, blobStore).sweepOnce(10);

        assertEquals(1, result.objectsRemoved());
        assertEquals(1, result.objectsFailed());
        assertEquals(1, result.requestsVerified());
        assertEquals(List.of("workspace-7/fine"), repository.markedDeleted);
    }

    @Test
    void aPassReportsWhatEachStepDidAndAnEntryThatFailedIsCountedNotHidden() {
        FakeSweepRepository repository = new FakeSweepRepository();
        repository.expired.add(new ExpiredTrashResult(11, "PURGED"));
        repository.expired.add(new ExpiredTrashResult(12, "FAILED"));
        repository.expired.add(new ExpiredTrashResult(13, "JOBS_STILL_STOPPING"));
        repository.unverified.add(21L);
        repository.unverified.add(22L);
        repository.unverifiable.add(22L);

        DeletionSweeper.Result result = new DeletionSweeper(repository, new FakeBlobStore(null)).sweepOnce(10);

        assertEquals(1, result.trashPurged());
        assertEquals(1, result.trashFailed());
        assertEquals(1, result.requestsVerified());
        assertTrue(result.didAnything());
    }

    @Test
    void aPassWithNothingOwedReportsThatItDidNothing() {
        DeletionSweeper.Result result = new DeletionSweeper(new FakeSweepRepository(), new FakeBlobStore(null)).sweepOnce(10);

        assertFalse(result.didAnything());
    }

    @Test
    void aNonPositiveBatchSizeIsRefused() {
        DeletionSweeper sweeper = new DeletionSweeper(new FakeSweepRepository(), new FakeBlobStore(null));

        assertThrows(IllegalArgumentException.class, () -> sweeper.sweepOnce(0));
    }

    private static final class FakeSweepRepository implements DeletionSweepRepository {

        final List<ExpiredTrashResult> expired = new ArrayList<>();
        final List<DeletionBlobTask> pending = new ArrayList<>();
        final List<Long> unverified = new ArrayList<>();
        final List<Long> unverifiable = new ArrayList<>();
        final List<String> markedDeleted = new ArrayList<>();

        @Override
        public List<ExpiredTrashResult> purgeExpiredTrash(int limit) {
            return expired;
        }

        @Override
        public List<DeletionBlobTask> collectPendingBlobDeletions(int limit) {
            return pending;
        }

        @Override
        public boolean markBlobDeleted(long taskId, String objectKey) {
            markedDeleted.add(objectKey);
            return true;
        }

        @Override
        public List<Long> collectUnverifiedDeletions(int limit) {
            return unverified;
        }

        @Override
        public boolean verifyDeletion(long requestId) {
            return !unverifiable.contains(requestId);
        }
    }

    private static final class FakeBlobStore implements BlobStore {

        private final String keyThatFails;
        String keyThatThrowsUnchecked;
        final List<String> deleted = new ArrayList<>();

        FakeBlobStore(String keyThatFails) {
            this.keyThatFails = keyThatFails;
        }

        @Override
        public void delete(String objectKey) throws IOException {
            if (objectKey.equals(keyThatFails)) {
                throw new IOException("The store is unavailable.");
            }
            if (objectKey.equals(keyThatThrowsUnchecked)) {
                throw new IllegalStateException("The storage client failed in a way it does not declare.");
            }
            deleted.add(objectKey);
        }

        @Override
        public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public UploadResult writeNewAndDigest(String objectKey, InputStream content, long maxBytes) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<Long> sizeOf(String objectKey) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public InputStream openStream(String objectKey) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }
}
