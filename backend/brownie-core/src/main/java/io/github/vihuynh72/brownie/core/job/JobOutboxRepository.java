package io.github.vihuynh72.brownie.core.job;

import java.util.List;

/** Browser-context inspection of metadata-only transactional outbox records. */
public interface JobOutboxRepository {

    List<OutboxEvent> findByJob(long workspaceId, long actorUserId, long jobId);
}
