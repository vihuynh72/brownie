package io.github.vihuynh72.brownie.worker.job;

import io.github.vihuynh72.brownie.core.job.JobOutputReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Periodically reclaims discarded temporary objects in small bounded batches. */
@Component
@ConditionalOnProperty(
        name = "brownie.worker.outputs.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true)
class JobOutputReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobOutputReconciliationScheduler.class);

    private final JobOutputReconciler reconciler;
    private final int batchSize;

    JobOutputReconciliationScheduler(
            JobOutputReconciler reconciler,
            @Value("${brownie.worker.outputs.reconciliation.batch-size:32}") int batchSize) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
        if (batchSize < 1 || batchSize > 512) {
            throw new IllegalArgumentException("brownie.worker.outputs.reconciliation.batch-size must be between one and 512.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${brownie.worker.outputs.reconciliation.delay:PT1M}")
    void reconcile() {
        int cleaned = reconciler.reconcileOnce(batchSize);
        if (cleaned > 0) {
            log.info("Reclaimed {} discarded temporary job outputs.", cleaned);
        }
    }
}
