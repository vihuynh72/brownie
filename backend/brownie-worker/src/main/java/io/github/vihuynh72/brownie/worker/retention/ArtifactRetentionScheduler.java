package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.retention.ArtifactRetentionSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Periodically runs file housekeeping in small bounded batches. */
@Component
@ConditionalOnProperty(
        name = "brownie.worker.retention.artifact-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
class ArtifactRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionScheduler.class);

    private final ArtifactRetentionSweeper sweeper;
    private final int batchSize;

    ArtifactRetentionScheduler(
            ArtifactRetentionSweeper sweeper,
            @Value("${brownie.worker.retention.artifact-sweep.batch-size:64}") int batchSize) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper must not be null");
        if (batchSize < 1 || batchSize > 512) {
            throw new IllegalArgumentException("brownie.worker.retention.artifact-sweep.batch-size must be between one and 512.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${brownie.worker.retention.artifact-sweep.delay:PT10M}")
    void sweep() {
        try {
            ArtifactRetentionSweeper.Result result = sweeper.sweepOnce(batchSize);
            if (result.didAnything()) {
                log.info(
                        "File housekeeping: {} stale uploads moved on, {} refused files' bytes removed ({} failed).",
                        result.uploadsExpired(),
                        result.payloadsRemoved(),
                        result.payloadsFailed());
            }
        } catch (RuntimeException failure) {
            log.error("File housekeeping failed and will run again on its next turn.", failure);
        }
    }
}
