package io.github.vihuynh72.brownie.core.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The complete queue-level transition graph. Persistence adapters call this
 * before issuing a state-changing query, while their SQL predicates provide
 * the corresponding concurrent-write guard. Reclaiming an expired lease keeps
 * the state at LEASED and is a fenced lease replacement, not a lifecycle
 * transition.
 */
public final class JobTransitionValidator {

    private static final Map<JobState, Set<JobState>> ALLOWED = allowedTransitions();

    private JobTransitionValidator() {
    }

    public static boolean isAllowed(JobState from, JobState to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        return ALLOWED.get(from).contains(to);
    }

    public static void requireAllowed(JobState from, JobState to) {
        if (!isAllowed(from, to)) {
            throw new InvalidJobTransitionException(from, to);
        }
    }

    public static void requireAllowed(JobTransition transition) {
        Objects.requireNonNull(transition, "transition must not be null");
        requireAllowed(transition.from(), transition.to());
    }

    private static Map<JobState, Set<JobState>> allowedTransitions() {
        Map<JobState, Set<JobState>> transitions = new EnumMap<>(JobState.class);
        transitions.put(JobState.QUEUED, EnumSet.of(JobState.LEASED, JobState.DEAD, JobState.CANCELLED));
        transitions.put(JobState.LEASED, EnumSet.of(
                JobState.QUEUED,
                JobState.WAITING_FOR_INPUT,
                JobState.SUCCEEDED,
                JobState.FAILED,
                JobState.DEAD,
                JobState.CANCEL_REQUESTED));
        transitions.put(JobState.WAITING_FOR_INPUT, EnumSet.of(JobState.QUEUED, JobState.DEAD, JobState.CANCELLED));
        transitions.put(JobState.SUCCEEDED, EnumSet.noneOf(JobState.class));
        transitions.put(JobState.FAILED, EnumSet.of(JobState.QUEUED));
        transitions.put(JobState.DEAD, EnumSet.of(JobState.QUEUED));
        transitions.put(JobState.CANCEL_REQUESTED, EnumSet.of(JobState.CANCELLED));
        transitions.put(JobState.CANCELLED, EnumSet.noneOf(JobState.class));
        return Map.copyOf(transitions);
    }
}
