package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.job.JobFailure;
import io.github.vihuynh72.brownie.core.job.JobFailureKind;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.LeasedJob;
import io.github.vihuynh72.brownie.core.job.WorkerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * The first real claim-and-execute loop this codebase has ever run: every
 * tick, claim at most one eligible job and dispatch it by type. Only one
 * job type exists today ({@link GenerationJobTypes#EXTRACTION_JOB_TYPE});
 * an unrecognized
 * type is released back to {@code DEAD} rather than silently dropped or
 * looped on forever, since nothing in this codebase enqueues any other
 * kind yet and a stray one would mean a real configuration mistake worth
 * surfacing loudly.
 */
@Component
@ConditionalOnProperty(name = "brownie.worker.generation.enabled", havingValue = "true", matchIfMissing = true)
class GenerationJobPoller {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobPoller.class);

    private final JobLeaseRepository jobLeaseRepository;
    private final GenerationExtractionJobProcessor processor;
    private final WorkerId workerId;
    private final Duration leaseDuration;

    GenerationJobPoller(
            JobLeaseRepository jobLeaseRepository,
            GenerationExtractionJobProcessor processor,
            WorkerId workerId,
            @Value("${brownie.worker.generation.lease-duration:PT2M}") Duration leaseDuration) {
        this.jobLeaseRepository = Objects.requireNonNull(jobLeaseRepository, "jobLeaseRepository must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(fixedDelayString = "${brownie.worker.generation.poll-delay:PT2S}")
    void pollOnce() {
        jobLeaseRepository.claimNext(workerId, leaseDuration).ifPresent(this::dispatch);
    }

    private void dispatch(LeasedJob leasedJob) {
        String jobType = leasedJob.job().type().value();
        if (!GenerationJobTypes.EXTRACTION_JOB_TYPE.equals(jobType)) {
            log.error("Claimed job {} has unrecognized type {}; no handler exists for it.", leasedJob.job().id(), jobType);
            jobLeaseRepository.releaseAfterFailure(
                    leasedJob.leaseToken(),
                    new JobFailure(JobFailureKind.DETERMINISTIC, "UNKNOWN_JOB_TYPE", "No handler is registered for this job type.", null));
            return;
        }
        try {
            processor.process(leasedJob);
        } catch (RuntimeException e) {
            log.error("Unexpected failure processing generation job {}.", leasedJob.job().id(), e);
            jobLeaseRepository.releaseAfterFailure(
                    leasedJob.leaseToken(),
                    new JobFailure(JobFailureKind.TRANSIENT_SERVER, "GENERATION_PROCESSOR_UNEXPECTED_FAILURE", "An unexpected error occurred.", null));
        }
    }
}
