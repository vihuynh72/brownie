package io.github.vihuynh72.brownie.core.job;

import java.util.List;

/**
 * Browser-context reads of the durable, ordered metadata event stream. The
 * cursor is the globally monotonic {@link JobEvent#id()}, not a per-job
 * sequence, so one workspace stream can safely include several jobs.
 */
public interface JobEventRepository {

    List<JobEvent> findAfter(long workspaceId, long actorUserId, long afterEventId, int limit);

    /** The job's most recent event, so a caller can say what a terminal job said last. */
    java.util.Optional<JobEvent> latestForJob(long workspaceId, long actorUserId, long jobId);

    /**
     * Returns whether this actor can still read the global event cursor within
     * the requested workspace. This distinguishes a missing reconnect cursor
     * from an empty page after a valid cursor.
     */
    boolean exists(long workspaceId, long actorUserId, long eventId);
}
