package io.github.vihuynh72.brownie.core.job;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTransitionValidatorTest {

    @Test
    void acceptsQueueLeaseRetryWaitAndTerminalTransitions() {
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.QUEUED, JobState.LEASED));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.QUEUED, JobState.DEAD));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.LEASED, JobState.QUEUED));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.LEASED, JobState.WAITING_FOR_INPUT));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.LEASED, JobState.DEAD));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.WAITING_FOR_INPUT, JobState.QUEUED));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.FAILED, JobState.QUEUED));
        assertDoesNotThrow(() -> JobTransitionValidator.requireAllowed(JobState.DEAD, JobState.QUEUED));
    }

    @Test
    void acceptsImmediateAndCooperativeCancellationPaths() {
        assertTrue(JobTransitionValidator.isAllowed(JobState.QUEUED, JobState.CANCELLED));
        assertTrue(JobTransitionValidator.isAllowed(JobState.WAITING_FOR_INPUT, JobState.CANCELLED));
        assertTrue(JobTransitionValidator.isAllowed(JobState.LEASED, JobState.CANCEL_REQUESTED));
        assertTrue(JobTransitionValidator.isAllowed(JobState.CANCEL_REQUESTED, JobState.CANCELLED));
    }

    @Test
    void rejectsLateOrSelfTransitions() {
        assertFalse(JobTransitionValidator.isAllowed(JobState.SUCCEEDED, JobState.QUEUED));
        assertFalse(JobTransitionValidator.isAllowed(JobState.CANCELLED, JobState.QUEUED));
        assertFalse(JobTransitionValidator.isAllowed(JobState.QUEUED, JobState.CANCEL_REQUESTED));
        assertFalse(JobTransitionValidator.isAllowed(JobState.WAITING_FOR_INPUT, JobState.CANCEL_REQUESTED));
        assertFalse(JobTransitionValidator.isAllowed(JobState.LEASED, JobState.CANCELLED));
        assertFalse(JobTransitionValidator.isAllowed(JobState.LEASED, JobState.LEASED));
        assertThrows(
                InvalidJobTransitionException.class,
                () -> JobTransitionValidator.requireAllowed(JobState.QUEUED, JobState.SUCCEEDED));
        assertThrows(
                InvalidJobTransitionException.class,
                () -> JobTransitionValidator.requireAllowed(JobState.CANCEL_REQUESTED, JobState.SUCCEEDED));
    }

    @Test
    void releaseMakesRetryTimingAndDeadOrWaitingStatesExplicit() {
        OffsetDateTime retryAt = OffsetDateTime.parse("2026-09-11T16:00:00Z");
        assertDoesNotThrow(() -> new JobRelease(JobState.QUEUED, retryAt, "retry later"));
        assertDoesNotThrow(() -> new JobRelease(JobState.WAITING_FOR_INPUT, null, "input needed"));
        assertDoesNotThrow(() -> new JobRelease(JobState.DEAD, null, "attempt limit reached"));

        assertThrows(IllegalArgumentException.class, () -> new JobRelease(JobState.QUEUED, null, "retry later"));
        assertThrows(IllegalArgumentException.class, () -> new JobRelease(JobState.SUCCEEDED, null, "invalid"));
    }

    @Test
    void completionAndStagedOutputRequireLeaseSafeMetadata() {
        JobTarget target = new JobTarget("revision", 40L, 41L);
        assertDoesNotThrow(() -> new JobCompletion(target, JobState.SUCCEEDED, "completed"));
        assertDoesNotThrow(() -> new JobLeaseToken(11L, "worker-a", 1L));
        assertDoesNotThrow(() -> new ResumeJobCommand(
                new IdempotencyKey("resume-key"),
                CanonicalRequestHash.sha256OfCanonicalText("resume-request"),
                11L));
        assertDoesNotThrow(() -> new StagedOutputRequest(
                "render", "temporary/opaque-key", null, null, OffsetDateTime.parse("2026-09-12T00:00:00Z")));

        assertThrows(IllegalArgumentException.class, () -> new JobCompletion(target, JobState.QUEUED, "not final"));
        assertThrows(IllegalArgumentException.class, () -> new JobCompletion(target, JobState.CANCELLED, "not a worker completion"));
        assertThrows(IllegalArgumentException.class, () -> new JobLeaseToken(11L, "worker-a", 0L));
        assertThrows(IllegalArgumentException.class, () -> new ResumeJobCommand(
                new IdempotencyKey("resume-key"),
                CanonicalRequestHash.sha256OfCanonicalText("resume-request"),
                0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StagedOutputRequest(
                        "render", "temporary/opaque-key", "a".repeat(64), null, OffsetDateTime.parse("2026-09-12T00:00:00Z")));
    }
}
