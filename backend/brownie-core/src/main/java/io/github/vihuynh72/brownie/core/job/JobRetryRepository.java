package io.github.vihuynh72.brownie.core.job;

/**
 * Starting a dead job again. Separate from {@link JobCommandRepository}
 * because it is a different kind of write: the API login may only ever
 * touch a job that is still active, so this crosses a tenant-checked
 * database routine, which also records the restart in the audit trail and
 * gives the run its request allowance again.
 */
public interface JobRetryRepository {

    JobRetryOutcome retry(long workspaceId, long actorUserId, long jobId);
}
