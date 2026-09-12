package io.github.vihuynh72.brownie.worker.job;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.JobOutputPublisher;
import io.github.vihuynh72.brownie.core.job.JobOutputReconciler;
import io.github.vihuynh72.brownie.core.job.StagedOutputCleanupRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires bounded verified-output handling into the trusted worker. */
@Configuration
class JobOutputConfig {

    @Bean
    JobOutputPublisher jobOutputPublisher(
            JobLeaseRepository jobLeaseRepository,
            BlobStore blobStore,
            @Value("${brownie.worker.outputs.max-bytes:10485760}") long maxOutputBytes,
            @Value("${brownie.worker.outputs.staged-ttl:PT15M}") Duration stagedOutputTtl) {
        return new JobOutputPublisher(jobLeaseRepository, blobStore, maxOutputBytes, stagedOutputTtl);
    }

    @Bean
    JobOutputReconciler jobOutputReconciler(
            StagedOutputCleanupRepository cleanupRepository,
            BlobStore blobStore) {
        return new JobOutputReconciler(cleanupRepository, blobStore);
    }
}
