package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.worker.persistence.JdbcWorkerUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Keeps the two append-only records honest over time. A model usage
 * reservation that was never closed belongs to a process that died between
 * asking and recording; nobody knows whether the provider served it, so it
 * is kept at its full amount rather than dropped, which can only over-count
 * what was spent. Audit events are kept for their retention period and then
 * removed.
 */
@Component
@ConditionalOnProperty(
        name = "brownie.worker.retention.ledger-maintenance.enabled",
        havingValue = "true",
        matchIfMissing = true)
class LedgerMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(LedgerMaintenanceScheduler.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcWorkerUsageRepository usageRepository;
    private final Duration staleReservationAfter;
    private final Duration auditRetention;

    LedgerMaintenanceScheduler(
            JdbcWorkerUsageRepository usageRepository,
            @Value("${brownie.worker.retention.ledger-maintenance.stale-reservation-after:PT30M}") Duration staleReservationAfter,
            @Value("${brownie.worker.retention.ledger-maintenance.audit-retention:P90D}") Duration auditRetention) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        if (staleReservationAfter.compareTo(Duration.ofMinutes(10)) < 0) {
            throw new IllegalArgumentException("A reservation cannot be called stale before ten minutes have passed.");
        }
        if (auditRetention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("Audit events are kept for at least one day.");
        }
        this.staleReservationAfter = staleReservationAfter;
        this.auditRetention = auditRetention;
    }

    @Scheduled(fixedDelayString = "${brownie.worker.retention.ledger-maintenance.delay:PT15M}")
    void maintain() {
        try {
            int retained = usageRepository.retainStale(staleReservationAfter, BATCH_SIZE);
            if (retained > 0) {
                // Worth a warning: each one is a request whose outcome was never recorded.
                log.warn("Kept {} model usage reservations that were never closed at their full amount.", retained);
            }
            int expired = usageRepository.expireAuditEvents(auditRetention, BATCH_SIZE);
            if (expired > 0) {
                log.info("Removed {} audit events older than {}.", expired, auditRetention);
            }
        } catch (RuntimeException failure) {
            log.error("Ledger maintenance failed and will run again on its next turn.", failure);
        }
    }
}
