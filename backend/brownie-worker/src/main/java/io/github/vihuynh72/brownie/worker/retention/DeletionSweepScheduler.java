package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.retention.DeletionArchiver;
import io.github.vihuynh72.brownie.core.retention.DeletionSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Periodically carries out expired trash, removes the stored objects that
 * deletions queued, and closes requests with nothing left, in small bounded
 * batches. A failure in one pass is logged and the next pass starts from
 * the database's own record of what is still owed, so nothing depends on
 * this process remembering anything.
 */
@Component
@ConditionalOnProperty(
        name = "brownie.worker.retention.deletion-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DeletionSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeletionSweepScheduler.class);

    private final DeletionSweeper sweeper;
    private final DeletionArchiver archiver;
    private final int batchSize;

    DeletionSweepScheduler(
            DeletionSweeper sweeper,
            DeletionArchiver archiver,
            @Value("${brownie.worker.retention.deletion-sweep.batch-size:32}") int batchSize) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper must not be null");
        this.archiver = Objects.requireNonNull(archiver, "archiver must not be null");
        if (batchSize < 1 || batchSize > 256) {
            throw new IllegalArgumentException("brownie.worker.retention.deletion-sweep.batch-size must be between one and 256.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${brownie.worker.retention.deletion-sweep.delay:PT30S}")
    void sweep() {
        // Copying out comes first, so a deletion a person just made is on
        // record outside the database before its files are even removed,
        // and can be closed in this same pass. One failing must not stop
        // the other: files are still removed while the archive is away.
        try {
            DeletionArchiver.ArchiveResult archived = archiver.archiveOnce(batchSize);
            if (archived.archived() + archived.failed() > 0) {
                log.info("Deletion archive: {} deletions recorded outside the database ({} failed).",
                        archived.archived(), archived.failed());
            }
        } catch (RuntimeException failure) {
            log.error("Recording deletions outside the database failed and will run again on its next turn.", failure);
        }
        try {
            DeletionSweeper.Result result = sweeper.sweepOnce(batchSize);
            if (result.didAnything()) {
                log.info(
                        "Deletion sweep: {} expired trash entries deleted ({} failed), {} stored objects removed ({} failed), {} requests verified.",
                        result.trashPurged(),
                        result.trashFailed(),
                        result.objectsRemoved(),
                        result.objectsFailed(),
                        result.requestsVerified());
            }
        } catch (RuntimeException failure) {
            log.error("Deletion sweep failed and will run again on its next turn.", failure);
        }
    }
}
