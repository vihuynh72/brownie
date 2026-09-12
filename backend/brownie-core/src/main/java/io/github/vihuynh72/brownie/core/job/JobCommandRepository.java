package io.github.vihuynh72.brownie.core.job;

import java.util.Optional;
import java.util.UUID;

/**
 * Browser-context persistence operations. Each mutating method must write its
 * idempotency record, receipt, job transition event, and applicable outbox
 * record in one database transaction under the acting user's tenant context.
 */
public interface JobCommandRepository {

    CommandReceipt enqueue(long workspaceId, long actorUserId, EnqueueJobCommand command);

    CommandReceipt requestCancellation(long workspaceId, long actorUserId, CancellationCommand command);

    CommandReceipt requestResume(long workspaceId, long actorUserId, ResumeJobCommand command);

    Optional<Job> find(long workspaceId, long actorUserId, long jobId);

    Optional<CommandReceipt> findReceipt(long workspaceId, long actorUserId, UUID commandId);
}
