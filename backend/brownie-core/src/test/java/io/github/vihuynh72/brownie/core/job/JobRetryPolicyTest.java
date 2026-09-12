package io.github.vihuynh72.brownie.core.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRetryPolicyTest {

    private final JobRetryPolicy policy = new JobRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));

    @Test
    void growsExponentiallyWithStableBoundedJitter() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-11T12:00:00Z");

        OffsetDateTime first = policy.nextRetryAt(1, 101L, 1L, now, null);
        OffsetDateTime third = policy.nextRetryAt(3, 101L, 3L, now, null);

        assertTrue(first.isAfter(now.plus(Duration.ofMillis(799))));
        assertTrue(first.isBefore(now.plus(Duration.ofMillis(1_201))));
        assertTrue(third.isAfter(now.plus(Duration.ofMillis(3_199))));
        assertTrue(third.isBefore(now.plus(Duration.ofMillis(4_801))));
        assertEquals(third, policy.nextRetryAt(3, 101L, 3L, now, null));
    }

    @Test
    void neverRetriesBeforeProviderRetryHint() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-11T12:00:00Z");

        OffsetDateTime retryAt = policy.nextRetryAt(1, 11L, 1L, now, Duration.ofMinutes(8));

        assertTrue(!retryAt.isBefore(now.plus(Duration.ofMinutes(8))));
    }

    @Test
    void rejectsInvalidAttemptsAndDurations() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-11T12:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> policy.nextRetryAt(0, 1L, 1L, now, null));
        assertThrows(IllegalArgumentException.class, () -> new JobRetryPolicy(Duration.ZERO, Duration.ofSeconds(1)));
    }
}
