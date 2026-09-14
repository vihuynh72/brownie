package io.github.vihuynh72.brownie.core.job;

import java.util.Optional;

/**
 * Browser-context read access to a job's own published outputs -- the
 * artifact a worker produced and {@link JobLeaseRepository#publishStagedOutput}
 * already verified and attached. Deliberately read-only: only the worker's
 * own lease-scoped routines may ever create or change one of these rows.
 */
public interface JobOutputArtifactRepository {

    /** The artifact ID published for this job under this exact output kind, if the worker has published one yet. */
    Optional<Long> findArtifactId(long workspaceId, long userId, long jobId, String outputKind);
}
