package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.ArtifactRetentionRepository;
import io.github.vihuynh72.brownie.core.retention.ArtifactRetentionSweeper;
import io.github.vihuynh72.brownie.core.retention.DeletionArchiveRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionArchiver;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import io.github.vihuynh72.brownie.core.retention.DeletionSweepRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionSweeper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the background half of deletion into the trusted worker, the only process that removes a deleted document's stored objects. */
@Configuration
class RetentionConfig {

    @Bean
    DeletionSweeper deletionSweeper(DeletionSweepRepository sweepRepository, BlobStore blobStore) {
        return new DeletionSweeper(sweepRepository, blobStore);
    }

    @Bean
    DeletionArchiver deletionArchiver(DeletionArchiveRepository archiveRepository, DeletionLedgerArchive archive) {
        return new DeletionArchiver(archiveRepository, archive);
    }

    /**
     * The defaults are the published retention periods: an abandoned or
     * refused upload's bytes are gone within a day, and a scan is called
     * stuck long after the scanner's own timeouts would have ended it.
     */
    @Bean
    ArtifactRetentionSweeper artifactRetentionSweeper(
            ArtifactRetentionRepository retentionRepository,
            BlobStore blobStore,
            @Value("${brownie.worker.retention.artifact-sweep.abandoned-after:PT24H}") Duration abandonedAfter,
            @Value("${brownie.worker.retention.artifact-sweep.stuck-scan-after:PT15M}") Duration stuckScanAfter,
            @Value("${brownie.worker.retention.artifact-sweep.rejected-after:PT24H}") Duration rejectedAfter,
            @Value("${brownie.worker.retention.artifact-sweep.unreferenced-after:PT24H}") Duration unreferencedAfter) {
        return new ArtifactRetentionSweeper(
                retentionRepository,
                blobStore,
                new ArtifactRetentionSweeper.Periods(abandonedAfter, stuckScanAfter, rejectedAfter, unreferencedAfter));
    }
}
